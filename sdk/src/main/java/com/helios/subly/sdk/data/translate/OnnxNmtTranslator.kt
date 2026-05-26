package com.helios.subly.sdk.data.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.helios.subly.sdk.data.ai.SherpaOnnxModel
import com.helios.subly.sdk.data.ai.SherpaOnnxModelLoader
import com.helios.subly.sdk.domain.repository.DownloadResult
import com.helios.subly.sdk.domain.repository.TranslatorRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.LongBuffer

/**
 * On-device NMT translator backed by ONNX Runtime and Opus-MT (Helsinki-NLP).
 *
 * Tokenisation uses an in-house pure-Kotlin SentencePiece Unigram reader
 * ([SentencePieceProcessor]) that parses the binary `.spm` files directly.
 * Avoiding native JNI (DJL ships only Linux/macOS `.so` files) keeps the SDK
 * Android-portable.
 *
 * Note on performance: this implementation forgoes JVM-side past_key_values
 * caching (would require manual 4-D tensor copies across JNI). Cost is
 * O(N^2) over the decoded sequence; acceptable because
 * `ClauseGatingPunctuationUseCase` caps clauses at ~12 words. For longer
 * inputs, switch to a merged-with-past decoder ONNX (Optimum:
 * `--task text2text-generation-with-past`).
 */
