package com.helios.subly.sdk.data.ai

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Test seam over the sherpa-onnx (`com.k2fsa.sherpa.onnx.*`) Kotlin API.
 *
 * Lets unit tests drive [SherpaOnnxTranscriber] without needing the
 * `libsherpa-onnx-jni.so` loaded (which would require an instrumentation
 * test, since JVM unit tests can't load Android JNIs).
 */
internal interface SherpaOnnxBackend {

    /**
     * True when the sherpa-onnx native library is loadable on this device.
     * `false` typically means the AAR's `libsherpa-onnx-jni.so` is missing
     * for the current ABI (e.g. running on x86 emulator while the SDK is
     * arm64-v8a-only).
     */
    fun isAvailable(): Boolean

    /** Returns an opaque recognizer+stream handle, or `null` on failure. */
    fun init(modelDir: String, sampleRateHz: Int): Handle?

    /** Push PCM (mono, float in [-1, 1]) into the recognizer's stream. */
    fun acceptWaveform(handle: Handle, pcm: FloatArray, sampleRateHz: Int)

    /** Drain decoder until the stream is no longer "ready" and return the current hypothesis. */
    fun decode(handle: Handle): DecodeResult

    /** Reset stream after a final commit so the next utterance starts clean. */
    fun reset(handle: Handle)

    /** Release native recognizer + stream resources. Idempotent. */
    fun release(handle: Handle)

    /**
     * Opaque holder for the sherpa-onnx recognizer + stream pair. Kept
     * package-private so callers can't accidentally bypass the backend
     * abstraction.
     */
    class Handle internal constructor(
        internal val recognizer: OnlineRecognizer,
        internal val stream: OnlineStream,
    )

    /**
     * One decode tick output.
     *
     * @property text Cumulative hypothesis text for the active utterance
     *   (may include earlier partials).
     * @property isEndpoint True when the recognizer reports an endpoint
     *   (silence / VAD-style boundary). Caller should emit a final packet
     *   and then [reset] the stream.
     */
    data class DecodeResult(val text: String, val isEndpoint: Boolean)

    companion object {
        /** Default real-recognizer-backed implementation. */
        val Jni: SherpaOnnxBackend = JniSherpaOnnxBackend
    }
}

/**
 * Real [SherpaOnnxBackend] backed by the sherpa-onnx Kotlin bindings shipped
 * in the AAR under `sdk/libs/`.
 *
 * Streaming-Zipformer-transducer wiring: we build the recognizer config
 * pointing at on-disk encoder/decoder/joiner/tokens, enable endpointing
 * with the tuning we want for "captions" (rule2 = 1.2 s trailing silence
 * after speech, rule3 = 20 s hard cap), and use `modified_beam_search`
 * which gives noticeably better WER than greedy for streaming Zipformer.
 *
 * Availability probe lazily loads the JNI; on `UnsatisfiedLinkError`
 * (e.g. wrong ABI), [isAvailable] returns `false` permanently and the
 * transcriber drains.
 */
private object JniSherpaOnnxBackend : SherpaOnnxBackend {

    private const val TAG = "SublySherpaOnnxBackend"

    /**
     * Touching any sherpa-onnx class triggers the static initializer that
     * `System.loadLibrary("sherpa-onnx-jni")`s the .so. We do a one-shot
     * probe so the Kotlin layer degrades exactly once at process start
     * rather than throwing on every call site.
     */
    private val available: Boolean by lazy {
        runCatching {
            // Touch a trivial sherpa-onnx class to force JNI load.
            OnlineTransducerModelConfig::class.java.name
            true
        }.getOrElse { error ->
            Log.w(TAG, "sherpa-onnx JNI unavailable: ${error.message}")
            false
        }
    }

    override fun isAvailable(): Boolean = available

    override fun init(modelDir: String, sampleRateHz: Int): SherpaOnnxBackend.Handle? {
        if (!available) return null
        return runCatching {
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = sampleRateHz,
                    featureDim = 80,
                    dither = 0f,
                ),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/encoder.onnx",
                        decoder = "$modelDir/decoder.onnx",
                        joiner = "$modelDir/joiner.onnx",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = NUM_THREADS,
                    debug = false,
                    provider = "cpu",
                    modelType = "zipformer",
                ),
                endpointConfig = EndpointConfig(
                    // rule1: silence before any speech (we ignore — never triggers
                    // inside an utterance because mustContainNonSilence=false).
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    // rule2: trailing silence *after* speech — the workhorse.
                    // 1.2 s is the sweet spot for caption UX: long enough to
                    // ride through inter-word pauses, short enough that
                    // sentence-end finals feel snappy.
                    rule2 = EndpointRule(true, 1.2f, 0.0f),
                    // rule3: hard cap so a continuous monologue still flushes
                    // periodically (downstream NMT needs final commits to
                    // settle).
                    rule3 = EndpointRule(false, 0.0f, 20.0f),
                ),
                enableEndpoint = true,
                decodingMethod = "modified_beam_search",
                maxActivePaths = 4,
            )
            // AssetManager = null -> recognizer loads from filesystem paths
            // (which is what we want; assets-unpacking happens earlier in
            // SherpaOnnxModelLoader).
            val recognizer = OnlineRecognizer(assetManager = null, config = config)
            val stream = recognizer.createStream("")
            SherpaOnnxBackend.Handle(recognizer, stream)
        }.getOrElse { error ->
            Log.w(TAG, "sherpa-onnx init failed: ${error.message}")
            null
        }
    }

    override fun acceptWaveform(
        handle: SherpaOnnxBackend.Handle,
        pcm: FloatArray,
        sampleRateHz: Int,
    ) {
        runCatching { handle.stream.acceptWaveform(pcm, sampleRateHz) }
    }

    override fun decode(handle: SherpaOnnxBackend.Handle): SherpaOnnxBackend.DecodeResult {
        return runCatching {
            while (handle.recognizer.isReady(handle.stream)) {
                handle.recognizer.decode(handle.stream)
            }
            val text = handle.recognizer.getResult(handle.stream).text
            val endpoint = handle.recognizer.isEndpoint(handle.stream)
            SherpaOnnxBackend.DecodeResult(text, endpoint)
        }.getOrElse { EMPTY }
    }

    override fun reset(handle: SherpaOnnxBackend.Handle) {
        runCatching { handle.recognizer.reset(handle.stream) }
    }

    override fun release(handle: SherpaOnnxBackend.Handle) {
        // Order matters: free the stream first, then the recognizer that
        // owns the native context. Both calls swallow exceptions because
        // double-release is harmless and we don't want stop() to surface
        // shutdown noise.
        runCatching { handle.stream.release() }
        runCatching { handle.recognizer.release() }
    }

    private val EMPTY = SherpaOnnxBackend.DecodeResult("", false)

    /**
     * 2 threads is the conventional sweet spot for streaming Zipformer on
     * arm64-v8a phones — 1 underutilizes the big core, 4+ thrashes the
     * shared L2 and actually slows decode. Tune per-device only if profiling
     * shows real wins.
     */
    private const val NUM_THREADS = 2
}
