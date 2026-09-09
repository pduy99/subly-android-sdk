package com.helios.subly.asr.vad

import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Test seam over the sherpa-onnx `Vad` bindings.
 *
 * Lets [SpeechGate]'s decision logic be unit-tested without loading
 * `libsherpa-onnx-jni.so`, which a JVM test cannot do.
 */
internal interface VadBackend {

    /**
     * True when the sherpa-onnx native library is loadable in this process.
     *
     * `false` is a normal, supported outcome — an app that depends on
     * `:asr:vosk` alone ships no sherpa AAR, so the classes referenced here
     * are simply absent. [SpeechGate] then runs as a pass-through rather
     * than failing preparation.
     */
    fun isAvailable(): Boolean

    /**
     * Opens a VAD session against [modelPath].
     *
     * Throws on a bad or missing checkpoint; [SpeechGate] catches that and
     * degrades to pass-through, so this stays a plain constructor rather
     * than a nullable result.
     */
    fun open(modelPath: String): Session

    /** One recogniser instance. Not safe for concurrent use. */
    interface Session {

        /**
         * Speech probability for exactly [SileroVadModel.WINDOW_SIZE]
         * samples of mono float PCM in `[-1, 1]`.
         *
         * @return `0f..1f`, or [PROBABILITY_UNKNOWN] when the model could
         *   not be evaluated. Callers must treat unknown as "assume speech":
         *   a broken VAD must never silence audio.
         */
        fun probability(window: FloatArray): Float

        /** Clears the LSTM state between utterances. */
        fun reset()

        /** Releases native resources. Idempotent. */
        fun release()
    }

    companion object {
        /** Returned by [Session.probability] when no decision could be made. */
        const val PROBABILITY_UNKNOWN: Float = -1f

        /** Default implementation, backed by the sherpa-onnx runtime. */
        val Sherpa: VadBackend = SherpaVadBackend
    }
}

/**
 * [VadBackend] over `com.k2fsa.sherpa.onnx.Vad`, which reaches the same
 * Silero graph the whisper engine gates with — except from Kotlin, so the
 * streaming engines can use it without a second native build.
 *
 * Only [Vad.compute] is used, never `acceptWaveform`/`isSpeechDetected`.
 * That pair applies sherpa's own min-speech/min-silence smoothing, which
 * delays speech onset by its `minSpeechDuration`; `compute` hands back the
 * raw per-window probability so [SpeechGate] can open on the very first
 * window that crosses the threshold. Measured on a Galaxy S10 against a
 * 25 s FLEURS clip: 782 windows in 260 ms (~1% of real time), digital
 * silence peaked at 0.043, speech reached 0.9998, and onset went
 * 0.04 -> 0.15 -> 0.93 across three consecutive windows.
 */
private object SherpaVadBackend : VadBackend {

    private const val TAG = "SherpaVadBackend"

    /**
     * One-shot probe. Touching a sherpa-onnx class runs the static
     * initializer that `System.loadLibrary`s the .so, so this catches both
     * the missing-AAR case ([NoClassDefFoundError]) and the wrong-ABI case
     * ([UnsatisfiedLinkError]) — neither of which is an [Exception], hence
     * the [Throwable] catch.
     */
    private val available: Boolean by lazy {
        try {
            SileroVadModelConfig::class.java.name
            true
        } catch (error: Throwable) {
            Log.w(TAG, "sherpa-onnx VAD unavailable: ${error.message}")
            false
        }
    }

    override fun isAvailable(): Boolean = available

    override fun open(modelPath: String): VadBackend.Session {
        val vad = Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelPath,
                    // These four only drive sherpa's own segmenter, which
                    // `compute` does not use. They are set to the library
                    // defaults so the config is valid, not because the gate
                    // depends on them — SpeechGate.threshold is the knob.
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = SileroVadModel.WINDOW_SIZE,
                    maxSpeechDuration = 20f,
                ),
                // Required by the config's shape; an empty model path leaves
                // the alternative TEN VAD unused.
                tenVadModelConfig = TenVadModelConfig(model = ""),
                sampleRate = SileroVadModel.SAMPLE_RATE_HZ,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
        )
        return SherpaSession(vad)
    }

    private class SherpaSession(private val vad: Vad) : VadBackend.Session {

        @Volatile
        private var released = false

        override fun probability(window: FloatArray): Float {
            if (released || window.size != SileroVadModel.WINDOW_SIZE) {
                return VadBackend.PROBABILITY_UNKNOWN
            }
            return try {
                vad.compute(window)
            } catch (error: Throwable) {
                // A failed compute leaves the LSTM state half-written, and
                // every later call then fails too. Reset so one bad window
                // costs one window, not the rest of the session.
                Log.w(TAG, "VAD compute failed; resetting state: ${error.message}")
                runCatching { vad.reset() }
                VadBackend.PROBABILITY_UNKNOWN
            }
        }

        override fun reset() {
            if (!released) runCatching { vad.reset() }
        }

        override fun release() {
            if (released) return
            released = true
            runCatching { vad.release() }
        }
    }
}
