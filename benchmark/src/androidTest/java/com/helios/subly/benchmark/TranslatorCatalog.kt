package com.helios.subly.benchmark

import android.content.Context
import com.helios.subly.translator.api.SublyTranslator
import com.helios.subly.translator.litertlm.LiteRtLmTranslator
import com.helios.subly.translator.mlkit.MlKitTranslator

/**
 * The translator axis of the benchmark — of which there is exactly one per
 * run, unlike the engine axis.
 *
 * Selected with
 * `-Pandroid.testInstrumentationRunnerArguments.benchmarkTranslator=mlkit`.
 * It is a switch rather than a matrix dimension on purpose: the LLM
 * translator is seconds per caption where ML Kit is milliseconds, so running
 * both in one pass would multiply an already hour-long matrix by far more
 * than two. Compare two runs instead — the recorded `translator` field in
 * results.json says which is which.
 */
enum class BenchmarkTranslator(
    val id: String,
    val factory: (Context) -> SublyTranslator,
) {
    /** ~30 MB per pair, milliseconds per caption. */
    MLKIT("mlkit", { MlKitTranslator() }),

    /** ~1.6 GB on-device LLM; finals only (partials carry source text). */
    LITERTLM("litertlm", { context -> LiteRtLmTranslator(context) });

    companion object {
        val Default: BenchmarkTranslator = LITERTLM

        fun of(id: String?): BenchmarkTranslator {
            if (id.isNullOrBlank()) return Default
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown benchmarkTranslator '$id'. Known: ${entries.map { it.id }}."
                )
        }
    }
}
