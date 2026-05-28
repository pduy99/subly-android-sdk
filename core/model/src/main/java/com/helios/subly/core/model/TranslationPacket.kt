package com.helios.subly.core.model

/**
 * A single chunk of translated text emitted by the engine.
 *
 * @property text Translated text in the target language.
 * @property sourceLanguageCode Best-guess BCP-47 code of the detected source (engine auto-detects).
 * @property targetLanguageCode BCP-47 code of the requested translation target.
 * @property timestampMs Monotonic wall-clock time the packet was produced.
 * @property source Whether this packet was produced by the audio or vision pipeline.
 * @property isFinal True when the underlying segment is committed (vs. an interim hypothesis).
 */
data class TranslationPacket(
    val text: String,
    val sourceLanguageCode: String?,
    val targetLanguageCode: String?,
    val timestampMs: Long,
    val source: Source,
    val isFinal: Boolean,
) {
    enum class Source { AUDIO, VISION }
}
