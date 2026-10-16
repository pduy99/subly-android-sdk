package com.duypham.translation.benchmark

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.helios.subly.asr.whisper.WhisperTranscriber
import com.helios.subly.benchmark.FileAudioCapture
import com.helios.subly.benchmark.latency.PushAudioCapture
import com.helios.subly.core.model.AccelerationReport
import com.helios.subly.core.model.EngineState
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.sdk.Subly
import com.helios.subly.sdk.SublySession
import com.helios.subly.sdk.streaming.StreamingTranslationStage
import com.helios.subly.sdk.streaming.WaitKPolicy
import com.helios.subly.translator.litertlm.LiteRtLmTranslator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.helios.subly.core.model.DelegateType as SdkDelegateType
import com.helios.subly.sdk.streaming.TranslationState as SdkTranslationState

/*
 * Binds the types PipelineBenchmarkTest uses to the Subly SDK.
 *
 * Nothing here is a stand-in for the pipeline. Audio goes through a real
 * SublySession: Whisper (whisper.cpp), the wait-k buffer and the streaming
 * stage driving LiteRT-LM. Transcript mocks skip ASR only, as the test
 * intends, and drive the same StreamingTranslationStage and translator. The
 * one naming gap: the test says "Gemma", and the SDK's translator model is
 * Qwen2.5-1.5B-Instruct (Gemma is excluded for licence reasons, see
 * LiteRtLmModel), so gemmaDelegate reports the LLM translator, whichever
 * model it runs.
 */

enum class DelegateType { CPU, GPU, NPU }

data class PipelineConfig(
    val whisperDelegate: DelegateType,
    val gemmaDelegate: DelegateType,
    /** Full reports, attempts and failure reasons included, for diagnostics. */
    val reports: List<AccelerationReport>,
)

/**
 * @property data 16-bit little-endian mono PCM at 16 kHz.
 * @property transcriptMock text that stands in for what ASR would have heard
 *   in this chunk; when set, [data] is ignored and ASR is skipped.
 */
class AudioChunk(
    val data: ByteArray,
    val isSilence: Boolean,
    val transcriptMock: String? = null,
)

sealed class TranslationState {
    data class Partial(val text: String) : TranslationState()
    data class Finalized(val text: String) : TranslationState()
}

interface TranslationPipeline {
    /** Hot. Every translated partial and final, in order; no replay. */
    val stateFlow: Flow<TranslationState>
    fun getActiveConfig(): PipelineConfig
    suspend fun pushAudio(chunk: AudioChunk)
    suspend fun stopStream()
}

object TranslationPipelineFactory {
    private var current: SubtitlePipeline? = null

    /**
     * Builds and fully prepares a pipeline: Whisper for English audio, the
     * LLM translator, and transcript mocks taken as Japanese → English (the
     * test's romaji). The previous pipeline is closed first: each holds a
     * Whisper context and an LLM engine, and the test never closes them.
     */
    fun createPipeline(): TranslationPipeline {
        current?.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return SubtitlePipeline(
            context = context,
            audioLanguage = LanguageConfig(source = "en", target = "vi"),
            mockLanguage = LanguageConfig(source = "ja", target = "en"),
        ).also {
            it.prepare()
            current = it
        }
    }
}

