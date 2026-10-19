package com.helios.subly.asr.sherpa

import android.content.Context
import com.helios.subly.asr.api.SublyAsr
import com.helios.subly.core.downloader.ModelDownloader
import com.helios.subly.core.downloader.OkHttpModelDownloader
import com.helios.subly.core.model.AccelerationAware
import com.helios.subly.core.model.AccelerationReport
import com.helios.subly.core.model.AsrResult
import com.helios.subly.core.model.AudioFrame
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * The best on-device recogniser for each source language, chosen by
 * measurement ([SherpaOnnxModel.bestFor]): a streaming transducer where one
 * was within reach of the best offline model, otherwise an offline model on
 * VAD-cut segments.
 *
 * | Language | Model | Mode |
 * |---|---|---|
 * | en | Kroko Zipformer (71 MB) | streaming |
 * | zh | Zipformer zh-int8 (167 MB) + CT-Transformer punctuation (76 MB) | streaming |
 * | ja | ReazonSpeech k2 v2 (160 MB) | offline + VAD |
 * | es | Kroko Zipformer (156 MB) | streaming |
 * | vi | Zipformer vi 30M (34 MB) | offline + VAD |
 *
 * One model is loaded at a time: switching to a language served by the other
 * mode releases the one in use first. Only the model a language needs is
 * downloaded, on its first prepare.
 *
 * The sherpa-onnx runtime is compileOnly in this module: the app must ship
 * the sherpa-onnx AAR.
 */
class SherpaAutoTranscriber internal constructor(
    private val streaming: SublyAsr,
    private val offline: SublyAsr,
) : SublyAsr, AccelerationAware {

    @JvmOverloads
    constructor(
        context: Context,
        modelDownloader: ModelDownloader = OkHttpModelDownloader(),
    ) : this(
        streaming = SherpaOnnxTranscriber(context, modelDownloader),
        offline = OfflineVadTranscriber(context, modelDownloader),
    )

    /** Both modes emit one final per utterance. */
    override val emitsCompleteUtterances: Boolean = true

    @Volatile
    private var active: SublyAsr? = null

    override val accelerationReport: AccelerationReport?
        get() = (active as? AccelerationAware)?.accelerationReport

    override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = flow {
        val best = SherpaOnnxModel.bestFor(config.source)
        if (best == null) {
            emit(ModelPrepState.Error(IllegalArgumentException(
                "No recogniser for '${config.source}'. Supported: ${supportedLanguages()}."
            )))
            return@flow
        }
        val engine = if (best.kind == SherpaModelKind.ASR) streaming else offline
        val previous = active
        if (previous != null && previous !== engine) previous.release()
        active = engine
        emitAll(engine.prepareModel(config))
    }

    override fun transcribe(frames: Flow<AudioFrame>): Flow<AsrResult> =
        active?.transcribe(frames) ?: flow {
            throw IllegalStateException("Recogniser not prepared. Collect prepareModel() to Ready before transcribe().")
        }

    override fun release() {
        streaming.release()
        offline.release()
        active = null
    }

    override fun supportedLanguages(): List<String> = SherpaOnnxModel.bestLanguages
}
