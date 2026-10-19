package com.helios.subly.benchmark.latency

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import com.google.gson.GsonBuilder
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.translator.litertlm.LiteRtLmModel
import com.helios.subly.translator.mlkit.MlKitTranslator
import com.helios.subly.translator.litertlm.LiteRtLmTranslator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Translation quality and speed of each catalog model, side by side.
 *
 * Runs the same sentences through every [LiteRtLmModel] (or the ones named
 * in the `probeModels` runner argument) as one-piece incremental segments,
 * the path the streaming stage uses, and records the output, time to first
 * token and total time. Writes `translator_probe.json`. Nothing is asserted
 * about the translations: this is for a human, or a judge, to read.
 *
 * `probeSpeculative=true|false` overrides the model's speculative decoding
 * (multi-token prediction); results carry a `+MTP` suffix when it is on.
 */
class TranslatorModelProbeTest {

    data class Sample(val source: String, val target: String, val text: String, val reference: String? = null)

    data class Result(
        val model: String,
        val delegate: String,
        val prepareMs: Long,
        val source: String,
        val target: String,
        val input: String,
        val output: String,
        val reference: String?,
        val ttftMs: Long,
        val totalMs: Long,
        val error: String?,
    )

    @Test
    fun probeModels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wanted = InstrumentationRegistry.getArguments().getString("probeModels")
            ?.split(',')?.map { it.trim() }?.toSet()
        val samples = when (InstrumentationRegistry.getArguments().getString("probeSamples")) {
            "zh_vi" -> ZH_VI_SAMPLES
            "flores" -> floresSamples()
            else -> SAMPLES
        }
        val results = ArrayList<Result>()

        // ML Kit: per-pair NMT, no LLM memory. Translated with plain
        // translate() calls; ttft is its whole time, since it does not stream.
        if (wanted == null || "MLKIT" in wanted) {
            val mlkit = MlKitTranslator()
            try {
                for ((pair, group) in samples.groupBy { it.source to it.target }) {
                    val start = System.nanoTime()
                    val state = withTimeout(PREPARE_TIMEOUT_MS) {
                        mlkit.prepareModel(LanguageConfig(pair.first, pair.second))
                            .first { it is ModelPrepState.Ready || it is ModelPrepState.Error }
                    }
                    val prepareMs = (System.nanoTime() - start) / 1_000_000
                    for (sample in group) {
                        val t0 = System.nanoTime()
                        val out = runCatching { mlkit.translate(sample.text) }
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        results += Result("MLKIT", "CPU", prepareMs, sample.source, sample.target, sample.text,
                            out.getOrDefault(""), sample.reference, ms, ms,
                            if (state is ModelPrepState.Error) "prepare failed: ${state.cause}" else out.exceptionOrNull()?.toString())
                        Log.i(TAG, "probe ${results.last()}")
                    }
                }
            } finally {
                mlkit.release()
            }
        }

        // ML Kit through English: zh->en and en->vi are far higher-resource
        // pairs than zh->vi; does two hops beat one?
        if (wanted != null && "MLKIT_PIVOT_EN" in wanted) {
            val first = MlKitTranslator()
            val second = MlKitTranslator()
            try {
                for ((pair, group) in samples.groupBy { it.source to it.target }) {
                    for ((t, cfg) in listOf(first to LanguageConfig(pair.first, "en"), second to LanguageConfig("en", pair.second))) {
                        withTimeout(PREPARE_TIMEOUT_MS) {
                            t.prepareModel(cfg).first { it is ModelPrepState.Ready || it is ModelPrepState.Error }
                        }
                    }
                    for (sample in group) {
                        val t0 = System.nanoTime()
                        val out = runCatching { second.translate(first.translate(sample.text)) }
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        results += Result("MLKIT_PIVOT_EN", "CPU", 0, sample.source, sample.target, sample.text,
                            out.getOrDefault(""), sample.reference, ms, ms, out.exceptionOrNull()?.toString())
                        Log.i(TAG, "probe ${results.last()}")
                    }
                }
            } finally {
                first.release()
                second.release()
            }
        }

