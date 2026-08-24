package com.helios.subly.sdk

import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.audio.api.AudioCapture
import com.helios.subly.audio.impl.AudioPlayback
import com.helios.subly.audio.impl.AudioRecordDataSource
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.translator.api.SublyTranslator

/**
 * Public entry point of the Subly SDK.
 *
 * `Subly` is a cheap, reusable *configuration* object. It holds **factories**,
 * not engine instances: every [createSession] call produces a session with its
 * own freshly-created engines, and that session fully owns their lifecycle
 * (releasing them in [SublySession.close]). Closing one session can never
 * poison another.
 *
 * ```kotlin
 * val subly = Subly.Builder()
 *     .setAsrEngine { SherpaOnnxTranscriber(context) }
 *     .setTranslator { MlKitTranslator() }
 *     .build()
 *
 * val session = subly.createSession(LanguageConfig(source = "en", target = "vi"))
 * session.prepare()                      // optional pre-warm; start() also triggers it
 * session.start(mediaProjection)
 * ```
 */
class Subly private constructor(
    private val asrEngineFactory: () -> SublyAsr,
    private val translatorFactory: () -> SublyTranslator,
    private val audioCaptureFactory: () -> AudioCapture,
    private val restorePunctuation: Boolean,
) {

    /**
     * Creates a session for the given language pair.
     *
     * Cheap to call — no I/O happens until [SublySession.prepare] or
     * [SublySession.start]. The returned session owns its engines; call
     * [SublySession.close] when done.
     */
    fun createSession(config: LanguageConfig): SublySession = SublySession(
        config = config,
        audioCapture = audioCaptureFactory(),
        asrEngine = asrEngineFactory(),
        translationEngine = translatorFactory(),
        restorePunctuation = restorePunctuation,
    )

    class Builder {
        private var asrEngineFactory: (() -> SublyAsr)? = null
        private var translatorFactory: (() -> SublyTranslator)? = null
        private var audioCaptureFactory: () -> AudioCapture = {
            AudioPlayback(dataSource = AudioRecordDataSource())
        }
        private var restorePunctuation: Boolean = true

        /** Required. Factory invoked once per session. */
        fun setAsrEngine(factory: () -> SublyAsr) = apply { asrEngineFactory = factory }

        /** Required. Factory invoked once per session. */
        fun setTranslator(factory: () -> SublyTranslator) = apply { translatorFactory = factory }

        /**
         * Optional. Overrides the default [AudioPlayback]-based capture —
         * mainly useful for tests and custom capture sources.
         */
        fun setAudioCapture(factory: () -> AudioCapture) = apply { audioCaptureFactory = factory }

        /**
         * Restore sentence-terminal punctuation and casing on engines that
         * emit neither (Vosk, sherpa-onnx). **On by default.** Engines that
         * already punctuate, such as Whisper, are unaffected.
         *
         * Beyond readability, this is what lets sentence assembly split at
         * sentences at all: it only treats a boundary as complete when the
         * text ends in a terminator, so unpunctuated input leaves captions
         * breaking wherever the recogniser happened to endpoint.
         *
         * Measured on the accuracy benchmark: readability rose sharply on
         * Chinese (25 -> 82), where the recordings are separate utterances and
         * an endpoint really is a sentence end.
         *
         * The known failure mode is continuous prose. A speaker pausing for
         * breath mid-clause gets a period written there — "reference to its.
         * capacity". Whether that costs more than the punctuation gains is
         * unresolved: the English cells measured -7 and +5, both inside the
         * judge's noise floor, on a pipeline that has since had two bugs fixed
         * (a truncated playback window and mid-word caption splits). Turn it
         * off if your audio is continuous speech and the mid-clause periods
         * bother you more than the missing ones.
         */
        fun setPunctuationRestoration(enabled: Boolean) =
            apply { restorePunctuation = enabled }

        /**
         * @throws IllegalArgumentException with an actionable message when a
         * required component is missing — never a bare NPE.
         */
        fun build(): Subly = Subly(
            asrEngineFactory = requireNotNull(asrEngineFactory) {
                "An ASR engine is required. Call setAsrEngine { ... } before build()."
            },
            translatorFactory = requireNotNull(translatorFactory) {
                "A translator is required. Call setTranslator { ... } before build()."
            },
            audioCaptureFactory = audioCaptureFactory,
            restorePunctuation = restorePunctuation,
        )
    }
}