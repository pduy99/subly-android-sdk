package com.helios.subly.sdk.domain.usecase

import android.media.projection.MediaProjection
import com.helios.subly.asr.api.AsrDataSource
import com.helios.subly.core.data.repository.AudioCaptureRepository
import com.helios.subly.core.model.TranslationPacket
import kotlinx.coroutines.flow.Flow

/**
 * Composes the audio capture and AI transcription boundaries into the engine's
 * primary translation stream.
 */
class ProcessAudioStreamUseCase(
    private val audioCapture: AudioCaptureRepository,
    private val transcriber: AsrDataSource,
) {
    operator fun invoke(
        mediaProjection: MediaProjection,
    ): Flow<TranslationPacket> =
        transcriber.transcribe(audioCapture.frames(mediaProjection))
}
