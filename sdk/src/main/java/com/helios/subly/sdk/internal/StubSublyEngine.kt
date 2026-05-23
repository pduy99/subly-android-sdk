package com.helios.subly.sdk.internal

import android.content.Context
import android.media.projection.MediaProjection
import com.helios.subly.sdk.domain.model.TranslationPacket
import com.helios.subly.sdk.domain.repository.SublyEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class StubSublyEngine(
    private val appContext: Context,
) : SublyEngine {

    private val packets = MutableSharedFlow<TranslationPacket>(
        replay = 0,
        extraBufferCapacity = 16,
    )

    override fun startTranslationPipeline(
        mediaProjection: MediaProjection,
        targetLanguageCode: String,
    ): SharedFlow<TranslationPacket> = packets.asSharedFlow()

    override fun stopTranslationPipeline() {
        // no-op in stub
    }
}
