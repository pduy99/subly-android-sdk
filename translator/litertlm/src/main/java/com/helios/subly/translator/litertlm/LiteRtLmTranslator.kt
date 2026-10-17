package com.helios.subly.translator.litertlm

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.helios.subly.core.downloader.ModelDownloader
import com.helios.subly.core.downloader.OkHttpModelDownloader
import com.helios.subly.core.model.AccelerationAware
import com.helios.subly.core.model.AccelerationReport
import com.helios.subly.core.model.BackendAttempt
import com.helios.subly.core.model.DelegateCrashGuard
import com.helios.subly.core.model.DelegateType
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.translator.api.IncrementalTranslator
import com.helios.subly.translator.api.SublyTranslator
import com.helios.subly.translator.api.TranslationSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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
 * That latency is why [translatesPartials] stays `false`: re-translating a
 * growing partial every ~200 ms through a multi-second generation cannot keep
 * up. Partials are instead translated incrementally through
 * [IncrementalTranslator]: the session commits a sentence clause by clause
 * (see `WaitKBuffer`), and each clause is one turn in a conversation that
 * lives for the sentence, so the earlier clauses are context already in the KV
 * cache rather than text prefilled again.
 *
 * ## Compute delegates
 *
 * [prepareModel] tries the delegates in [preferredDelegates] order and keeps
 * the first that both initialises and completes a tiny test generation; the
 * outcome is in [accelerationReport]. Two failure shapes are handled:
 *  - the delegate throws (no GPU support for the model, missing NPU
 *    libraries): recorded, next delegate tried;
 *  - the delegate takes the process down in native code: a marker file
 *    written before the attempt survives the crash, and the next launch skips
 *    that delegate instead of crashing again. It is retried after
 *    [CRASH_MARKER_TTL_MS], in case an OS or driver update fixed it.
 *
 * ## Threading
 *
 * LiteRT-LM generates on one native engine, one request at a time. Every
 * generation and [release] take [inferenceLock], so the engine is never freed
 * mid-inference; [release] cancels the running generation first, so it waits
 * for at most the current token rather than a whole caption.
 */
