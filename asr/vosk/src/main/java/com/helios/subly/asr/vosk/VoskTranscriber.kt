package com.helios.subly.asr.vosk

import android.content.Context
import android.util.Log
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.core.downloader.ModelDownloader
import com.helios.subly.core.downloader.OkHttpModelDownloader
import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transform
import java.util.concurrent.atomic.AtomicReference

/**
 * Vosk (Kaldi) on-device ASR engine.
 *
 * Streaming recognizer: PCM frames are pushed into a single [Recognizer]; when
 * Vosk detects an utterance endpoint we emit an [AsrResult.Final], otherwise a
 * debounced [AsrResult.Partial]. Output is lowercase and unpunctuated (small
 * Kaldi models don't restore casing/punctuation) — the SDK's sentence assembly
 * leans on its word-count cap for these finals.
 *
 * Session-scoped per the [SublyAsr] contract: one instance per
 * [com.helios.subly.sdk.SublySession], `prepare -> transcribe -> release`, then
 * discard.
 */
class VoskTranscriber internal constructor(
    private val context: Context,
    private val backend: VoskBackend,
    private val modelLoaderFactory: VoskModelLoaderFactory,
) : SublyAsr {

    /**
     * @param modelDownloader downloader used to fetch model zips on first
     *   prepare. Defaults to a plain [OkHttpModelDownloader]; supply your own
     *   (e.g. an OkHttp client with auth headers, certificate pinning, or a
     *   private mirror) to control how models are fetched.
     */
    @JvmOverloads
    constructor(
        context: Context,
        modelDownloader: ModelDownloader = OkHttpModelDownloader(),
    ) : this(
        context = context,
        backend = VoskBackend.Jni,
        modelLoaderFactory = VoskModelLoaderFactory.of(modelDownloader),
    )

    /** Lazily-initialized model/recognizer pair. Cleared on [release]. */
    private val handleRef = AtomicReference<VoskBackend.Handle?>(null)

    /**
     * Serializes native calls. Vosk's [Recognizer] is not thread-safe;
     * concurrent acceptWaveForm/result calls would corrupt its decoder state.
     */
    private val nativeLock = Any()

    override fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult> {
        // Per the SublyAsr contract: fail loudly on collection rather than
        // silently draining audio (which presents to the user as "ready but no
        // captions ever appear").
        if (!backend.isAvailable() || handleRef.get() == null) {
            return flow {
                throw IllegalStateException(
                    "Vosk engine not prepared. Collect prepareModel() to Ready before transcribe()."
                )
            }
        }

        // Per-collection state for partial debounce/dedupe.
        var lastEmittedPartial = ""
        var lastPartialEmitMs = 0L

        return frames
            .transform { frame ->
                val pcm = toMono16kShorts(frame)
                if (pcm.isEmpty()) return@transform

                val endpoint = synchronized(nativeLock) {
                    val h = handleRef.get() ?: return@synchronized null
                    backend.acceptWaveform(h, pcm, pcm.size)
                } ?: return@transform

                if (endpoint) {
                    val finalText = synchronized(nativeLock) {
                        handleRef.get()?.let(backend::finalText)
                    }.orEmpty().trim()
                    if (finalText.isNotEmpty()) {
                        // NOTE: never log transcript content — it is end-user speech.
                        Log.d(TAG, "Emitted final packet (length=${finalText.length})")
                        emit(AsrResult.Final(finalText))
                    }
                    lastEmittedPartial = ""
                    lastPartialEmitMs = 0L
                    return@transform
                }

                // Partial path: skip if it didn't grow, or if we emitted very
                // recently (debounce). Downstream NMT is expensive; ~200 ms is
                // the sweet spot for a "realtime" feel without burning translate
                // quota on every frame.
                val partial = synchronized(nativeLock) {
                    handleRef.get()?.let(backend::partialText)
                }.orEmpty().trim()

                val now = frame.timestampMs
                val grew = partial.length > lastEmittedPartial.length
                val dueByTime = (now - lastPartialEmitMs) >= PARTIAL_MIN_INTERVAL_MS
                if (partial.isNotEmpty() && grew && dueByTime) {
                    lastEmittedPartial = partial
                    lastPartialEmitMs = now
                    Log.d(TAG, "Emitted partial packet (length=${partial.length})")
                    emit(AsrResult.Partial(partial))
                }
            }
            .flowOn(Dispatchers.Default)
    }

    /**
     * Resolves the model for [LanguageConfig.source], provisions it
     * (download + unpack), then opens the native recognizer.
     *
     * Per the [ModelPrepState] contract, every exit path emits a terminal
     * [ModelPrepState.Ready] or [ModelPrepState.Error] — never a silent
     * completion — and the flow is re-collectable for retry / fast-path when
     * already prepared.
     */
    override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = flow {
        emit(ModelPrepState.Checking)

        if (!backend.isAvailable()) {
            emit(
                ModelPrepState.Error(
                    IllegalStateException("Vosk native backend is not available on this device/ABI.")
                )
            )
            return@flow
        }

        if (handleRef.get() != null) {
            emit(ModelPrepState.Ready)
            return@flow
        }

        val model = VoskModels.forLanguage(config.source)
        if (model == null) {
            emit(
                ModelPrepState.Error(
                    IllegalArgumentException(
                        "Vosk has no model for source language '${config.source}'. " +
                            "Supported: ${supportedLanguages()}."
                    )
                )
            )
            return@flow
        }

        val loader = modelLoaderFactory.create(context)

        // Provision (download + unpack): 0% -> 90%.
        loader.provisionWithProgress(model)
            .onEach { p -> emit(ModelPrepState.Preparing(p * 0.9f)) }
            .collect {}

        // A finished-but-not-ready provision means no source was available or
        // the unpack failed. Emit Error rather than completing silently (which
        // the session would misread as a "false Ready").
        if (!loader.isReady(model)) {
            emit(
                ModelPrepState.Error(
                    IllegalStateException(
                        "Failed to provision Vosk model '${model.dirName}'. " +
                            "The download/unpack failed — check connectivity and retry."
                    )
                )
            )
            return@flow
        }

        emit(ModelPrepState.Preparing(0.95f))

        val created = backend.init(loader.modelDir(model).absolutePath, VOSK_SAMPLE_RATE_HZ)
        if (created == null) {
            emit(
                ModelPrepState.Error(
                    IllegalStateException("Vosk native init failed for model '${model.dirName}'.")
                )
            )
            return@flow
        }

        // If a concurrent prepare won the race, release ours and keep theirs.
        if (!handleRef.compareAndSet(null, created)) {
            runCatching { backend.release(created) }
        }

        emit(ModelPrepState.Ready)
    }.flowOn(Dispatchers.IO)

    override fun release() {
        synchronized(nativeLock) {
            handleRef.getAndSet(null)?.let { runCatching { backend.release(it) } }
        }
    }

    override fun supportedLanguages(): List<String> = VoskModels.supportedLanguageTags()

    /**
     * Converts an int16 mono/stereo [AudioFrame] to mono 16-bit PCM at
     * [VOSK_SAMPLE_RATE_HZ] — what the recognizer expects. Mono-mixes, then
     * integer-decimates (e.g. 48k -> 16k via stride-3 averaging) or falls back
     * to a linear resample for non-integer ratios. Aliasing above 8 kHz isn't
     * critical for speech ASR.
     */
    private fun toMono16kShorts(frame: AudioFrame): ShortArray {
        val pcm = frame.pcm
        val channels = frame.channelCount.coerceAtLeast(1)
        val sourceRate = frame.sampleRateHz

        // Step 1: mono-mix.
        val monoLen = pcm.size / channels
        if (monoLen == 0) return ShortArray(0)
        val mono = ShortArray(monoLen)
        if (channels == 1) {
            System.arraycopy(pcm, 0, mono, 0, monoLen)
        } else {
            var i = 0
            var j = 0
            while (i + channels <= pcm.size) {
                var acc = 0
                for (c in 0 until channels) acc += pcm[i + c].toInt()
                mono[j++] = (acc / channels).toShort()
                i += channels
            }
        }

        // Step 2: resample to 16 kHz if needed.
        if (sourceRate == VOSK_SAMPLE_RATE_HZ || sourceRate <= 0) return mono
        val ratio = sourceRate / VOSK_SAMPLE_RATE_HZ
        if (ratio <= 1 || sourceRate % VOSK_SAMPLE_RATE_HZ != 0) {
            return linearResample(mono, sourceRate, VOSK_SAMPLE_RATE_HZ)
        }
        val outLen = monoLen / ratio
        val out = ShortArray(outLen)
        var srcIdx = 0
        var dstIdx = 0
        while (dstIdx < outLen) {
            var sum = 0
            for (i in 0 until ratio) {
                if (srcIdx + i < monoLen) sum += mono[srcIdx + i].toInt()
            }
            out[dstIdx++] = (sum / ratio).toShort()
            srcIdx += ratio
        }
        return out
    }

    private fun linearResample(src: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (src.isEmpty()) return src
        val outLen = (src.size.toLong() * toRate / fromRate).toInt()
        if (outLen <= 0) return ShortArray(0)
        val out = ShortArray(outLen)
        val step = src.size.toDouble() / outLen
        var pos = 0.0
        var i = 0
        while (i < outLen) {
            val idx = pos.toInt().coerceAtMost(src.size - 1)
            val frac = (pos - idx)
            val next = (idx + 1).coerceAtMost(src.size - 1)
            out[i] = (src[idx] * (1.0 - frac) + src[next] * frac).toInt().toShort()
            pos += step
            i++
        }
        return out
    }

    private companion object {
        const val TAG = "VoskTranscriber"

        /** Vosk small models are trained at 16 kHz. */
        const val VOSK_SAMPLE_RATE_HZ = 16_000

        /** Min wall-clock gap between two partial emissions for one utterance. */
        const val PARTIAL_MIN_INTERVAL_MS = 200L
    }
}
