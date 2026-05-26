package com.helios.subly.sdk.internal

import android.media.projection.MediaProjection
import com.helios.subly.sdk.data.ai.SherpaOnnxPunctuation
import com.helios.subly.sdk.data.vision.OcrRecognizer
import com.helios.subly.sdk.domain.model.EngineState
import com.helios.subly.sdk.domain.model.LanguageConfig
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.helios.subly.sdk.domain.repository.AiTranscriberRepository
import com.helios.subly.sdk.domain.repository.AudioCaptureRepository
import com.helios.subly.sdk.domain.repository.SublyEngine
import com.helios.subly.sdk.domain.repository.TranslatorRepository
import com.helios.subly.sdk.domain.repository.VisionCaptureRepository
import com.helios.subly.sdk.domain.usecase.DetectSystemSilenceUseCase
import com.helios.subly.sdk.domain.usecase.ProcessAudioStreamUseCase
import com.helios.subly.sdk.domain.usecase.ProcessOcrFrameUseCase
import com.helios.subly.sdk.domain.usecase.TranslatePacketUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import android.util.Log
import kotlin.coroutines.CoroutineContext

/**
 * Default [SublyEngine] implementation.
 */
internal class SublyEngineImpl(
    private val audioCapture: AudioCaptureRepository,
    private val transcriber: AiTranscriberRepository,
    private val translator: TranslatorRepository? = null,
    private val punctuation: SherpaOnnxPunctuation? = null,
    private val visionCapture: VisionCaptureRepository? = null,
    private val ocrRecognizer: OcrRecognizer? = null,
    private val silenceDetector: DetectSystemSilenceUseCase,
    dispatcher: CoroutineContext = Dispatchers.Default,
) : SublyEngine {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val packets = MutableSharedFlow<TranslationPacket>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Idle)

    /** Engine lifecycle for the Consumer UI; not part of the public PRD signature. */
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private var pipelineJob: Job? = null
    private var silenceJob: Job? = null
    private var visionJob: Job? = null

    init {
        punctuation?.init()
    }

    override fun startTranslationPipeline(
        mediaProjection: MediaProjection,
        targetLanguageCode: String,
    ): SharedFlow<TranslationPacket> {
        if (pipelineJob?.isActive == true) {
            return packets.asSharedFlow()
        }

        _engineState.value = EngineState.Starting
        val processAudio = ProcessAudioStreamUseCase(audioCapture, transcriber)
        val processOcr = if (visionCapture != null && ocrRecognizer != null) {
            ProcessOcrFrameUseCase(visionCapture, ocrRecognizer)
        } else {
            null
        }
        val translateStage = translator?.let { TranslatePacketUseCase(it) }
        val config = LanguageConfig(targetLanguageCode)

        // Subscribe to amplitudes before launching capture so the silence detector
        // doesn't miss the first frames (amplitudes is a replay=0 SharedFlow).
        silenceJob = scope.launch {
            silenceDetector(audioCapture.amplitudes()).collect { silenced ->
                if (silenced) {
                    _engineState.value = EngineState.DrmBlocked
                    startVisionFallback(mediaProjection, config, translateStage, processOcr)
                } else {
                    _engineState.value = EngineState.Capturing(EngineState.CaptureMode.AUDIO)
                    stopVisionFallback()
                }
            }
        }

        _engineState.value = EngineState.Capturing(EngineState.CaptureMode.AUDIO)
        val raw = processAudio(mediaProjection, config)
        pipelineJob = (translateStage?.invoke(raw, config) ?: raw)
            .onEach { packets.emit(it) }
            .catch { error ->
                Log.e("SublyEngine", "Pipeline crashed", error)
                _engineState.value = EngineState.Idle
            }
            .launchIn(scope)

        return packets.asSharedFlow()
    }

    override fun stopTranslationPipeline() {
        _engineState.value = EngineState.Stopping
        silenceJob?.cancel(); silenceJob = null
        pipelineJob?.cancel(); pipelineJob = null
        stopVisionFallback()
        audioCapture.stop()
        // Native release blocks until any in-flight JNI transcribe()
        // completes. Punt it to the
        // engine scope's worker dispatcher so we don't ANR a UI/main-thread
        // caller (e.g. FGS.onDestroy).
        scope.launch {
            runCatching { transcriber.release() }
            runCatching { translator?.release() }
            runCatching { punctuation?.release() }
            _engineState.value = EngineState.Idle
        }
    }

    /**
     * Spawn the vision fallback flow on the first DRM-block. Idempotent: a
     * subsequent silence event while [visionJob] is still active is a no-op,
     * so a flapping silence detector won't churn `VirtualDisplay` resources.
     */
    private fun startVisionFallback(
        mediaProjection: MediaProjection,
        config: LanguageConfig,
        translateStage: TranslatePacketUseCase?,
        processOcr: ProcessOcrFrameUseCase?,
    ) {
        if (processOcr == null) return
        if (visionJob?.isActive == true) return
        
        val raw = processOcr(mediaProjection, config)
        // OCR text already arrives as complete recognised lines - no
        // need for clause gating; mark them isFinal so NMT runs.
        val finalised = raw // OCR pipeline emits isFinal=true packets.
        
        visionJob = (translateStage?.invoke(finalised, config) ?: finalised)
            .onEach { packets.emit(it) }
            .catch { error ->
                Log.e("SublyEngine", "Vision pipeline crashed", error)
            }
            .launchIn(scope)
    }

    private fun stopVisionFallback() {
        visionJob?.cancel(); visionJob = null
        visionCapture?.stop()
    }
}
