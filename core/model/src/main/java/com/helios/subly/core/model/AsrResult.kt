package com.helios.subly.core.model

sealed class AsrResult(open val text: String) {
    data class Partial(override val text: String) : AsrResult(text)

    /**
     * A committed recognition result.
     *
     * @property endsSentence whether the speaker actually stopped here.
     *   Streaming engines finalise on a trailing pause, so their finals do end
     *   a sentence and this stays true. Whisper decodes fixed-length windows
     *   and punctuates each one as if it were complete — when a window is cut
     *   at the length cap mid-utterance it must report false, or sentence
     *   assembly splits the caption there and capitalises the continuation
     *   ("and then with clean wet." / "Hands squeeze them into a ball.").
     */
    data class Final(
        override val text: String,
        val endsSentence: Boolean = true,
    ) : AsrResult(text)
}
