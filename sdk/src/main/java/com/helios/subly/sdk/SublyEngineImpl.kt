package com.helios.subly.sdk

import android.media.projection.MediaProjection
import com.helios.subly.asr.api.AsrDataSource
import com.helios.subly.core.data.repository.AudioCaptureRepository
import com.helios.subly.translator.api.TranslatorDataSource
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.core.model.TranslationPacket
import com.helios.subly.sdk.domain.usecase.ProcessAudioStreamUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Default [SublyEngine] implementation.
 */
internal class SublyEngineImpl(
    private val audioCapture: AudioCaptureRepository,
    private val transcriber: AsrDataSource,
    private val translator: TranslatorDataSource,
    dispatcher: CoroutineContext = Dispatchers.Default,
) : SublyEngine {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val packets = MutableSharedFlow<TranslationPacket>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Idle)
    private val _prepState = MutableStateFlow<ModelPrepState>(ModelPrepState.Idle)

    /** Engine lifecycle for the Consumer UI; not part of the public PRD signature. */
    override val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private var pipelineJob: Job? = null
    private var silenceJob: Job? = null
    private var prepJob: Job? = null

    /**
     * Stages:
     *  - Stage 1 (ASR, 0%→60%): extract model from APK assets + native init.
     *  - Stage 2 (Translator, 60%→100%): download / initialise ML Kit model.
     *
     * Each stage feeds its [0,1] progress into the combined window above.
     * Cancels any in-flight preparation before starting a new one.
     */
    override fun prepareModels(languageConfig: LanguageConfig): StateFlow<ModelPrepState> {
        prepJob?.cancel()
        _prepState.value = ModelPrepState.Preparing(0f, "Starting…")

        prepJob = scope.launch {
            runCatching {
                // Stage 1: ASR (60% of total)
                transcriber.prepareModel(languageConfig).collect { asrProgress ->
                    _prepState.value = ModelPrepState.Preparing(
                        progress = asrProgress * ASR_WEIGHT,
                        description = "Preparing speech recognition…",
                    )
                }

                // Stage 2: Translator (remaining 40%)
                translator.prepareModel(languageConfig).collect { translatorProgress ->
                    _prepState.value = ModelPrepState.Preparing(
                        progress = ASR_WEIGHT + translatorProgress * TRANSLATOR_WEIGHT,
                        description = "Preparing translation model…",
                    )
                }

                _prepState.value = ModelPrepState.Ready
            }.onFailure { cause ->
                _prepState.value = ModelPrepState.Error(
                    message = cause.message ?: "Model preparation failed",
                    cause = cause,
                )
            }
        }

        return _prepState.asStateFlow()
    }

    override fun startTranslationPipeline(
        mediaProjection: MediaProjection,
        languageConfig: LanguageConfig
    ): SharedFlow<TranslationPacket> {
        if (pipelineJob?.isActive == true) {
            return packets.asSharedFlow()
        }

        _engineState.value = EngineState.Starting
        val processAudio = ProcessAudioStreamUseCase(audioCapture, transcriber)

        _engineState.value = EngineState.Capturing(EngineState.CaptureMode.AUDIO)
        val raw = processAudio(mediaProjection)
        pipelineJob = raw
            .applyTranslate(languageConfig)
            .catch { _ ->
                _engineState.value = EngineState.Idle
            }
            .onEach {
                packets.emit(it)
            }
            .launchIn(scope)

        return packets.asSharedFlow()
    }

    override fun stopTranslationPipeline() {
        _engineState.value = EngineState.Stopping
        silenceJob?.cancel(); silenceJob = null
        pipelineJob?.cancel(); pipelineJob = null
        audioCapture.stop()
        scope.launch {
            _engineState.value = EngineState.Idle
        }
    }

    /**
     * Latest-wins streaming translate stage.
     *
     * Whisper emits cumulative partial transcripts every few hundred ms; each
     * Gemma translation streams tokens over several seconds. We pair them with
     * `transformLatest` so:
     *   - Each fresh transcript packet preempts the in-flight translation
     *     (the prior `translator.translate(...)` Flow is cancelled, which in
     *     `LiteRtGemmaTranslator` triggers `Conversation.cancelProcess()` to
     *     free the native session immediately).
     *   - Every streamed partial translation is emitted downstream as soon as
     *     the model produces it, so captions grow in real time instead of
     *     popping in only after the model finishes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Flow<TranslationPacket>.applyTranslate(
        languageConfig: LanguageConfig,
    ): Flow<TranslationPacket> = mapLatest { packet ->
        val translated = translator.translate(packet.text, languageConfig)
        packet.copy(text = translated)
    }

    private companion object {
        /** ASR model extraction dominates preparation time (~300 MB asset copy). */
        const val ASR_WEIGHT = 0.6f
        const val TRANSLATOR_WEIGHT = 1f - ASR_WEIGHT
    }
}