internal class OnnxNmtTranslator(
    private val context: Context,
    private val model: SherpaOnnxModel = SherpaOnnxModel.NMT_OPUS_MT_EN_VI,
) : TranslatorRepository {

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var sourceSp: SentencePieceProcessor? = null
    private var targetSp: SentencePieceProcessor? = null
    private var vocab: MarianVocab? = null

    private val initMutex = Mutex()
    private val inferenceMutex = Mutex()
    private var initialized = false
    private var available = false

    private suspend fun ensureInitialized(): Boolean = initMutex.withLock {
        if (initialized) return@withLock available
        initialized = true

        runCatching {
            val modelDir = SherpaOnnxModelLoader(context).resolve(model)
                ?: error("Opus-MT model directory not found")

            val dir = File(modelDir)
            val files = dir.listFiles()?.toList().orEmpty()
            val encoderFile = files.firstOrNull { it.name.matches(Regex("encoder.*\\.onnx")) }
                ?: error("Encoder ONNX not found in $modelDir")
            val decoderFile = files.firstOrNull { it.name.matches(Regex("decoder.*\\.onnx")) }
                ?: error("Decoder ONNX not found in $modelDir")
            val sourceSpm = File(modelDir, "source.spm")
            val targetSpm = File(modelDir, "target.spm")
            val vocabFile = File(modelDir, "vocab.json")
            require(sourceSpm.isFile) { "source.spm missing in $modelDir" }
            require(targetSpm.isFile) { "target.spm missing in $modelDir" }
            require(vocabFile.isFile) {
                "vocab.json missing in $modelDir - required for Marian piece-to-id mapping"
            }

            env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(NUM_THREADS)
            }
            encoderSession = env!!.createSession(encoderFile.absolutePath, options)
            decoderSession = env!!.createSession(decoderFile.absolutePath, options)
            sourceSp = SentencePieceProcessor.load(sourceSpm)
            targetSp = SentencePieceProcessor.load(targetSpm)
            vocab = MarianVocab.load(vocabFile)

            available = true
            Log.i(TAG, "OnnxNmtTranslator initialised from $modelDir")
        }.onFailure { error ->
            Log.e(TAG, "Failed to initialise OnnxNmtTranslator: ${error.message}", error)
            available = false
        }

        available
    }

    override fun translate(
        text: String,
        sourceBcp47: String,
        targetBcp47: String,
    ): Flow<String> = flow {
        if (sourceBcp47.equals(targetBcp47, ignoreCase = true)) {
            emit(text)
            return@flow
        }
        if (!ensureInitialized()) {
            emit(text)
            return@flow
        }

        val translated = inferenceMutex.withLock {
            runCatching { runGreedyDecode(text) }
                .onFailure { Log.w(TAG, "translate failed: ${it.message}", it) }
                .getOrDefault(text)
        }
        emit(translated)
    }.flowOn(Dispatchers.Default)

    override suspend fun ensureModel(sourceBcp47: String, targetBcp47: String): DownloadResult {
        if (sourceBcp47.equals("en", true) && targetBcp47.equals("vi", true)) {
            return if (ensureInitialized()) DownloadResult.Success
            else DownloadResult.Failed(IllegalStateException("Opus-MT en-vi model missing"))
        }
        return DownloadResult.Unsupported
    }

    override suspend fun identifySource(text: String): String? = "en"

    override fun release() {
        runCatching { encoderSession?.close() }
        runCatching { decoderSession?.close() }
        encoderSession = null
        decoderSession = null
        sourceSp = null
        targetSp = null
        vocab = null
        initialized = false
        available = false
    }

    /**
     * Greedy autoregressive decode. No past_key_values cache (see class kdoc).
     */
    private fun runGreedyDecode(text: String): String {
        val ortEnv = env ?: return text
        val encoder = encoderSession ?: return text
        val decoder = decoderSession ?: return text
        val srcSp = sourceSp ?: return text
        val tgtSp = targetSp ?: return text
        val v = vocab ?: return text

        // Marian Opus-MT en-XX is multilingual-target: prepend the language
        // tag (`>>vie<<` here) or the model hallucinates. SP pieces -> model
        // embedding ids via `vocab.json` (SP-internal id order differs from
        // the model's embedding row order).
        //
        // Lowercase first: k2fsa's English streaming Zipformer emits ALL-CAPS
        // BPE tokens, but Opus-MT was trained on mixed-case text. Feeding it
        // uppercase fractures words into rare subwords and produces
        // hallucinations ("Thuyết" / "CLADE" / etc.).
        val normalized = text.lowercase()
        val srcPieces = mutableListOf<String>()
        if (v.contains(TARGET_LANG_TAG)) srcPieces.add(TARGET_LANG_TAG)
        srcPieces.addAll(srcSp.encodeAsPieces(normalized))
        if (srcPieces.isEmpty()) return text

        val srcIds = LongArray(srcPieces.size + 1) { i ->
            if (i < srcPieces.size) v.encode(srcPieces[i]).toLong()
            else MarianVocab.EOS_ID.toLong()
        }
        val srcLen = srcIds.size
        val attentionMask = LongArray(srcLen) { 1L }

        val inputIdsTensor = OnnxTensor.createTensor(
            ortEnv,
            LongBuffer.wrap(srcIds),
            longArrayOf(1, srcLen.toLong()),
        )
        val attentionMaskTensor = OnnxTensor.createTensor(
            ortEnv,
            LongBuffer.wrap(attentionMask),
            longArrayOf(1, srcLen.toLong()),
        )

        val encoderOutputs = encoder.run(
            mapOf("input_ids" to inputIdsTensor, "attention_mask" to attentionMaskTensor),
        )
        val encoderHidden = encoderOutputs.iterator().next().value as OnnxTensor

        // Marian's decoder_start_token_id == pad_token_id == last index of
        // the model vocab (53684 for en-vi). Reading from vocab.json size
        // keeps us model-agnostic.
        val decoderStartId = (v.size - 1).toLong()
        val decodedIds = mutableListOf(decoderStartId)
        var emitted: String = text

        try {
            for (@Suppress("UNUSED_PARAMETER") step in 0 until MAX_DECODE_STEPS) {
                val decInputIds = LongArray(decodedIds.size) { decodedIds[it] }
                val decTensor = OnnxTensor.createTensor(
                    ortEnv,
                    LongBuffer.wrap(decInputIds),
                    longArrayOf(1, decodedIds.size.toLong()),
                )

                val decFeed = mutableMapOf<String, OnnxTensor>(
                    "input_ids" to decTensor,
                    "encoder_attention_mask" to attentionMaskTensor,
                    "encoder_hidden_states" to encoderHidden,
                )
                val decOut = decoder.run(decFeed)
                val logitsValue: OnnxValue = decOut.iterator().next().value
                val logitsTensor = logitsValue as? OnnxTensor ?: run {
                    decTensor.close(); decOut.close(); break
                }
                @Suppress("UNCHECKED_CAST")
                val logits = logitsTensor.value as Array<Array<FloatArray>>

                val lastStep = logits[0][decodedIds.size - 1]
                var bestId = 0
                var bestVal = Float.NEGATIVE_INFINITY
                for (i in lastStep.indices) {
                    if (lastStep[i] > bestVal) {
                        bestVal = lastStep[i]
                        bestId = i
                    }
                }
                decTensor.close()
                decOut.close()

                if (bestId == MarianVocab.EOS_ID) break
                decodedIds.add(bestId.toLong())
            }

            // Drop the decoder-start token, map model ids -> pieces via the
            // Marian vocab, then SP-detokenise (replace ▁ with spaces).
            val outPieces = ArrayList<String>(decodedIds.size - 1)
            for (i in 1 until decodedIds.size) {
                val piece = v.decode(decodedIds[i].toInt())
                if (piece.isNotEmpty()) outPieces.add(piece)
            }
            emitted = tgtSp.decodePieces(outPieces).ifBlank { text }
        } finally {
            inputIdsTensor.close()
            attentionMaskTensor.close()
            encoderOutputs.close()
        }
        Log.d("DUY2", "Translate from: $normalized -> $emitted")
        return emitted
    }

    private companion object {
        const val TAG = "SublyOnnxNmt"
        const val MAX_DECODE_STEPS = 128
        const val NUM_THREADS = 2
        // Multilingual-target language tag for Opus-MT en→XX. The model card
        // for Helsinki-NLP/opus-mt-en-vi requires `>>vie<<` (ISO 639-3) as
        // the first source token; without it the model hallucinates.
        const val TARGET_LANG_TAG = ">>vie<<"
    }
}
