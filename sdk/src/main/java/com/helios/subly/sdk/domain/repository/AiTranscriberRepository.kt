package com.helios.subly.sdk.domain.repository

import com.helios.subly.sdk.domain.model.AudioFrame
import com.helios.subly.sdk.domain.model.LanguageConfig
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.helios.subly.sdk.domain.model.VisionFrame
import kotlinx.coroutines.flow.Flow

/**
 * Boundary over the on-device AI core (Whisper JNI for audio + ML Kit for vision).
 *
 * The same interface fronts both pipelines so the engine can swap pipelines on
 * DRM-block without re-wiring downstream collectors.
 */
interface AiTranscriberRepository {

    /**
     * Translate a stream of PCM frames into target-language [TranslationPacket]s.
     * Source language is permanently auto-detected (per PRD).
     */
    fun transcribeAudio(
        frames: Flow<AudioFrame>,
        config: LanguageConfig,
    ): Flow<TranslationPacket>

    /**
     * Run text recognition over a stream of screen frames and translate the
     * recognized text into the target language.
     */
    fun recognizeVision(
        frames: Flow<VisionFrame>,
        config: LanguageConfig,
    ): Flow<TranslationPacket>

    /** Eagerly release native AI resources (Whisper context, ML Kit recognizer). */
    fun release()
}
