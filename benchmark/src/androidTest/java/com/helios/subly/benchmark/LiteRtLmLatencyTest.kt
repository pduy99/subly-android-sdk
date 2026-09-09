package com.helios.subly.benchmark

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.helios.subly.core.model.LanguageConfig
import com.helios.subly.core.model.ModelPrepState
import com.helios.subly.translator.litertlm.LiteRtLmTranslator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an on-device LLM actually costs per caption on the benchmark device.
 *
 * The accuracy benchmark cannot answer this: it runs the whole pipeline, so a
 * slow translator shows up as a clip that timed out rather than as a number.
 * This isolates provisioning, engine init, and per-caption generation, and it
 * decides whether the full matrix is runnable at all — `SublySession`
 * translates on the coroutine that collects ASR results, so a translator that
 * takes longer than a sentence takes to speak applies backpressure all the
 * way to audio capture.
 *
 * Not part of the accuracy matrix, and not run by `runBenchmark`. Invoke it
 * directly:
 * ```
 * ./gradlew :benchmark:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.helios.subly.benchmark.LiteRtLmLatencyTest
 * ```
 */
class LiteRtLmLatencyTest {

    @Test
    fun measureTranslateLatency() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val translator = LiteRtLmTranslator(context)

            try {
                val prepareStartMs = SystemClock.elapsedRealtime()
                var lastLoggedPercent = -1
                val prepared = withTimeout(PREPARE_TIMEOUT_MS) {
                    translator.prepareModel(LanguageConfig(source = "en", target = "vi"))
                        .onEach { state ->
                            if (state is ModelPrepState.Preparing) {
                                val percent = (state.progress * 100).toInt()
                                if (percent >= lastLoggedPercent + 5) {
                                    lastLoggedPercent = percent
                                    Log.i(TAG, "prepare progress=$percent%")
                                }
                            }
                        }
                        .first { it is ModelPrepState.Ready || it is ModelPrepState.Error }
                }
                val prepareMs = SystemClock.elapsedRealtime() - prepareStartMs
                Log.i(TAG, "prepare ms=$prepareMs state=$prepared")
                assertTrue(
                    "prepare failed: $prepared",
                    prepared is ModelPrepState.Ready,
                )

                // Real caption shapes: the sentence lengths the SDK's own
                // assembly emits, not a one-word smoke test.
                val samples = listOf(
                    "The quick brown fox jumps over the lazy dog.",
                    "Rainfall in the region has decreased by roughly a third " +
                        "over the past decade.",
                    "He said the new policy would take effect at the start of " +
                        "next year, once parliament approves it.",
                )

                var totalMs = 0L
                samples.forEachIndexed { index, source ->
                    val startMs = SystemClock.elapsedRealtime()
                    val out = runCatching { translator.translate(source) }
                    val ms = SystemClock.elapsedRealtime() - startMs
                    totalMs += ms
                    // Length only: the output is a translation of speech-shaped
                    // text, and the SDK never logs transcript content.
                    Log.i(
                        TAG,
                        "translate i=$index ms=$ms src_words=${source.split(" ").size} " +
                            "src_len=${source.length} dst_len=${out.getOrNull()?.length ?: -1} " +
                            "ok=${out.isSuccess} err=${out.exceptionOrNull()?.message ?: ""}",
                    )
                }
                Log.i(TAG, "translate mean_ms=${totalMs / samples.size} over ${samples.size} calls")
            } finally {
                translator.release()
            }
        }
    }

    private companion object {
        const val TAG = "LiteRtLmLatency"

        /** First run downloads ~1.6 GB and memory-maps it. */
        const val PREPARE_TIMEOUT_MS = 45 * 60 * 1_000L
    }
}
