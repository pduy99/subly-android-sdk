package com.helios.subly.sdk.domain.repository

import android.content.Context
import android.media.projection.MediaProjection
import com.helios.subly.sdk.domain.model.TranslationPacket
import kotlinx.coroutines.flow.SharedFlow

/**
 * Public entry point exposed by the Subly SDK.
 */
interface SublyEngine {

    /**
     * Begin the translation pipeline using an already-resolved [MediaProjection]
     * (which must be obtained inside a running mediaProjection foreground service).
     *
     * @return a hot [SharedFlow] of translation packets. Multiple collectors may
     * attach simultaneously (e.g. overlay + analytics).
     */
    fun startTranslationPipeline(
        mediaProjection: MediaProjection,
        targetLanguageCode: String,
    ): SharedFlow<TranslationPacket>

    /** Tear down audio/vision capture and any native AI resources. */
    fun stopTranslationPipeline()

    interface Factory {
        fun create(context: Context): SublyEngine

        /**
         * Builds the SDK's [SublyModelRegistry]. Independent from [create] so
         * the Consumer can pre-flight downloads before the user ever taps
         * Start (no capture/JNI resources are allocated by this call).
         */
        fun createModelRegistry(context: Context): SublyModelRegistry
    }

    companion object {
        /** Returns a default [Factory] implementation provided by the SDK. */
        @JvmStatic
        fun factory(): Factory = com.helios.subly.sdk.internal.DefaultSublyEngineFactory
    }
}
