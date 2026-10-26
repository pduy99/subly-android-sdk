package com.helios.subly.asr.sherpa

import android.content.Context
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.core.downloader.ModelDownloader
import com.helios.subly.core.downloader.OkHttpModelDownloader
import com.helios.subly.core.model.AccelerationAware

/**
 * SenseVoice small (Mandarin, English, Japanese, Korean, Cantonese) on
 * VAD-cut segments: an [OfflineVadTranscriber] pinned to
 * [SherpaOnnxModel.SENSE_VOICE], whatever the source language.
 *
 * The sherpa-onnx runtime is compileOnly in this module: the app must ship
 * the sherpa-onnx AAR.
 */
class SenseVoiceTranscriber private constructor(
    delegate: OfflineVadTranscriber,
) : SublyAsr by delegate, AccelerationAware by delegate {

    @JvmOverloads
    constructor(
        context: Context,
        modelDownloader: ModelDownloader = OkHttpModelDownloader(),
    ) : this(
        OfflineVadTranscriber(context, SherpaOnnxModelLoaderFactory.of(modelDownloader), SherpaOnnxModel.SENSE_VOICE)
    )
}
