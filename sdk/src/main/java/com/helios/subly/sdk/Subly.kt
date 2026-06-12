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
    )

    class Builder {
        private var asrEngineFactory: (() -> SublyAsr)? = null
        private var translatorFactory: (() -> SublyTranslator)? = null
        private var audioCaptureFactory: () -> AudioCapture = {
            AudioPlayback(dataSource = AudioRecordDataSource())
        }

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
        )
    }
}