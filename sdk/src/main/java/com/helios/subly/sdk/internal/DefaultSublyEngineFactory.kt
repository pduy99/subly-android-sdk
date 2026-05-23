package com.helios.subly.sdk.internal

import android.content.Context
import com.helios.subly.sdk.data.ai.WhisperTranscriber
import com.helios.subly.sdk.data.audio.AudioPlaybackCaptureSource
import com.helios.subly.sdk.data.audio.AudioRecordDataSource
import com.helios.subly.sdk.data.translate.MlKitTranslator
import com.helios.subly.sdk.domain.repository.SublyEngine

internal object DefaultSublyEngineFactory : SublyEngine.Factory {
    override fun create(context: Context): SublyEngine {
        val appContext = context.applicationContext
        return SublyEngineImpl(
            appContext = appContext,
            audioCapture = AudioPlaybackCaptureSource(dataSource = AudioRecordDataSource()),
            // WhisperTranscriber self-degrades to a drain if the native lib
            // or the on-disk model isn't available, so wiring it
            // unconditionally is safe on CI and stub-built devices.
            transcriber = WhisperTranscriber(appContext),
            // On-device NMT bridge. Requires Google Play Services at runtime;
            // when GMS is absent or a language pair is unsupported, the
            // translator degrades to pass-through (source-language text).
            translator = MlKitTranslator(),
        )
    }
}
