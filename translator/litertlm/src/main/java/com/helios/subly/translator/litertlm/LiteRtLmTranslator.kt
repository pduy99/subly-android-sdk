package com.helios.subly.translator.litertlm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.helios.subly.core.downloader.ModelDownloader
import com.helios.subly.core.downloader.OkHttpModelDownloader
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.translator.api.SublyTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * [SublyTranslator] backed by an on-device LLM through LiteRT-LM.
 *
 * ## Why this exists, and what it costs
 *
 * ML Kit translates a caption in tens of milliseconds from a ~30 MB per-pair
 * NMT model. It is also sentence-blind: it sees one caption at a time with no
 * notion of register, idiom, or the fact that it is subtitling speech. An
 * instruction-tuned LLM handles all three — for a ~1.6 GB download and
 * seconds, not milliseconds, per caption.
 *
 * That latency is the whole design constraint here, and it is why this
 * translator declares [translatesPartials] `false`. Partials arrive every
 * ~200 ms and are re-translated as they grow; routing those through a
 * multi-second generation does not degrade gracefully, it stalls the audio
 * pipeline, because `SublySession` translates on the same coroutine that
 * collects ASR results. Finals go through the LLM; partials show source text
 * until the sentence completes.
 *
 * ## Threading
 *
 * `sendMessage` is a blocking native call, so [translate] moves to
 * [Dispatchers.Default]. Generation and [release] are serialised on one lock:
 * closing the engine while a generation is in flight frees memory the native
 * side is still reading. The consequence is that [release] blocks until any
 * in-flight caption finishes — the same caveat the SDK already documents for
 * `SublySession.close()`, just measured in seconds rather than milliseconds.
 */
