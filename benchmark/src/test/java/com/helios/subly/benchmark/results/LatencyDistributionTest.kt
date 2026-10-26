package com.helios.subly.benchmark.results

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyDistributionTest {

    @Test
    fun `nearest-rank percentiles over one to a hundred`() {
        val d = LatencyDistribution.of((1L..100L).shuffled())!!
        assertEquals(100, d.count)
        assertEquals(50L, d.p50)
        assertEquals(90L, d.p90)
        assertEquals(99L, d.p99)
        assertEquals(1L, d.min)
        assertEquals(100L, d.max)
        assertEquals(50.5, d.mean, 1e-9)
    }

    @Test
    fun `small samples put high percentiles on the maximum`() {
        val d = LatencyDistribution.of(listOf(300L, 100L, 200L))!!
        assertEquals(200L, d.p50)
        assertEquals(300L, d.p90)
        assertEquals(300L, d.p99)
    }

    @Test
    fun `a single sample is every percentile`() {
        val d = LatencyDistribution.of(listOf(42L))!!
        assertEquals(42L, d.p50)
        assertEquals(42L, d.p99)
    }

    @Test
    fun `no samples gives no distribution`() {
        assertNull(LatencyDistribution.of(emptyList()))
    }
}
