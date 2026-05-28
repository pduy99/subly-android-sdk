package com.helios.subly.sdk

import android.content.Context
import android.media.projection.MediaProjection
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.core.model.TranslationPacket
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public entry point exposed by the Subly SDK.
 */
interface SublyEngine {

    val engineState: StateFlow<EngineState>

    /**
     * Prepares the ASR and translation models required for [languageConfig].
     *
     * Call this after the user selects a language pair and before starting
     * the pipeline. Collect the returned [StateFlow] to observe progress
     * and update the UI.
     *
     * The flow transitions through [ModelPrepState.Preparing] (with 0.0–1.0
     * progress) and finally emits [ModelPrepState.Ready] on success or
     * [ModelPrepState.Error] on failure.
     */
    fun prepareModels(languageConfig: LanguageConfig): StateFlow<ModelPrepState>

    /**
     * Begin the translation pipeline using an already-resolved [android.media.projection.MediaProjection]
     * (which must be obtained inside a running mediaProjection foreground service).
     *
     * @return a hot [kotlinx.coroutines.flow.SharedFlow] of translation packets. Multiple collectors may
     * attach simultaneously (e.g. overlay + analytics).
     */
    fun startTranslationPipeline(
        mediaProjection: MediaProjection,
        languageConfig: LanguageConfig
    ): SharedFlow<TranslationPacket>

    /** Tear down audio/vision capture and any native AI resources. */
    fun stopTranslationPipeline()

    interface Factory {
        fun create(context: Context): SublyEngine
    }

    companion object {
        /** Returns a default [Factory] implementation provided by the SDK. */
        @JvmStatic
        fun factory(): Factory = DefaultSublyEngineFactory
    }
}