package com.helios.subly.sdk.domain.usecase

import android.media.projection.MediaProjection
import android.util.Log
import com.helios.subly.sdk.data.vision.OcrRecognizer
import com.helios.subly.sdk.domain.model.LanguageConfig
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.helios.subly.sdk.domain.repository.VisionCaptureRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform

/**
 * Composes the vision capture and OCR/translation boundaries into the engine's
 * fallback translation stream (activated on DRM-block).
 */
internal class ProcessOcrFrameUseCase(
    private val visionCapture: VisionCaptureRepository,
    private val ocrRecognizer: OcrRecognizer,
) {
    operator fun invoke(
        mediaProjection: MediaProjection,
        config: LanguageConfig,
        targetFps: Int = 2,
    ): Flow<TranslationPacket> {
        var lastText = ""
        return visionCapture.frames(mediaProjection, targetFps)
            .transform { frame ->
                val text = ocrRecognizer.recognize(frame)
                if (text.isEmpty() || text == lastText) return@transform
                lastText = text
                emit(
                    TranslationPacket(
                        text = text,
                        sourceLanguageCode = "auto",
                        targetLanguageCode = config.targetLanguageCode,
                        timestampMs = frame.timestampMs,
                        source = TranslationPacket.Source.VISION,
                        isFinal = true,
                    ),
                )
            }
            .flowOn(Dispatchers.Default)
    }
}
