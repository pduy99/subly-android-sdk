package com.helios.subly.asr.whisper

/**
 * Test seam over [WhisperNative]. Lets unit tests drive the chunker ->
 * packet path without invoking JNI.
 */
interface WhisperBackend {
    fun isAvailable(): Boolean

    /**
     * @param vadModelPath ggml Silero VAD model path; empty disables the
     *   pre-transcribe speech gate.
     */
    fun init(modelPath: String, targetLanguageCode: String, vadModelPath: String): Long

    /**
     * @param gateWithVad run the Silero VAD gate before decoding. Pass `true`
     *   for final windows, `false` for partials (skips the full-window scan).
     */
    fun transcribe(handle: Long, pcm: FloatArray, sampleRateHz: Int, gateWithVad: Boolean): String

    /**
     * BCP-47 language detected by whisper on the most recent [transcribe]
     * call. Empty string if unknown. Caller must hold the same monitor as
     * [transcribe] / [release].
     */
    fun lastDetectedLang(handle: Long): String
    fun release(handle: Long)

    companion object {
        val Jni: WhisperBackend = object : WhisperBackend {
            override fun isAvailable() = WhisperNative.isAvailable()
            override fun init(modelPath: String, targetLanguageCode: String, vadModelPath: String) =
                WhisperNative.nativeInit(modelPath, targetLanguageCode, vadModelPath)
            override fun transcribe(handle: Long, pcm: FloatArray, sampleRateHz: Int, gateWithVad: Boolean) =
                WhisperNative.nativeTranscribe(handle, pcm, sampleRateHz, gateWithVad)
            override fun lastDetectedLang(handle: Long) =
                WhisperNative.nativeLastDetectedLang(handle)
            override fun release(handle: Long) = WhisperNative.nativeRelease(handle)
        }
    }
}