class SubtitlePipeline(
    context: Context,
    private val audioLanguage: LanguageConfig,
    private val mockLanguage: LanguageConfig,
) : TranslationPipeline, AutoCloseable {

    private val translator = LiteRtLmTranslator(context)
    private val asr = WhisperTranscriber(context)
    private val capture = PushAudioCapture()
    private val session: SublySession = Subly.Builder()
        .setAsrEngine { asr }
        .setTranslator { translator }
        .setAudioCapture { capture }
        .build()
        .createSession(audioLanguage)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val states = MutableSharedFlow<TranslationState>(extraBufferCapacity = 1024)
    override val stateFlow: Flow<TranslationState> = states.asSharedFlow()

    private var audioStarted = false
    private var sessionFinal: CompletableDeferred<Unit>? = null

    private var stage: StreamingTranslationStage? = null
    private var stageWorker: Job? = null
    private var stageFinal: CompletableDeferred<Unit>? = null
    private var mockHypothesis = ""

    fun prepare() = runBlocking {
        session.prepare()
        val state = withTimeout(PREPARE_TIMEOUT_MS) {
            session.state.first { it is EngineState.Ready || it is EngineState.Error }
        }
        check(state is EngineState.Ready) { "pipeline failed to prepare: $state" }
        session.accelerationReports().forEach { Log.i(TAG, "delegate: $it") }
    }

    override fun getActiveConfig(): PipelineConfig {
        val reports = session.accelerationReports()
        fun delegateOf(prefix: String) = reports.firstOrNull { it.component.startsWith(prefix) }
            ?.active?.let { DelegateType.valueOf(it.name) } ?: DelegateType.CPU
        return PipelineConfig(
            whisperDelegate = delegateOf("asr:"),
            gemmaDelegate = delegateOf("translator:"),
            reports = reports,
        )
    }

    override suspend fun pushAudio(chunk: AudioChunk) {
        val mock = chunk.transcriptMock
        if (mock != null) {
            mockStage().onPartial(mockHypothesis.plus(mock).trim().also { mockHypothesis += mock })
            return
        }
        if (!audioStarted) startAudio()
        capture.push(toShorts(chunk.data))
    }

    override suspend fun stopStream() {
        stage?.let { s ->
            s.onFinal(mockHypothesis.trim())
            s.finish()
            stageFinal?.await()
            stageWorker?.join()
            stage = null
            mockHypothesis = ""
        }
        if (audioStarted) {
            capture.end()
            withTimeout(STOP_TIMEOUT_MS) { sessionFinal?.await() }
            audioStarted = false
        }
        // Give collectors on other dispatchers a turn to take the last state
        // before the caller cancels them.
        yield()
    }

    private suspend fun mockStage(): StreamingTranslationStage {
        stage?.let { return it }
        // Same engine, other language pair: re-preparing on a warm engine
        // only swaps the prompt.
        translator.prepareModel(mockLanguage).first { it is ModelPrepState.Ready || it is ModelPrepState.Error }
        val done = CompletableDeferred<Unit>().also { stageFinal = it }
        return StreamingTranslationStage(
            translator = translator,
            policy = WaitKPolicy.forLanguage(mockLanguage.source),
            onState = { publish(it, done) },
        ).also {
            stage = it
            stageWorker = scope.launch { it.run() }
        }
    }

    private suspend fun startAudio() {
        // The audio pair may have been swapped out by a mock run.
        translator.prepareModel(audioLanguage).first { it is ModelPrepState.Ready || it is ModelPrepState.Error }
        val done = CompletableDeferred<Unit>().also { sessionFinal = it }
        scope.launch { session.translationState.collect { publish(it, done) } }
        audioStarted = true
        session.start()
    }

    private suspend fun publish(state: SdkTranslationState, done: CompletableDeferred<Unit>) {
        when (state) {
            is SdkTranslationState.Partial ->
                if (state.text.isNotBlank()) states.emit(TranslationState.Partial(state.text))
            is SdkTranslationState.Finalized -> {
                states.emit(TranslationState.Finalized(state.text))
                done.complete(Unit)
            }
            is SdkTranslationState.Failed -> {
                Log.e(TAG, "translation failed", state.error)
                done.complete(Unit)
            }
            SdkTranslationState.Idle -> Unit
        }
    }

    override fun close() {
        scope.cancel()
        session.close()
    }

    private fun toShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private companion object {
        const val TAG = "PipelineAdapter"
        const val PREPARE_TIMEOUT_MS = 45 * 60 * 1_000L
        const val STOP_TIMEOUT_MS = 60_000L
    }
}

/**
 * Supplies speech audio for latency tests.
 *
 * "Generate" is the test's word; a tone or noise generator would give ASR
 * nothing to transcribe and the pipeline nothing to translate. This cuts the
 * most continuously voiced window of the requested length out of a recorded
 * FLEURS benchmark clip, so the timing is of real speech.
 */
object AudioGenerator {
    private const val SAMPLE_RATE = 16_000
    private const val CLIP = "audio/fleurs_en_01.mp4"

    fun generateContinuousSpeech(durationSec: Int): AudioChunk {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("speech", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            file.outputStream().use { out ->
                instrumentation.context.assets.open("dataset/$CLIP").use { it.copyTo(out) }
            }
            val pcm = FileAudioCapture(file).decodeMono16k()
            val window = durationSec * SAMPLE_RATE
            require(pcm.size >= window) { "$CLIP is shorter than $durationSec s" }

            // Highest summed energy over 100 ms steps: the most voiced window.
            val step = SAMPLE_RATE / 10
            var bestStart = 0
            var bestEnergy = -1.0
            var start = 0
            while (start + window <= pcm.size) {
                var energy = 0.0
                var i = start
                while (i < start + window) {
                    energy += kotlin.math.abs(pcm[i].toDouble())
                    i += 16
                }
                if (energy > bestEnergy) {
                    bestEnergy = energy
                    bestStart = start
                }
                start += step
            }

            val bytes = ByteBuffer.allocate(window * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in bestStart until bestStart + window) bytes.putShort(pcm[i])
            return AudioChunk(data = bytes.array(), isSilence = false)
        } finally {
            file.delete()
        }
    }
}