class LiteRtLmTranslator internal constructor(
    private val context: Context,
    private val model: LiteRtLmModel,
    private val loader: LiteRtLmModelLoader,
    private val threadCount: Int,
) : SublyTranslator {

    /**
     * @param modelDownloader downloader used to fetch the checkpoint on first
     *   prepare. Defaults to a plain [OkHttpModelDownloader]; supply your own
     *   to control how the ~1.6 GB transfer is made (metered-network
     *   policies, a private mirror, resume).
     */
    @JvmOverloads
    constructor(
        context: Context,
        modelDownloader: ModelDownloader = OkHttpModelDownloader(),
    ) : this(
        context = context,
        model = LiteRtLmModel.Default,
        loader = LiteRtLmModelLoader(context, modelDownloader),
        threadCount = defaultThreadCount(),
    )

    /** See the class docs: an LLM is a finals-only translator. */
    override val translatesPartials: Boolean = false

    private var engine: Engine? = null

    /** Rebuilt per language pair; the engine itself is pair-agnostic. */
    private var conversationConfig: ConversationConfig? = null
    private var sourceTag: String = ""
    private var targetTag: String = ""

    /**
     * Serialises generation against [release] so the engine is never freed
     * mid-inference, and serialises concurrent [translate] calls — one native
     * engine, one generation at a time.
     */
    private val inferenceLock = Any()

    override suspend fun translate(text: String): String {
        val source = text.trim()
        check(engine != null) { "Translator not prepared. Call prepareModel first." }
        if (source.isEmpty()) return ""

        return withContext(Dispatchers.Default) {
            synchronized(inferenceLock) {
                val activeEngine = engine
                    ?: throw IllegalStateException("Translator was released mid-translation.")
                val config = requireNotNull(conversationConfig)

                // A fresh conversation per caption. Reusing one would let the
                // previous captions accumulate as history, which for a
                // translation task is pure drift: the model starts answering
                // about the conversation instead of translating the sentence.
                val conversation = activeEngine.createConversation(config)
                val raw = try {
                    textOf(conversation.sendMessage(source))
                } finally {
                    conversation.close()
                }

                val translated = TranslationPrompt.clean(raw)
                // NOTE: never log `source` or `translated` — end-user speech.
                when {
                    TranslationGuards.isSourceEcho(translated, source, sourceTag, targetTag) ->
                        error("Model echoed the source instead of translating it.")

                    TranslationGuards.isDegenerate(translated) ->
                        error("Model produced degenerate output (${translated.length} chars).")

                    else -> translated
                }
            }
        }
    }

    override fun prepareModel(config: LanguageConfig): Flow<ModelPrepState> = flow {
        emit(ModelPrepState.Checking)

        val unsupported = listOf(config.source, config.target)
            .filterNot { tag -> tag.substringBefore('-') in model.supportedLanguages }
        if (unsupported.isNotEmpty()) {
            emit(
                ModelPrepState.Error(
                    IllegalArgumentException(
                        "${model.name} does not support ${unsupported.joinToString()}. " +
                            "Supported: ${model.supportedLanguages}."
                    )
                )
            )
            return@flow
        }

        // Language-pair state is cheap and must be refreshed even on the
        // already-prepared fast path — a session can be re-prepared for a
        // different pair against the same warm engine.
        sourceTag = config.source
        targetTag = config.target
        conversationConfig = buildConversationConfig(sourceTag, targetTag)

        if (engine != null) {
            emit(ModelPrepState.Ready)
            return@flow
        }

        loader.insufficientSpace(model)?.let { shortfall ->
            emit(
                ModelPrepState.Error(
                    IllegalStateException("Not enough storage for ${model.fileName}: $shortfall.")
                )
            )
            return@flow
        }

        // Download: 0% -> 90%. The remaining budget is engine init, which on
        // a 1.6 GB checkpoint is tens of seconds of memory-mapping and graph
        // setup — long enough that reporting it as progress matters.
        loader.provisionWithProgress(model).collect { emit(ModelPrepState.Preparing(it * 0.9f)) }

        if (!loader.isReady(model)) {
            emit(
                ModelPrepState.Error(
                    IllegalStateException(
                        "Failed to provision ${model.fileName}. The download did not " +
                            "complete — check connectivity and retry."
                    )
                )
            )
            return@flow
        }

        emit(ModelPrepState.Preparing(0.92f))

        val created = runCatching {
            Engine(
                EngineConfig(
                    modelPath = loader.modelFile(model).absolutePath,
                    backend = Backend.CPU(threadCount = threadCount),
                    maxNumTokens = model.maxNumTokens,
                    cacheDir = context.cacheDir.absolutePath,
                )
            ).apply { initialize() }
        }.getOrElse { error ->
            // Engine init is where an out-of-memory device fails, and it
            // throws rather than returning null. Per the ModelPrepState
            // contract this must surface as Error, not as an exception
            // escaping the flow.
            emit(ModelPrepState.Error(IllegalStateException("LiteRT-LM engine init failed", error)))
            return@flow
        }

        synchronized(inferenceLock) { engine = created }
        Log.i(TAG, "LiteRT-LM engine ready (${model.fileName}, $threadCount threads)")
        emit(ModelPrepState.Ready)
    }.flowOn(Dispatchers.IO)

    override fun release() {
        synchronized(inferenceLock) {
            runCatching { engine?.close() }
            engine = null
            conversationConfig = null
        }
    }

    override fun supportedLanguages(): List<String> = model.supportedLanguages

    private fun buildConversationConfig(source: String, target: String) = ConversationConfig(
        systemInstruction = Contents.of(TranslationPrompt.systemInstruction(source, target)),
        // topK = 1 is greedy decoding, which makes temperature and topP
        // inert. Greedy on purpose: a translator inside an accuracy benchmark
        // has to be reproducible, and sampling buys variety that a subtitle
        // does not want.
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0),
        // The latency guard. A caption is one sentence, so a generation that
        // runs past this is a repetition loop, and on this hardware every
        // extra token is real wall-clock time on the audio pipeline.
        maxOutputToken = MAX_OUTPUT_TOKENS,
    )

    /** Concatenates the text parts of a model reply, ignoring other content. */
    private fun textOf(message: Message): String =
        message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString(separator = "") { it.text }

    private companion object {
        const val TAG = "LiteRtLmTranslator"

        /** Generous for one sentence in any target language, bounded for latency. */
        const val MAX_OUTPUT_TOKENS = 256

    }
}

/**
 * Half the cores, capped at 4.
 *
 * These are big.LITTLE parts: handing the runtime every core schedules work
 * onto little cores that finish late and stall the whole batch, so more
 * threads past the big cluster is usually slower, not faster.
 *
 * A file-level function rather than a companion one because it is called from
 * a constructor-delegation argument, which cannot see companion members.
 */
private fun defaultThreadCount(): Int =
    min(4, (Runtime.getRuntime().availableProcessors() + 1) / 2).coerceAtLeast(1)
