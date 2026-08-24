package com.helios.subly.asr.sherpa

import android.util.Log
import java.io.File
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineCtcFstDecoderConfig
import com.k2fsa.sherpa.onnx.OnlineLMConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

private fun CaptionEndpointTuning.Rule.toEndpointRule(): EndpointRule = EndpointRule(
    mustContainNonSilence = mustContainNonSilence,
    minTrailingSilence = minTrailingSilenceSec,
    minUtteranceLength = minUtteranceLengthSec,
)

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
 * pointing at on-disk encoder/decoder/joiner/tokens and enable endpointing
 * with the caption tuning in [CaptionEndpointTuning].
 *
 * Decoding is `greedy_search`. `modified_beam_search` would likely lower
 * WER, but it has not been measured on the accuracy benchmark — treat the
 * switch as an open experiment, not a known win.
 *
 * Availability probe lazily loads the JNI; on `UnsatisfiedLinkError`
 * (e.g. wrong ABI), [isAvailable] returns `false` permanently and the
 * transcriber drains.
 */
private object JniSherpaOnnxBackend : SherpaOnnxBackend {

    private const val TAG = "SherpaOnnxBackend"

    /**
     * Picks the first file in [dir] whose name matches [prefix]*.onnx,
     * preferring an int8 build when both are present. Returns `null` if none
     * match — lets [init] fail loudly with a clear message instead of handing
     * sherpa-onnx a non-existent path.
     */
    private fun resolveOnnx(dir: File, prefix: String): String? {
        val candidates = dir.listFiles { f ->
            f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".onnx")
        }?.toList().orEmpty()
        if (candidates.isEmpty()) return null
        return (candidates.firstOrNull { it.name.contains(".int8.") } ?: candidates.first())
            .absolutePath
    }

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
        val dir = File(modelDir)
        val encoder = resolveOnnx(dir, "encoder")
        val decoder = resolveOnnx(dir, "decoder")
        val joiner = resolveOnnx(dir, "joiner")
        if (encoder == null || decoder == null || joiner == null) {
            Log.w(
                TAG,
                "sherpa-onnx init: missing transducer files in $modelDir " +
                        "(encoder=${encoder != null} decoder=${decoder != null} joiner=${joiner != null})"
            )
            return null
        }
        return runCatching {
            val config = OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder,
                        decoder = decoder,
                        joiner = joiner,
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = 1,
                    debug = false,
                ),
                lmConfig = OnlineLMConfig(),
                featConfig = FeatureConfig(
                    sampleRate = 16000,
                    featureDim = 80,
                ),
                ctcFstDecoderConfig = OnlineCtcFstDecoderConfig(),
                // Where one caption ends and the next begins. See
                // CaptionEndpointTuning — in particular why every rule waits
                // for a pause instead of cutting on elapsed time alone.
                endpointConfig = EndpointConfig(
                    rule1 = CaptionEndpointTuning.SILENCE_GUARD.toEndpointRule(),
                    rule2 = CaptionEndpointTuning.SPEECH_PAUSE.toEndpointRule(),
                    rule3 = CaptionEndpointTuning.RUN_ON_CAP.toEndpointRule(),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
            )

            val recognizer = OnlineRecognizer(assetManager = null, config = config)
            val stream = recognizer.createStream()
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
}
