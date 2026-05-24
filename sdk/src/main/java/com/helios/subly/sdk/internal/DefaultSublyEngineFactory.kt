package com.helios.subly.sdk.internal

import android.content.Context
import com.helios.subly.sdk.data.ai.SherpaOnnxTranscriber
import com.helios.subly.sdk.data.audio.AudioPlaybackCaptureSource
import com.helios.subly.sdk.data.audio.AudioRecordDataSource
import com.helios.subly.sdk.data.translate.MlKitTranslator
import com.helios.subly.sdk.data.vision.ImageReaderDataSource
import com.helios.subly.sdk.data.vision.MlKitOcrRecognizer
import com.helios.subly.sdk.data.vision.VirtualDisplayCaptureSource
import com.helios.subly.sdk.domain.repository.SublyEngine
import com.helios.subly.sdk.domain.repository.SublyModelRegistry

internal object DefaultSublyEngineFactory : SublyEngine.Factory {
    override fun createModelRegistry(context: Context): SublyModelRegistry =
        DefaultSublyModelRegistry(context.applicationContext)

    override fun create(context: Context): SublyEngine {
        val appContext = context.applicationContext
        return SublyEngineImpl(
            audioCapture = AudioPlaybackCaptureSource(dataSource = AudioRecordDataSource()),
            transcriber = SherpaOnnxTranscriber(context = appContext),
            // On-device NMT bridge. Requires Google Play Services at runtime;
            // when GMS is absent or a language pair is unsupported, the
            // translator degrades to pass-through (source-language text).
            translator = MlKitTranslator(),
            // Vision fallback fires only after the audio path emits a
            // sustained DRM-block. Until then no VirtualDisplay is allocated.
            visionCapture = VirtualDisplayCaptureSource(
                dataSource = ImageReaderDataSource(appContext),
            ),
            ocrRecognizer = MlKitOcrRecognizer(),
        )
    }

    private const val TAG = "SublyEngineFactory"
}