        val models = LiteRtLmModel.entries.filter { wanted == null || it.name in wanted }
        for (model in models) {
            val contextLines = InstrumentationRegistry.getArguments().getString("probeContext")?.toInt() ?: 2
            val delegates = InstrumentationRegistry.getArguments().getString("probeDelegates")
                ?.split(',')?.map { com.helios.subly.core.model.DelegateType.valueOf(it.trim()) }
                ?: listOf(com.helios.subly.core.model.DelegateType.GPU, com.helios.subly.core.model.DelegateType.CPU)
            val speculative = InstrumentationRegistry.getArguments().getString("probeSpeculative")?.toBoolean()
                ?: model.speculativeDecoding
            val translator = LiteRtLmTranslator(
                context, model = model, contextLines = contextLines, preferredDelegates = delegates,
                speculativeDecoding = speculative,
            )
            val label = if (speculative) "${model.name}+MTP" else model.name
            try {
                for ((pair, group) in samples.groupBy { it.source to it.target }) {
                    val start = System.nanoTime()
                    val state = withTimeout(PREPARE_TIMEOUT_MS) {
                        translator.prepareModel(LanguageConfig(pair.first, pair.second))
                            .first { it is ModelPrepState.Ready || it is ModelPrepState.Error }
                    }
                    val prepareMs = (System.nanoTime() - start) / 1_000_000
                    val delegate = translator.accelerationReport?.active?.name ?: "none"
                    for (sample in group) {
                        results += if (state is ModelPrepState.Error) {
                            Result(label, delegate, prepareMs, sample.source, sample.target, sample.text,
                                "", sample.reference, -1, -1, "prepare failed: ${state.cause}")
                        } else {
                            translateOnce(translator, label, delegate, prepareMs, sample)
                        }
                        Log.i(TAG, "probe ${results.last()}")
                    }
                }
            } finally {
                translator.release()
            }
        }

        TestStorage().openOutputFile("translator_probe.json").bufferedWriter().use {
            it.write(GsonBuilder().setPrettyPrinting().create().toJson(results))
        }
        assertTrue("probe errors", results.none { it.error != null })
    }

    private suspend fun translateOnce(
        translator: LiteRtLmTranslator,
        model: String,
        delegate: String,
        prepareMs: Long,
        sample: Sample,
    ): Result {
        val start = System.nanoTime()
        var firstAt = -1L
        var output = ""
        val error = runCatching {
            translator.openSegment().use { segment ->
                segment.append(sample.text).collect {
                    if (firstAt < 0) firstAt = System.nanoTime()
                    output = it
                }
            }
        }.exceptionOrNull()
        val end = System.nanoTime()
        return Result(
            model = model,
            delegate = delegate,
            prepareMs = prepareMs,
            source = sample.source,
            target = sample.target,
            input = sample.text,
            output = output,
            reference = sample.reference,
            ttftMs = if (firstAt < 0) -1 else (firstAt - start) / 1_000_000,
            totalMs = (end - start) / 1_000_000,
            error = error?.toString(),
        )
    }

    /**
     * FLEURS sentences in all five app languages (`dataset/parallel`), one
     * per `probeDirections` entry (default: the directions the app is used
     * in most). Each carries the reference translation, for chrF on the host.
     */
    private fun floresSamples(): List<Sample> {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("dataset/parallel/flores_parallel.json").bufferedReader().readText()
        val rows = com.google.gson.JsonParser.parseString(json).asJsonArray.map { it.asJsonObject }
        val limit = InstrumentationRegistry.getArguments().getString("probeLimit")?.toInt() ?: rows.size
        val directions = (InstrumentationRegistry.getArguments().getString("probeDirections") ?: DEFAULT_DIRECTIONS)
            .split(',').map { it.trim().split('-').let { (s, t) -> s to t } }
        return directions.flatMap { (s, t) ->
            rows.take(limit).map { Sample(s, t, it[s].asString, it[t].asString) }
        }
    }

    private companion object {
        const val TAG = "TranslatorProbe"
        const val DEFAULT_DIRECTIONS = "zh-vi,en-vi,ja-vi,es-vi,vi-en,zh-en,ja-en,en-zh"
        const val PREPARE_TIMEOUT_MS = 45 * 60 * 1_000L

        val SAMPLES = listOf(
            Sample("ja", "en", "Watashi wa ringo o taberu."),
            Sample("ja", "en", "私はりんごを食べます。"),
            Sample("ja", "en", "合金は基本的に二種類以上の金属の混合物です。"),
            Sample("zh", "en", "合金基本上是两种或多种金属的混合物。"),
            Sample("en", "vi", "The quick brown fox jumps over the lazy dog."),
            Sample("en", "vi", "Alloys are basically a mixture of two or more metals."),
        )

        /** Lines of realworld/zh_dialogue_01 (its subtitles) and FLEURS zh, to Vietnamese. */
        val ZH_VI_SAMPLES = listOf(
            "硬通货随便吃都有。", "真便宜啊！", "你是这个卖家？", "对，是我。走吧。",
            "等会你就说你是我女儿。", "不是，等会，什么叫我是你女儿？", "我妈撒拉今天聚餐，可以带家属。",
            "来，把这个头盔戴上。", "那我跟你坐一起吃吗？", "到时候也可以跟你妈坐一起。",
            "下单后找两个检票员，然后盯着眨眼三次。", "你想干嘛？票呢？",
            "他没有设定削减数额，只是表示将根据中国的经济产出来决定数额。",
            "合金基本上由两种或以上金属混合而成。",
        ).map { Sample("zh", "vi", it) }
    }
}
