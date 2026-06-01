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
import kotlinx.coroutines.flow.transform
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
                _state.value = EngineState.Ready
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
            val transcriptPool = StringBuilder()
            asrEngine.transcribe(audioCapture.frames(mediaProjection))
                .transform { asrResult ->
                    if (asrResult is AsrResult.Partial) {
                        val partialState = processPartialResult(transcriptPool, asrResult.text)
                        if (partialState != null) emit(partialState)
                        return@transform
                    }

                    val finalChunk = asrResult.text.trim()
                    if (finalChunk.isEmpty()) return@transform

                    appendFinalChunk(transcriptPool, finalChunk)

                    while (true) {
                        val finalState = extractNextCompletedSentence(transcriptPool)
                        if (finalState != null) {
                            emit(finalState)
                        } else {
                            break
                        }
                    }
                }
                .catch { e -> _state.value = EngineState.Error(e) }
                .collect { _state.value = it }
        }
    }

    private suspend fun processPartialResult(
        transcriptPool: StringBuilder,
        partialText: String
    ): EngineState.Translating? {
        val trimmed = partialText.trim()
        if (trimmed.isEmpty()) return null
        
        val tempSentence = ("$transcriptPool $trimmed").trim()
        val translated = translationEngine.translate(tempSentence)
        
        return EngineState.Translating(
            originalText = tempSentence,
            translatedText = translated,
            isFinal = false,
            mode = EngineState.CaptureMode.AUDIO,
        )
    }

    private fun appendFinalChunk(transcriptPool: StringBuilder, finalChunk: String) {
        if (transcriptPool.isNotEmpty() && !transcriptPool.endsWith(" ")) {
            transcriptPool.append(" ")
        }
        transcriptPool.append(finalChunk)
    }

    private suspend fun extractNextCompletedSentence(transcriptPool: StringBuilder): EngineState.Translating? {
        val currentPool = transcriptPool.toString()
        val words = currentPool.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        
        val punctuationIndex = currentPool.indexOfAny(PUNCTUATION_MARKS)
        val shouldExtract = punctuationIndex != -1 || words.size >= MAX_WORDS_PER_SENTENCE
        
        if (!shouldExtract) return null
        
        val extractEndIndex = if (punctuationIndex != -1) punctuationIndex + 1 else currentPool.length
        val sentenceToTranslate = currentPool.substring(0, extractEndIndex).trim()
        transcriptPool.delete(0, extractEndIndex)
        
        val translated = translationEngine.translate(sentenceToTranslate)
        return EngineState.Translating(
            originalText = sentenceToTranslate,
            translatedText = translated,
            isFinal = true,
            mode = EngineState.CaptureMode.AUDIO,
        )
    }

    override fun close() {
        prepJob?.cancel()
        sessionJob?.cancel()
        asrEngine.release()
        translationEngine.release()
        _state.value = EngineState.Idle
    }

    companion object {
        private val PUNCTUATION_MARKS = charArrayOf('.', '?', '!')
        private const val MAX_WORDS_PER_SENTENCE = 20
    }
}