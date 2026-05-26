package com.helios.subly.sdk.internal

import android.content.Context
import com.helios.subly.sdk.data.ai.SherpaOnnxModel
import com.helios.subly.sdk.data.ai.SherpaOnnxModelLoader
import com.helios.subly.sdk.data.ai.SherpaOnnxPunctuation
import com.helios.subly.sdk.data.ai.SherpaOnnxTranscriber
import com.helios.subly.sdk.data.audio.AudioPlaybackCaptureSource
import com.helios.subly.sdk.data.audio.AudioRecordDataSource
import com.helios.subly.sdk.data.translate.OnnxNmtTranslator
import com.helios.subly.sdk.data.vision.ImageReaderDataSource
import com.helios.subly.sdk.data.vision.MlKitOcrRecognizer
import com.helios.subly.sdk.data.vision.VirtualDisplayCaptureSource
import com.helios.subly.sdk.data.audio.AndroidMediaPlaybackRepository
import com.helios.subly.sdk.domain.usecase.DetectSystemSilenceUseCase
import com.helios.subly.sdk.domain.repository.SublyEngine
import com.helios.subly.sdk.domain.repository.SublyModelRegistry

internal object DefaultSublyEngineFactory : SublyEngine.Factory {
    override fun createModelRegistry(context: Context): SublyModelRegistry =
        DefaultSublyModelRegistry(context.applicationContext)

    override fun create(context: Context): SublyEngine {
        val appContext = context.applicationContext

        val mediaPlaybackRepo = AndroidMediaPlaybackRepository(appContext)

        // Lazily-initialised punctuation wrapper. Engine pipeline calls
        // `init()` once and reuses across the session.
        val punctuationModelDir = SherpaOnnxModelLoader(appContext)
            .resolve(SherpaOnnxModel.PUNCTUATION_EN)
        val punctuation = punctuationModelDir?.let { SherpaOnnxPunctuation(it) }

        return SublyEngineImpl(
            audioCapture = AudioPlaybackCaptureSource(dataSource = AudioRecordDataSource()),
            transcriber = SherpaOnnxTranscriber(context = appContext),
            // On-device NMT via ONNX Runtime + Opus-MT (en -> vi).
            translator = OnnxNmtTranslator(context = appContext),
            punctuation = punctuation,
            // Vision fallback fires only after the audio path emits a
            // sustained DRM-block. Until then no VirtualDisplay is allocated.
            visionCapture = VirtualDisplayCaptureSource(
                dataSource = ImageReaderDataSource(appContext),
            ),
            ocrRecognizer = MlKitOcrRecognizer(),
            silenceDetector = DetectSystemSilenceUseCase(
                mediaPlaybackRepository = mediaPlaybackRepo
            ),
        )
    }

    private const val TAG = "SublyEngineFactory"
}
