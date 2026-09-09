package com.helios.subly.asr.vad

/**
 * The single Silero VAD checkpoint the SDK provisions.
 *
 * Sourced from the sherpa-onnx release assets rather than upstream
 * silero-vad: this is the export the sherpa runtime is built and tested
 * against, and it is the one whose input contract the runtime assumes
 * ([WINDOW_SIZE] samples at [SAMPLE_RATE_HZ]). The upstream v5 export is a
 * different graph and is not interchangeable here.
 *
 * At ~0.6 MB it is small enough to download on first prepare without a
 * progress UI mattering. Repoint [DOWNLOAD_URL] at your own mirror for
 * production — this object is the single source of truth.
 */
internal object SileroVadModel {

    const val FILE_NAME: String = "silero_vad.onnx"

    const val DOWNLOAD_URL: String =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

    /** Measured content-length; used only for progress when the server omits one. */
    const val APPROX_SIZE_BYTES: Long = 643_854L

    /**
     * Samples per inference window. **Not a tuning knob**: the exported graph
     * declares this dimension, and a differently-sized call fails — and
     * poisons the recogniser's LSTM state until `reset()` (measured on
     * device: a 256-sample call left every later call returning
     * "NULL input supplied for input h").
     */
    const val WINDOW_SIZE: Int = 512

    /** Rate the checkpoint was exported for. */
    const val SAMPLE_RATE_HZ: Int = 16_000

    /** One window of audio, in milliseconds — the gate's decision period. */
    const val WINDOW_MS: Int = WINDOW_SIZE * 1_000 / SAMPLE_RATE_HZ
}
