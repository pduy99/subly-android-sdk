package com.helios.subly.sdk.internal

import android.content.Context
import android.media.projection.MediaProjection
import com.helios.subly.sdk.domain.model.EngineState
import com.helios.subly.sdk.domain.model.LanguageConfig
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.helios.subly.sdk.domain.repository.AiTranscriberRepository
import com.helios.subly.sdk.domain.repository.AudioCaptureRepository
import com.helios.subly.sdk.domain.repository.SublyEngine
import com.helios.subly.sdk.domain.usecase.DetectSystemSilenceUseCase
import com.helios.subly.sdk.domain.usecase.ProcessAudioStreamUseCase
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
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Default [SublyEngine] implementation.
 */
internal class SublyEngineImpl(
    private val appContext: Context,
    private val audioCapture: AudioCaptureRepository,
    private val transcriber: AiTranscriberRepository,
    private val silenceDetector: DetectSystemSilenceUseCase = DetectSystemSilenceUseCase(),
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

    override fun startTranslationPipeline(
        mediaProjection: MediaProjection,
        targetLanguageCode: String,
    ): SharedFlow<TranslationPacket> {
        if (pipelineJob?.isActive == true) return packets.asSharedFlow()

        _engineState.value = EngineState.Starting
        val processAudio = ProcessAudioStreamUseCase(audioCapture, transcriber)
        val config = LanguageConfig(targetLanguageCode)

        // Subscribe to amplitudes before launching capture so the silence detector
        // doesn't miss the first frames (amplitudes is a replay=0 SharedFlow).
        silenceJob = scope.launch {
            silenceDetector(audioCapture.amplitudes()).collect { silenced ->
                _engineState.value = if (silenced) {
                    EngineState.DrmBlocked
                } else {
                    EngineState.Capturing(EngineState.CaptureMode.AUDIO)
                }
            }
        }

        pipelineJob = scope.launch {
            _engineState.value = EngineState.Capturing(EngineState.CaptureMode.AUDIO)
            processAudio(mediaProjection, config).collect { packets.tryEmit(it) }
        }

        return packets.asSharedFlow()
    }

    override fun stopTranslationPipeline() {
        _engineState.value = EngineState.Stopping
        silenceJob?.cancel(); silenceJob = null
        pipelineJob?.cancel(); pipelineJob = null
        audioCapture.stop()
        transcriber.release()
        _engineState.value = EngineState.Idle
    }
}