class LiteRtLmTranslator internal constructor(
    private val context: Context,
    private val model: LiteRtLmModel,
    private val loader: LiteRtLmModelLoader,
    private val threadCount: Int,
    private val preferredDelegates: List<DelegateType>,
    private val contextLines: Int = DEFAULT_CONTEXT_LINES,
) : SublyTranslator, IncrementalTranslator, AccelerationAware {

    /**
     * @param modelDownloader downloader used to fetch the checkpoint on first
     *   prepare. Defaults to a plain [OkHttpModelDownloader]; supply your own
     *   to control how the ~1.6 GB transfer is made (metered-network
     *   policies, a private mirror, resume).
     * @param model checkpoint to run. Defaults to the best one the device's
     *   memory allows ([LiteRtLmModel.forDevice]).
     * @param preferredDelegates delegates to try, in order. CPU is always
     *   appended as the last resort if missing. NPU is only useful with a
     *   checkpoint compiled for the device's NPU; the default model is not,
     *   so the default skips it.
     */
    @JvmOverloads
    constructor(
        context: Context,
        modelDownloader: ModelDownloader = OkHttpModelDownloader(),
        preferredDelegates: List<DelegateType> = DEFAULT_DELEGATES,
        model: LiteRtLmModel = LiteRtLmModel.forDevice(context),
        contextLines: Int = DEFAULT_CONTEXT_LINES,
    ) : this(
        context = context,
        model = model,
        contextLines = contextLines,
        loader = LiteRtLmModelLoader(context, modelDownloader),
        threadCount = defaultThreadCount(),
        preferredDelegates = preferredDelegates,
    )

    override val translatesPartials: Boolean = false

    @Volatile
    override var accelerationReport: AccelerationReport? = null
        private set

    @Volatile
    private var engine: Engine? = null

    /** Rebuilt per language pair; the engine itself is pair-agnostic. */
    @Volatile
    private var conversationConfig: ConversationConfig? = null

    @Volatile
    private var incrementalConfig: ConversationConfig? = null
    private var sourceTag: String = ""
    private var targetTag: String = ""

    private val inferenceLock = Mutex()

    /**
     * The last few completed sentences and their translations, oldest first,
     * fed to the next sentence as context. Cleared when the language pair
     * changes: context in another language would only confuse.
     */
    private val recentLines = ArrayDeque<Pair<String, String>>()

    /** The conversation generating right now, so [release] can cancel it. */
    @Volatile
    private var activeConversation: Conversation? = null

    override suspend fun translate(text: String): String {
        val source = text.trim()
        check(engine != null) { "Translator not prepared. Call prepareModel first." }
        if (source.isEmpty()) return ""

        return inferenceLock.withLock {
            withContext(Dispatchers.Default) {
                val activeEngine = engine
                    ?: throw IllegalStateException("Translator was released mid-translation.")

                // A fresh conversation per caption. Reusing one would let the
                // previous captions accumulate as history, which for a
                // translation task is pure drift: the model starts answering
                // about the conversation instead of translating the sentence.
                val conversation = activeEngine.createConversation(requireNotNull(conversationConfig))
                activeConversation = conversation
                val raw = try {
                    textOf(conversation.sendMessage(source))
                } finally {
                    activeConversation = null
                    conversation.close()
                }
                checkedTranslation(TranslationPrompt.clean(raw), source, allowBlank = false)
            }
        }
    }

    override fun openSegment(): TranslationSegment {
        check(engine != null) { "Translator not prepared. Call prepareModel first." }
        return Segment()
    }

    /**
     * One sentence as one conversation. Opened lazily on the first append so
     * an abandoned, never-used segment costs nothing.
     */
    private inner class Segment : TranslationSegment {
        private var conversation: Conversation? = null
        private val sources = ArrayList<String>()
        private val translations = ArrayList<String>()

        override val source: String get() = sources.joinToString(" ")
        override val translation: String get() = translations.filter { it.isNotEmpty() }.joinToString(" ")

        override fun append(sourceDelta: String): Flow<String> = flow {
            val piece = sourceDelta.trim()
            if (piece.isEmpty()) return@flow

            inferenceLock.withLock {
                val activeEngine = engine
                    ?: throw IllegalStateException("Translator was released mid-translation.")
                val conv = conversation
                    ?: activeEngine.createConversation(requireNotNull(incrementalConfig))
                        .also { conversation = it }

                var raw = ""
                activeConversation = conv
                // The sentence's first message carries the conversation so
                // far; later pieces already have it in the conversation.
                val message = if (sources.isEmpty()) {
                    TranslationPrompt.withContext(piece, synchronized(recentLines) { recentLines.toList() })
                } else {
                    piece
                }
                try {
                    streamTokens(conv, message).collect { chunk ->
                        raw = accumulate(raw, chunk)
                        val shown = TranslationGuards.stripHanLeak(TranslationPrompt.clean(raw), targetTag)
                        if (shown.isNotEmpty()) emit(shown)
                    }
                } finally {
                    activeConversation = null
                }

                val translated = checkedTranslation(TranslationPrompt.clean(raw), piece, allowBlank = true)
                sources.add(piece)
                translations.add(translated)
                emit(translated)
            }
        }.flowOn(Dispatchers.Default)

        override fun close() {
            val text = translation
            if (sources.isNotEmpty() && text.isNotBlank()) {
                synchronized(recentLines) {
                    recentLines.addLast(source to text)
                    while (recentLines.size > contextLines) recentLines.removeFirst()
                }
            }
            val conv = conversation ?: return
            conversation = null
            runCatching { conv.close() }
        }
    }

    /**
     * Bridges LiteRT-LM's callback streaming into a flow of text chunks.
     * Cancelling the collector cancels the native generation.
     */
    private fun streamTokens(conversation: Conversation, text: String): Flow<String> = callbackFlow {
        conversation.sendMessageAsync(
            text,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    trySend(textOf(message))
                }

                override fun onDone() {
                    close()
                }

                override fun onError(throwable: Throwable) {
                    close(throwable)
                }
            },
        )
        awaitClose { runCatching { conversation.cancelProcess() } }
    }

    /**
     * Streaming callbacks carry either the newest chunk or the whole reply so
     * far, depending on runtime version; accept both.
     */
    private fun accumulate(soFar: String, chunk: String): String =
        if (soFar.isNotEmpty() && chunk.startsWith(soFar)) chunk else soFar + chunk

    // NOTE: never log `source` or `translated` — end-user speech.
    private fun checkedTranslation(raw: String, source: String, allowBlank: Boolean): String {
        val translated = TranslationGuards.stripHanLeak(raw, targetTag)
        if (translated.length != raw.length) Log.w(TAG, "Removed untranslated Han characters from a translation")
        return when {
            translated.isBlank() && allowBlank -> ""
            TranslationGuards.isSourceEcho(translated, source, sourceTag, targetTag) ->
                error("Model echoed the source instead of translating it.")
            TranslationGuards.isDegenerate(translated) ->
                error("Model produced degenerate output (${translated.length} chars).")
            else -> translated
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
        if (config.source != sourceTag || config.target != targetTag) {
            synchronized(recentLines) { recentLines.clear() }
        }
        sourceTag = config.source
        targetTag = config.target
        conversationConfig = buildConversationConfig(
            TranslationPrompt.systemInstruction(sourceTag, targetTag), MAX_OUTPUT_TOKENS,
        )
        incrementalConfig = buildConversationConfig(
            TranslationPrompt.incrementalInstruction(sourceTag, targetTag), MAX_OUTPUT_TOKENS,
        )

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

        val attempts = ArrayList<BackendAttempt>()
        var created: Engine? = null
        var lastError: Throwable? = null
        for (delegate in delegateOrder()) {
            val (attempt, result) = tryDelegate(delegate)
            attempts.add(attempt)
            if (result.isSuccess) {
                created = result.getOrThrow()
                break
            }
            lastError = result.exceptionOrNull()
        }

        if (created == null) {
            // Every delegate, CPU included, failed. Engine init is where an
            // out-of-memory device fails, and it throws rather than returning
            // null; per the ModelPrepState contract this surfaces as Error.
            emit(
                ModelPrepState.Error(
                    IllegalStateException(
                        "LiteRT-LM engine init failed on every delegate: " +
                            attempts.joinToString { "${it.delegate}=${it.failure}" },
                        lastError,
                    )
                )
            )
            return@flow
        }

        val report = AccelerationReport(
            component = "translator:${model.name}",
            active = attempts.last().delegate,
            attempts = attempts,
        )
        inferenceLock.withLock { engine = created }
        accelerationReport = report
        Log.i(
            TAG,
            "LiteRT-LM engine ready (${model.fileName}) on ${report.active}; attempts: " +
                attempts.joinToString { "${it.delegate} ${if (it.succeeded) "ok" else it.failure} ${it.initMs}ms" },
        )
        emit(ModelPrepState.Ready)
    }.flowOn(Dispatchers.IO)

    private fun delegateOrder(): List<DelegateType> =
        (preferredDelegates + DelegateType.CPU).distinct()

    /**
     * Initialises an engine on [delegate] and proves it with a tiny
     * generation, under a crash marker.
     */
    private fun tryDelegate(delegate: DelegateType): Pair<BackendAttempt, Result<Engine>> {
        val guard = DelegateCrashGuard(context, crashMarker(delegate))
        if (delegate != DelegateType.CPU) {
            guard.skipReason()?.let { note ->
                return BackendAttempt(delegate, succeeded = false, initMs = 0, failure = note) to
                    Result.failure(IllegalStateException(note))
            }
        }

        val start = System.nanoTime()
        guard.arm(delegate.name)
        val proven = provenMarker(delegate)
        val result = runCatching {
            val candidate = Engine(
                EngineConfig(
                    modelPath = loader.modelFile(model).absolutePath,
                    backend = backendFor(delegate),
                    maxNumTokens = model.maxNumTokens,
                    cacheDir = engineCacheDir(delegate).absolutePath,
                )
            )
            try {
                val initStart = System.nanoTime()
                candidate.initialize()
                val initMs = (System.nanoTime() - initStart) / 1_000_000
                // Init alone does not prove a delegate: a GPU path can
                // initialise and then fail on the first kernel. One short
                // generation exercises prefill and decode end to end. Once
                // it has passed for this model on this OS build, it is not
                // repeated: on a 4B model it costs seconds on every start.
                val probeMs = if (proven.readTextOrNull() == Build.FINGERPRINT) {
                    0L
                } else {
                    val probeStart = System.nanoTime()
                    candidate.createConversation(
                        buildConversationConfig(TranslationPrompt.systemInstruction("en", "en"), PROBE_OUTPUT_TOKENS)
                    ).use { it.sendMessage("Hi") }
                    proven.parentFile?.mkdirs()
                    runCatching { proven.writeText(Build.FINGERPRINT) }
                    (System.nanoTime() - probeStart) / 1_000_000
                }
                Log.i(TAG, "$delegate engine init ${initMs}ms, probe ${if (probeMs == 0L) "skipped (proven)" else "${probeMs}ms"}")
                candidate
            } catch (t: Throwable) {
                runCatching { candidate.close() }
                throw t
            }
        }
        guard.disarm()
        val ms = (System.nanoTime() - start) / 1_000_000
        val attempt = BackendAttempt(
            delegate = delegate,
            succeeded = result.isSuccess,
            initMs = ms,
            failure = result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" },
        )
        if (result.isFailure) Log.w(TAG, "LiteRT-LM init on $delegate failed after ${ms}ms", result.exceptionOrNull())
        return attempt to result
    }

    private fun backendFor(delegate: DelegateType): Backend = when (delegate) {
        DelegateType.CPU -> Backend.CPU(threadCount = threadCount)
        DelegateType.GPU -> Backend.GPU()
        DelegateType.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
    }

    private fun crashMarker(delegate: DelegateType): File =
        File(File(context.filesDir, CRASH_MARKER_DIR), "${model.name}.${delegate.name}.init")

    /** Holds the OS build a delegate last passed its probe on. */
    private fun provenMarker(delegate: DelegateType): File =
        File(File(context.filesDir, CRASH_MARKER_DIR), "${model.name}.${delegate.name}.proven")

    /**
     * Where LiteRT-LM keeps what it compiles at init.
     *
     * For the GPU that is the compiled-program cache (5.5 MB for Qwen3-4B),
     * which turns a 25-55 s init into a fraction of that. It lived in
     * cacheDir, which Android empties under storage pressure, and the CPU
     * path's weight cache (2.3 GB for the same model) in the same directory
     * made that likely. The GPU cache now lives in filesDir, which only the
     * user clears. The CPU weight cache stays in cacheDir: it is too large
     * to keep unconditionally, and dropping it costs time, not correctness.
     */
    private fun engineCacheDir(delegate: DelegateType): File = when (delegate) {
        DelegateType.CPU -> context.cacheDir
        else -> File(context.filesDir, ENGINE_CACHE_DIR).apply { mkdirs() }
    }

    private fun File.readTextOrNull(): String? = if (exists()) runCatching { readText() }.getOrNull() else null

    override fun release() {
        runCatching { activeConversation?.cancelProcess() }
        runBlocking {
            inferenceLock.withLock {
                runCatching { engine?.close() }
                engine = null
                conversationConfig = null
                incrementalConfig = null
            }
        }
    }

    override fun supportedLanguages(): List<String> = model.supportedLanguages

    private fun buildConversationConfig(instruction: String, maxOutputTokens: Int) = ConversationConfig(
        systemInstruction = Contents.of(instruction),
        // topK = 1 is greedy decoding, which makes temperature and topP
        // inert. Greedy on purpose: a translator inside an accuracy benchmark
        // has to be reproducible, and sampling buys variety that a subtitle
        // does not want.
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0),
        // The latency guard. A caption is one sentence, so a generation that
        // runs past this is a repetition loop, and on this hardware every
        // extra token is real wall-clock time on the audio pipeline.
        maxOutputToken = maxOutputTokens,
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

        /**
         * Earlier sentences given as context; off by default. Measured on a
         * Galaxy S10 with small models (Qwen2.5-1.5B, Qwen3-1.7B), context
         * made the model copy an earlier translation instead of translating
         * the new line: seven lines in a row came back as the first line's
         * translation. Unproven on larger models, so it is opt-in.
         */
        const val DEFAULT_CONTEXT_LINES = 0

        /** The delegate probe only needs to see decode run. */
        const val PROBE_OUTPUT_TOKENS = 4

        const val CRASH_MARKER_DIR = "litertlm/init-guard"

        const val ENGINE_CACHE_DIR = "litertlm/engine-cache"

        val DEFAULT_DELEGATES = listOf(DelegateType.GPU, DelegateType.CPU)
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
