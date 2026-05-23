package com.helios.subly.sdk.domain.usecase

import android.media.projection.MediaProjection
import com.helios.subly.sdk.domain.model.LanguageConfig
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.helios.subly.sdk.domain.repository.AiTranscriberRepository
import com.helios.subly.sdk.domain.repository.AudioCaptureRepository
import kotlinx.coroutines.flow.Flow

/**
 * Composes the audio capture and AI transcription boundaries into the engine's
 * primary translation stream.
 */
class ProcessAudioStreamUseCase(
    private val audioCapture: AudioCaptureRepository,
    private val transcriber: AiTranscriberRepository,
) {
    operator fun invoke(
        mediaProjection: MediaProjection,
        config: LanguageConfig,
    ): Flow<TranslationPacket> =
        transcriber.transcribeAudio(audioCapture.frames(mediaProjection), config)
}
