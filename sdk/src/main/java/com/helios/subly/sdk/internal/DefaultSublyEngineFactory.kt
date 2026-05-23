package com.helios.subly.sdk.internal

import android.content.Context
import com.helios.subly.sdk.data.audio.AudioPlaybackCaptureSource
import com.helios.subly.sdk.data.audio.AudioRecordDataSource
import com.helios.subly.sdk.domain.repository.SublyEngine

internal object DefaultSublyEngineFactory : SublyEngine.Factory {
    override fun create(context: Context): SublyEngine = SublyEngineImpl(
        appContext = context.applicationContext,
        audioCapture = AudioPlaybackCaptureSource(dataSource = AudioRecordDataSource()),
        transcriber = StubAiTranscriberRepository(),
    )
}
