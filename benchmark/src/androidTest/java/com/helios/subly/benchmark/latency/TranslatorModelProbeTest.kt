package com.helios.subly.benchmark.latency

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import com.google.gson.GsonBuilder
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.translator.litertlm.LiteRtLmModel
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
 */
class TranslatorModelProbeTest {

    data class Sample(val source: String, val target: String, val text: String)

    data class Result(
        val model: String,
        val delegate: String,
        val prepareMs: Long,
        val source: String,
        val target: String,
        val input: String,
        val output: String,
        val ttftMs: Long,
        val totalMs: Long,
        val error: String?,
    )

    @Test
    fun probeModels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wanted = InstrumentationRegistry.getArguments().getString("probeModels")
            ?.split(',')?.map { it.trim() }?.toSet()
        val models = LiteRtLmModel.entries.filter { wanted == null || it.name in wanted }
        val results = ArrayList<Result>()

        for (model in models) {
            val translator = LiteRtLmTranslator(context, model = model)
            try {
                for ((pair, samples) in SAMPLES.groupBy { it.source to it.target }) {
                    val start = System.nanoTime()
                    val state = withTimeout(PREPARE_TIMEOUT_MS) {
                        translator.prepareModel(LanguageConfig(pair.first, pair.second))
                            .first { it is ModelPrepState.Ready || it is ModelPrepState.Error }
                    }
                    val prepareMs = (System.nanoTime() - start) / 1_000_000
                    val delegate = translator.accelerationReport?.active?.name ?: "none"
                    for (sample in samples) {
                        results += if (state is ModelPrepState.Error) {
                            Result(model.name, delegate, prepareMs, sample.source, sample.target, sample.text,
                                "", -1, -1, "prepare failed: ${state.cause}")
                        } else {
                            translateOnce(translator, model, delegate, prepareMs, sample)
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
        model: LiteRtLmModel,
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
            model = model.name,
            delegate = delegate,
            prepareMs = prepareMs,
            source = sample.source,
            target = sample.target,
            input = sample.text,
            output = output,
            ttftMs = if (firstAt < 0) -1 else (firstAt - start) / 1_000_000,
            totalMs = (end - start) / 1_000_000,
            error = error?.toString(),
        )
    }

    private companion object {
        const val TAG = "TranslatorProbe"
        const val PREPARE_TIMEOUT_MS = 45 * 60 * 1_000L

        val SAMPLES = listOf(
            Sample("ja", "en", "Watashi wa ringo o taberu."),
            Sample("ja", "en", "私はりんごを食べます。"),
            Sample("ja", "en", "合金は基本的に二種類以上の金属の混合物です。"),
            Sample("zh", "en", "合金基本上是两种或多种金属的混合物。"),
            Sample("en", "vi", "The quick brown fox jumps over the lazy dog."),
            Sample("en", "vi", "Alloys are basically a mixture of two or more metals."),
        )
    }
}
