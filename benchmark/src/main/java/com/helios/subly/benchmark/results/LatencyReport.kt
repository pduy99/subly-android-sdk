package com.helios.subly.benchmark.results

import kotlin.math.ceil

/**
 * Distribution of one latency metric across a run.
 *
 * Percentiles use the nearest-rank method: p is the smallest sample with at
 * least p% of samples at or below it. With few samples the high percentiles
 * collapse onto the maximum, so [count] is always reported next to them: a
 * p99 over 12 samples is the slowest of 12, nothing more.
 */
data class LatencyDistribution(
    val count: Int,
    val min: Long,
    val p50: Long,
    val p90: Long,
    val p99: Long,
    val max: Long,
    val mean: Double,
) {
    companion object {
        fun of(samples: List<Long>): LatencyDistribution? {
            if (samples.isEmpty()) return null
            val sorted = samples.sorted()
            return LatencyDistribution(
                count = sorted.size,
                min = sorted.first(),
                p50 = percentile(sorted, 50.0),
                p90 = percentile(sorted, 90.0),
                p99 = percentile(sorted, 99.0),
                max = sorted.last(),
                mean = sorted.average(),
            )
        }

        /** Nearest-rank percentile of an ascending list. */
        fun percentile(sorted: List<Long>, p: Double): Long {
            require(sorted.isNotEmpty())
            require(p in 0.0..100.0)
            val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

/** One delegate attempt as recorded in the report. */
data class DelegateAttemptRecord(
    val delegate: String,
    val succeeded: Boolean,
    val initMs: Long,
    val failure: String?,
)

data class DelegateRecord(
    val component: String,
    val active: String,
    val hardwareAccelerated: Boolean,
    val fallback: Boolean,
    val attempts: List<DelegateAttemptRecord>,
)

/** Latency samples and outcome for one clip. */
data class ClipLatency(
    val clipId: String,
    val language: String,
    val target: String,
    val durationSec: Int,
    val finals: Int,
    val forcedCommits: Int,
    val ttftMs: List<Long>,
    val pieceMs: List<Long>,
    val finalLatencyMs: List<Long>,
    val speechEndToTranslationMs: List<Long>,
    val speechOnsetToFirstTranslationMs: List<Long>,
    val error: String? = null,
)

data class MemoryRecord(
    /** Kernel-tracked peak resident set (VmHWM), file-backed model pages included. */
    val peakRssMb: Double,
    /** Highest sampled proportional set size. */
    val peakPssMb: Double,
    /** Highest sampled native-heap allocation (excludes memory-mapped model files). */
    val peakNativeHeapMb: Double,
    val samples: Int,
)

/** Thresholds the run is compared against; reported, never asserted here. */
data class LatencyTargets(
    val ttftMs: Long,
    val speechEndToTranslationMs: Long,
)

data class LatencyRun(
    val runId: String,
    val device: String,
    val soc: String,
    val sdkInt: Int,
    val gitSha: String,
    val asrEngine: String,
    val delegates: List<DelegateRecord>,
    val targets: LatencyTargets,
    val summary: Map<String, LatencyDistribution?>,
    val targetsMet: Map<String, Boolean?>,
    val memory: MemoryRecord,
    val clips: List<ClipLatency>,
)
