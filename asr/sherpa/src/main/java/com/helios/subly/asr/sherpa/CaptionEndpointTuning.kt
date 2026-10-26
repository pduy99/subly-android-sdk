package com.helios.subly.asr.sherpa

/**
 * Endpoint rules that decide where one caption ends and the next begins.
 *
 * Held as plain data rather than built inline as `com.k2fsa.sherpa.onnx`
 * types so the invariant below can be unit-tested — the library's config
 * classes sit behind the native JNI, which JVM tests cannot load.
 *
 * **The invariant: every rule must require some trailing silence.** A rule
 * that can fire at zero silence ends the caption wherever the audio happens
 * to be, and in continuous speech that lands mid-word. This is not
 * hypothetical — a previous `rule3` of "10 s elapsed, silence irrelevant"
 * produced exactly that in the accuracy benchmark, splitting "refractory"
 * into "refractor" + "y ward" and "confinement" into "con" + "finement".
 * The damage is not only cosmetic: each fragment is then translated
 * separately, so "refractory ward" reached the user as "khúc xạ y phường"
 * ("refraction Y ward"). A mid-word cut corrupts the translation too.
 */
internal object CaptionEndpointTuning {

    /**
     * @property mustContainNonSilence require actual speech before firing.
     * @property minTrailingSilenceSec pause required to fire. Must be > 0.
     * @property minUtteranceLengthSec how long the utterance must have run.
     */
    data class Rule(
        val mustContainNonSilence: Boolean,
        val minTrailingSilenceSec: Float,
        val minUtteranceLengthSec: Float,
    )

    /** Long pure-silence guard, for when no speech has arrived yet. */
    val SILENCE_GUARD = Rule(
        mustContainNonSilence = false,
        minTrailingSilenceSec = 2.0f,
        minUtteranceLengthSec = 0f,
    )

    /** The main driver: a normal speech pause ends the caption. */
    val SPEECH_PAUSE = Rule(
        mustContainNonSilence = true,
        minTrailingSilenceSec = 0.8f,
        minUtteranceLengthSec = 0f,
    )

    /**
     * Keeps a run-on from growing without bound. Unlike a hard timeout this
     * still waits for a pause — just a much shorter one, so after 10 s the
     * caption breaks at the next gap between words instead of inside one.
     * A speaker who never pauses at all keeps a growing caption, which is
     * the right trade: a long caption is readable, a severed word is not.
     */
    val RUN_ON_CAP = Rule(
        mustContainNonSilence = true,
        minTrailingSilenceSec = 0.15f,
        minUtteranceLengthSec = 10f,
    )

    val ALL: List<Rule> = listOf(SILENCE_GUARD, SPEECH_PAUSE, RUN_ON_CAP)
}
