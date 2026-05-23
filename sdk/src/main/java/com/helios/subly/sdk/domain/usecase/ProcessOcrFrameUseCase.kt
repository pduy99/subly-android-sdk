package com.helios.subly.sdk.domain.usecase

import android.media.projection.MediaProjection
import com.helios.subly.sdk.domain.model.LanguageConfig
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.helios.subly.sdk.domain.repository.AiTranscriberRepository
import com.helios.subly.sdk.domain.repository.VisionCaptureRepository
import kotlinx.coroutines.flow.Flow

/**
 * Composes the vision capture and OCR/translation boundaries into the engine's
 * fallback translation stream (activated on DRM-block).
 */
class ProcessOcrFrameUseCase(
    private val visionCapture: VisionCaptureRepository,
    private val transcriber: AiTranscriberRepository,
) {
    operator fun invoke(
        mediaProjection: MediaProjection,
        config: LanguageConfig,
        targetFps: Int = 2,
    ): Flow<TranslationPacket> =
        transcriber.recognizeVision(
            visionCapture.frames(mediaProjection, targetFps),
            config,
        )
}
