package com.helios.subly.asr.whisper

import android.content.Context
import android.util.Log
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.core.model.AccelerationAware
import com.helios.subly.core.model.AccelerationReport
import com.helios.subly.core.model.BackendAttempt
import com.helios.subly.core.model.DelegateCrashGuard
import com.helios.subly.core.model.DelegateType
import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.produceIn
import java.io.File
import com.helios.subly.core.downloader.ModelDownloader
import com.helios.subly.core.downloader.OkHttpModelDownloader
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class WhisperTranscriber(
    private val context: Context,
    private val backend: WhisperBackend = WhisperBackend.Jni,
    private val speechSegmenter: SpeechSegmenter = SpeechSegmenter(),
    private val modelDownloader: ModelDownloader = OkHttpModelDownloader(),
    /**
     * ggml model file name on the `ggerganov/whisper.cpp` HF repo. Null (the
     * default) picks by delegate: [GPU_MODEL_ASSET] when an OpenCL GPU is
     * available, [DEFAULT_MODEL_ASSET] otherwise. Pass `ggml-base-q8_0.bin`
     * or `ggml-tiny-q8_0.bin` for devices that need a smaller download or
     * faster-than-real-time transcription.
     */
    private val modelAsset: String? = null,
    /**
     * ggml Silero VAD model file name on the `ggml-org/whisper-vad` HF repo.
     * Downloaded alongside the main model and used as a neural speech gate to
     * reject background music / noise before transcription (see the
     * non-speech rejection layers in `whisper-jni.cpp`). Set to an empty
     * string to disable the gate and rely only on the post-decode guards.
     */
    private val vadModelAsset: String = DEFAULT_VAD_ASSET,
    /**
     * Optional decoding-prompt bias passed to whisper as `initial_prompt`:
     * domain hotwords, product names, and proper nouns the model otherwise
     * mis-recognizes (e.g. "Fortnite, USB, gigabit"). whisper biases decoding
     * toward this vocabulary. Keep it short — long prompts can make the model
     * "continue" them. Empty disables biasing.
     */
    private val glossary: String = "",
    /**
     * Try the GPU (ggml's OpenCL backend) before the CPU. Without a usable
     * OpenCL device, or after the GPU path crashed the process once, the CPU
     * is used and [accelerationReport] says why.
     */
    private val preferGpu: Boolean = true,
) : SublyAsr, AccelerationAware {

    /** Which delegate whisper.cpp ended up on, and every attempt on the way. */
    @Volatile
    override var accelerationReport: AccelerationReport? = null
        private set


    private val handleRef = AtomicLong(0L)
    private val targetLangRef = AtomicReference("")
    private val nativeLock = Any()

    // -------------------------------------------------------------------------
    // Prepare
    // -------------------------------------------------------------------------

    override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = flow {
        emit(ModelPrepState.Checking)

        if (!backend.isAvailable()) {
            val error = IllegalStateException("Whisper JNI unavailable; prepare is a no-op.")
            Log.w(TAG, error.message!!)
            emit(ModelPrepState.Error(error))
            return@flow
        }

        val language = config.source.normalizeLangTag()
        targetLangRef.set(language)

        // Already loaded: whisper is one multilingual model, so a new source
        // language is a parameter change on the live context, not a reload.
        val live = handleRef.get()
        if (live != 0L) {
            synchronized(nativeLock) { backend.setLanguage(live, language) }
            emit(ModelPrepState.Ready)
            return@flow
        }

        // Delegate decision comes first because it picks the model: the
        // OpenCL backend has Adreno-tuned kernels for q8_0 matmuls but none
        // for q5_1, so a q5_1 model on the GPU would run its matmuls on the
        // CPU anyway.
        val gpuDevice = if (preferGpu) runCatching { backend.gpuDevice() }.getOrDefault("") else ""
        val gpuGuard = DelegateCrashGuard(context, File(context.filesDir, GPU_CRASH_MARKER))
        val gpuSkipReason = if (gpuDevice.isNotEmpty()) gpuGuard.skipReason() else null
        val tryGpu = gpuDevice.isNotEmpty() && gpuSkipReason == null
        val asset = modelAsset ?: if (tryGpu) GPU_MODEL_ASSET else DEFAULT_MODEL_ASSET

        val modelPath = runCatching {
            // Speech model download/extraction phase: 0.0 -> 0.85 (progress is
            // normalized to 0..1 per the ModelPrepState contract — previously
            // this emitted 0..90 and broke any progress bar built on it).
            ensureFileDownloaded(asset, modelUrl(asset)) { progress ->
                emit(ModelPrepState.Preparing(progress * 0.85f))
            }
        }.getOrElse {
            Log.e(TAG, "Failed to obtain whisper model", it)
            emit(ModelPrepState.Error(it))
            return@flow
        }

        // VAD model download phase: 0.85 -> 0.9. The VAD model is small and
        // optional — a failed download must NOT fail prepare, it just disables
        // the speech gate (the post-decode guards still run).
        val vadPath = if (vadModelAsset.isEmpty()) {
            ""
        } else {
            runCatching {
                ensureFileDownloaded(vadModelAsset, vadModelUrl(vadModelAsset)) { progress ->
                    emit(ModelPrepState.Preparing(0.85f + progress * 0.05f))
                }
            }.getOrElse {
                Log.w(TAG, "VAD model unavailable; speech gate disabled.", it)
                ""
            }
        }

        // Native init phase: 0.9 -> 1.0. Init includes a warm-up inference,
        // so a GPU that initialises but cannot run whisper's graph fails here.
        emit(ModelPrepState.Preparing(0.9f))
        val attempts = ArrayList<BackendAttempt>()
        var created = 0L
        if (tryGpu) {
            gpuGuard.arm(gpuDevice)
            val start = System.nanoTime()
            created = backend.init(modelPath, targetLangRef.get(), vadPath, glossary.trim(), useGpu = true)
            gpuGuard.disarm()
            attempts += BackendAttempt(
                DelegateType.GPU,
                succeeded = created != 0L,
                initMs = (System.nanoTime() - start) / 1_000_000,
                failure = if (created == 0L) "whisper_init on $gpuDevice returned null" else null,
            )
        } else if (preferGpu) {
            attempts += BackendAttempt(
                DelegateType.GPU,
                succeeded = false,
                initMs = 0,
                failure = if (gpuSkipReason != null) {
                    gpuSkipReason
                } else {
                    "no OpenCL GPU device (libOpenCL.so missing or not exposed to apps)"
                },
            )
        }
        if (created == 0L) {
            val start = System.nanoTime()
            created = backend.init(modelPath, targetLangRef.get(), vadPath, glossary.trim(), useGpu = false)
            attempts += BackendAttempt(
                DelegateType.CPU,
                succeeded = created != 0L,
                initMs = (System.nanoTime() - start) / 1_000_000,
                failure = if (created == 0L) "whisper_init returned null" else null,
            )
        }
        val active = attempts.lastOrNull { it.succeeded }?.delegate ?: DelegateType.CPU
        BenchLog.metric(
            "model_init model=$asset delegate=$active " +
                attempts.joinToString(" ") { "${it.delegate}=${if (it.succeeded) "ok" else "fail"}:${it.initMs}ms" }
        )
        Log.i(TAG, "whisper on $active ($asset); gpu device: '${gpuDevice.ifEmpty { "none" }}'")
        accelerationReport = AccelerationReport(
            component = "asr:whisper:$asset",
            active = active,
            attempts = attempts,
        )

        if (created == 0L) {
            Log.e(TAG, "whisper_init returned null for $modelPath")
            val error = IllegalStateException("whisper_init returned null for $modelPath")
            emit(ModelPrepState.Error(error))
            return@flow
        }

        if (!handleRef.compareAndSet(0L, created)) {
            runCatching { backend.release(created) }
        }

        emit(ModelPrepState.Ready)
    }.flowOn(Dispatchers.IO)

    // -------------------------------------------------------------------------
    // Transcribe
    // -------------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult> {
        // Per the SublyAsr contract: fail loudly on collection if unprepared.
        if (!backend.isAvailable() || handleRef.get() == 0L) {
            return flow {
                throw IllegalStateException(
                    "Whisper engine not prepared. Collect prepareModel() to Ready before transcribe()."
                )
            }
        }

        return channelFlow {
            val windows = speechSegmenter.chunk(frames)
                .buffer(capacity = Channel.UNLIMITED)
                .produceIn(this)

            for (received in windows) {
                // Real-time policy: a partial window is a disposable
                // hypothesis. If inference fell behind and newer windows are
                // queued, skip ahead to the freshest one — transcribing stale
                // audio only grows the on-screen lag. Final windows are never
                // skipped (dropping one would lose caption text for good); a
                // final also supersedes any stale partial of its own segment.
                var window = received
                var skipped = 0
                while (!window.isFinal) {
                    val next = windows.tryReceive().getOrNull() ?: break
                    window = next
                    skipped++
                }

                checkSampleRate(window.sampleRateHz)
                val pcm = window.pcm
                val kind = if (window.isFinal) "final" else "partial"
                val audioMs = pcm.size.toLong() * 1000 / WHISPER_SAMPLE_RATE
                // Time the window sat queued behind earlier inferences. If
                // this grows, inference isn't keeping up with speech (check
                // skipped= too — that's how many stale partials were dropped).
                val queueMs = (System.nanoTime() - window.createdAtNanos) / 1_000_000

                val inferStartNanos = System.nanoTime()
                val rawText = runCatching {
                    synchronized(nativeLock) {
                        val h = handleRef.get()
                        // Gate non-speech with the VAD only on finals. Partials
                        // grow every ~800 ms; re-scanning the whole window each
                        // time costs up to ~1 s and the VAD always passes during
                        // real speech anyway. Final windows still gate, and the
                        // post-decode no-speech/log-prob guards cover partials.
                        if (h == 0L) "" else backend.transcribe(
                            h, pcm, WHISPER_SAMPLE_RATE, /* gateWithVad = */ window.isFinal,
                        ).trim()
                    }
                }.getOrElse { e ->
                    Log.w(TAG, "Whisper native transcribe failed", e)
                    ""
                }
                val inferMs = (System.nanoTime() - inferStartNanos) / 1_000_000
                // Collapse repetition-loop hallucinations; the first
                // occurrence of the looped phrase is the real transcription.
                var text = RepetitionFilter.collapse(rawText)

                // Whisper punctuates every window as a complete sentence. When
                // the segmenter cut this one at the length cap the speaker was
                // still talking, so that full stop is an artefact of the cut —
                // and downstream it does real damage: sentence assembly treats
                // it as a boundary and capitalises the continuation, producing
                // captions like "and then with clean wet." / "hands squeeze
                // them into a ball." Drop it and let the real sentence end,
                // when it arrives, be the one that splits the caption.
                if (window.continuesUtterance && text.isNotEmpty()) {
                    text = text.trimEnd().trimEnd(*MID_UTTERANCE_TRIM).trimEnd()
                }
                // The strip alone is not enough: PunctuationRestorer adds a
                // terminator back to any final that lacks one, so the fact has
                // to travel with the result rather than only shaping its text.
                val endsSentence = !window.continuesUtterance

                val lang = if (window.isFinal && text.isNotEmpty()) {
                    synchronized(nativeLock) {
                        val h = handleRef.get()
                        if (h != 0L) backend.lastDetectedLang(h) else ""
                    }
                } else ""

                // e2e = end of captured audio -> result ready. This is the
                // latency the user perceives for this window (rendering aside).
                BenchLog.metric(
                    "asr_window kind=$kind audio_ms=$audioMs queue_ms=$queueMs " +
                            "infer_ms=$inferMs e2e_ms=${queueMs + inferMs} " +
                            "rtf=${"%.3f".format(java.util.Locale.US, inferMs.toFloat() / audioMs.coerceAtLeast(1))} " +
                            "skipped=$skipped text_len=${text.length} " +
                            "raw_len=${rawText.length} lang=$lang " +
                            "start_ts=${window.startTimestampMs}"
                )
                // Accuracy benchmarking only; no-op unless VERBOSE was
                // explicitly enabled (see BenchLog docs). Empty results are
                // logged too — silent deletions matter for WER.
                BenchLog.transcript(
                    "transcript kind=$kind start_ts=${window.startTimestampMs} " +
                            "audio_ms=$audioMs text=\"$text\""
                )

                if (text.isNotEmpty()) {
                    if (window.isFinal) {
                        // NOTE: never log transcript content — it is end-user speech.
                        Log.d(TAG, "Whisper emitted final (length=${text.length}, lang='$lang')")
                        send(AsrResult.Final(text, endsSentence = endsSentence))
                    } else {
                        send(AsrResult.Partial(text))
                    }
                }
            }
        }.flowOn(Dispatchers.Default)
    }

    override fun release() {
        synchronized(nativeLock) {
            val h = handleRef.getAndSet(0L)
            if (h != 0L) runCatching { backend.release(h) }
        }
    }

    /**
     * Languages offered for transcription, as ISO 639-1 codes.
     *
     * The multilingual whisper model technically transcribes 100 languages
     * (whisper.cpp's `g_lang` table), but accuracy tracks training-data
     * volume and the base model is only dependable for the high-resource
     * tier. This list is deliberately curated to languages that are
     * (a) high-accuracy on the base model — whisper language ids 0-19,
     *     which are ordered by training-data volume, plus uk/th/ms from the
     *     next tier;
     * (b) widely spoken; and
     * (c) supported by ML Kit translation, so any pickable source language
     *     is guaranteed translatable downstream.
     *
     * Ordered by whisper id (≈ descending transcription quality). To offer
     * more languages, prefer shipping a larger model (small/medium) rather
     * than just extending this list — mid/low-tier accuracy on base is
     * poor enough to be misleading to users.
     */
    override fun supportedLanguages(): List<String> = listOf(
        "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr",
        "pl", "nl", "ar", "sv", "it", "id", "hi", "fi", "vi",
        "uk", "th", "ms",
    )

    // -------------------------------------------------------------------------
    // Chunking helpers
    // -------------------------------------------------------------------------

    private fun checkSampleRate(actual: Int) {
        if (actual != WHISPER_SAMPLE_RATE) {
            Log.e(
                TAG, "SAMPLE RATE MISMATCH: audio is ${actual} Hz but Whisper requires " +
                        "$WHISPER_SAMPLE_RATE Hz. Set AudioRecord.SAMPLE_RATE = 16000 or " +
                        "add a resampler. Transcription will be empty/garbled."
            )
        }
    }

    // -------------------------------------------------------------------------
    // Model extraction
    // -------------------------------------------------------------------------

    private suspend fun ensureFileDownloaded(
        assetName: String,
        url: String,
        onProgress: suspend (Float) -> Unit,
    ): String {
        val outDir = File(context.filesDir, "whisper").apply { mkdirs() }
        val outFile = File(outDir, assetName)

        if (outFile.exists() && outFile.length() > 0L) {
            onProgress(1f)
            return outFile.absolutePath
        }

        modelDownloader.downloadModel(url, outFile).collect { progress ->
            onProgress(progress)
        }

        return outFile.absolutePath
    }

    private fun modelUrl(asset: String) =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$asset?download=true"

    private fun vadModelUrl(asset: String) =
        "https://huggingface.co/ggml-org/whisper-vad/resolve/main/$asset?download=true"

    private fun String.normalizeLangTag(): String =
        substringBefore('-').lowercase().takeIf { it.isNotBlank() && it != "auto" } ?: ""

    companion object {
        private const val TAG = "WhisperTranscriber"
        /**
         * The q5_1-quantized `small` model (~190 MB).
         *
         * Chosen over `base-q8_0` (78 MB) for accuracy, most visibly on
         * Japanese, where `small` measured 20 -> 35 against base. The costs
         * are real and deliberate: a ~2.4x larger first-run download, and
         * ~3.2x real-time transcription on a Galaxy S24 versus ~2.3x for
         * base. Devices that cannot afford either should pass
         * `ggml-tiny-q8_0.bin` or `ggml-base-q8_0.bin` explicitly.
         */
        const val DEFAULT_MODEL_ASSET = "ggml-small-q5_1.bin"

        /**
         * The same `small` model at q8_0 for the GPU path: ggml's OpenCL
         * backend has Adreno-optimised q8_0 matmul kernels and none for q5_1.
         * ~264 MB, larger than q5_1, and slightly more accurate.
         */
        const val GPU_MODEL_ASSET = "ggml-small-q8_0.bin"

        /** Survives a native crash during GPU init; see prepareModel. */
        private const val GPU_CRASH_MARKER = "whisper/init-guard/GPU.init"
        /** ggml Silero VAD model (~885 KB) on the `ggml-org/whisper-vad` repo. */
        const val DEFAULT_VAD_ASSET = "ggml-silero-v5.1.2.bin"
        private const val WHISPER_SAMPLE_RATE = 16_000

        /**
         * Sentence-final marks stripped from a window that was cut mid-
         * utterance. Commas and other non-terminal marks are left alone —
         * they do not end a sentence downstream, so they do no harm.
         */
        private val MID_UTTERANCE_TRIM =
            charArrayOf('.', '!', '?', '…', '。', '！', '？', '．')
    }
}