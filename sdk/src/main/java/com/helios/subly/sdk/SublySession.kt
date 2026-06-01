package com.helios.subly.sdk

import android.media.projection.MediaProjection
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.core.data.repository.AudioCapture
import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.EngineState
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.translator.api.SublyTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

class SublySession internal constructor(
    private val config: LanguageConfig,
    private val audioCapture: AudioCapture,
    private val asrEngine: SublyAsr,
    private val translationEngine: SublyTranslator,
) : AutoCloseable {

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private var prepJob: Job? = null
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        prepareModels()
    }

    private fun prepareModels() {
        prepJob = sessionScope.launch {
            val asrPrepFlow = asrEngine.prepareModel(config)
            val transPrepFlow = translationEngine.prepareModel(config)

            combine(asrPrepFlow, transPrepFlow) { asr, trans -> Pair(asr, trans) }
                .takeWhile { (asr, trans) ->
                    val isReady = asr is ModelPrepState.Ready && trans is ModelPrepState.Ready
                    val hasError = asr is ModelPrepState.Error || trans is ModelPrepState.Error

                    if (hasError) {
                        val error = (asr as? ModelPrepState.Error)?.cause
                            ?: (trans as? ModelPrepState.Error)?.cause
                            ?: RuntimeException("Preparation failed")
                        _state.value = EngineState.Error(error)
                        return@takeWhile false
                    }

                    _state.value = EngineState.Preparing(asr, trans)
                    !isReady
                }.collect()
                
            if (_state.value !is EngineState.Error) {
                // Once ready, you might want to explicitly set a Ready state, 
                // but Preparing with (Ready, Ready) is handled by consumer. 
                // We leave it as Preparing(Ready, Ready) or we could define a specific Ready state if it existed.
                // Looking at EngineState.kt, if there is no separate Ready state, the app checks if both are Ready.
            }
        }
    }

    fun startTranslationPipeline(mediaProjection: MediaProjection) {
        if (sessionJob?.isActive == true) return

        sessionJob = sessionScope.launch {
            // Wait for prep to finish before capturing audio if it's somehow not finished.
            prepJob?.join()
            
            if (_state.value is EngineState.Error) return@launch

            // 2. Start Processing
            asrEngine.transcribe(audioCapture.frames(mediaProjection))
                .map { asrResult ->
                    val transcript = when (asrResult) {
                        is AsrResult.Partial -> asrResult.text
                        is AsrResult.Final -> asrResult.text
                    }

                    // The engine handles translation implicitly based on prior preparation
                    val translated = translationEngine.translate(transcript)

                    EngineState.Translating(
                        originalText = transcript,
                        translatedText = translated,
                        isFinal = asrResult is AsrResult.Final,
                        mode = EngineState.CaptureMode.AUDIO,
                    )
                }
                .catch { e -> _state.value = EngineState.Error(e) }
                .collect { _state.value = it }
        }
    }

    override fun close() {
        prepJob?.cancel()
        sessionJob?.cancel()
        asrEngine.release()
        translationEngine.release()
        _state.value = EngineState.Idle
    }
}