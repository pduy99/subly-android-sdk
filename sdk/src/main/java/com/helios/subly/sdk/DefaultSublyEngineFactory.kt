package com.helios.subly.sdk

import android.content.Context
import com.helios.subly.asr.whisper.WhisperTranscriber
import com.helios.subly.audio.impl.AudioRecordDataSource
import com.helios.subly.core.data.repository.AudioPlaybackRepository
import com.helios.subly.translator.mlkit.MlKitTranslator

internal object DefaultSublyEngineFactory : SublyEngine.Factory {

    override fun create(context: Context): SublyEngine {
        val appContext = context.applicationContext

        return SublyEngineImpl(
            audioCapture = AudioPlaybackRepository(dataSource = AudioRecordDataSource()),
            transcriber = WhisperTranscriber(context = appContext),
            translator = MlKitTranslator(),
        )
    }
}
