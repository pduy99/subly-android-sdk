package com.helios.subly.asr.whisper

/**
 * Test seam over [WhisperNative]. Lets unit tests drive the chunker ->
 * packet path without invoking JNI.
 */
interface WhisperBackend {
    fun isAvailable(): Boolean
    fun init(modelPath: String, targetLanguageCode: String): Long
    fun transcribe(handle: Long, pcm: FloatArray, sampleRateHz: Int): String

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
            override fun init(modelPath: String, targetLanguageCode: String) =
                WhisperNative.nativeInit(modelPath, targetLanguageCode)
            override fun transcribe(handle: Long, pcm: FloatArray, sampleRateHz: Int) =
                WhisperNative.nativeTranscribe(handle, pcm, sampleRateHz)
            override fun lastDetectedLang(handle: Long) =
                WhisperNative.nativeLastDetectedLang(handle)
            override fun release(handle: Long) = WhisperNative.nativeRelease(handle)
        }
    }
}
