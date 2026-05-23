package com.helios.subly.sdk.internal

import com.helios.subly.sdk.domain.model.AudioFrame
import com.helios.subly.sdk.domain.model.LanguageConfig
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.helios.subly.sdk.domain.model.VisionFrame
import com.helios.subly.sdk.domain.repository.AiTranscriberRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.transform

/**
 * Placeholder transcriber wired in Phase 2 so the audio pipeline has a
 * downstream collector. Phase 3 swaps this for the Whisper JNI implementation.
 *
 * Drains incoming frames without emitting packets so the upstream capture loop
 * still applies backpressure correctly.
 */
internal class StubAiTranscriberRepository : AiTranscriberRepository {

    override fun transcribeAudio(
        frames: Flow<AudioFrame>,
        config: LanguageConfig,
    ): Flow<TranslationPacket> = frames.transform { /* discard until Phase 3 */ }

    override fun recognizeVision(
        frames: Flow<VisionFrame>,
        config: LanguageConfig,
    ): Flow<TranslationPacket> = emptyFlow()

    override fun release() = Unit
}
