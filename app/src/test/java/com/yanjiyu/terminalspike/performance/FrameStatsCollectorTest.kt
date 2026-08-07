package com.yanjiyu.terminalspike.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStatsCollectorTest {
    @Test
    fun summarizesActualDrawRateDurationPercentileAndSlowBuckets() {
        val collector = FrameStatsCollector { }
        repeat(20) { index ->
            val start = index * 16_666_667L
            val duration = if (index == 19) 20_000_000L else 2_000_000L
            collector.recordDraw(start, start + duration)
        }

        val summary = collector.summarize()

        assertFalse(summary.idle)
        assertEquals(60.0, summary.drawsPerSecond, 0.1)
        assertEquals(2.0, summary.p95DrawMs, 0.001)
        assertEquals(1, summary.drawsSlowerThan8Ms)
        assertEquals(1, summary.drawsSlowerThan16Ms)
    }

    @Test
    fun ringWrapsWithoutAllocationGrowthAndIdleIsExplicit() {
        var published = FrameTimingSnapshot()
        val collector = FrameStatsCollector { published = it }
        repeat(500) { index ->
            val start = index * 10_000_000L
            collector.recordDraw(start, start + 1_000_000L)
        }
        assertEquals(100.0, collector.summarize().drawsPerSecond, 0.1)

        collector.publishIdle()

        assertTrue(published.idle)
        assertEquals(0.0, published.drawsPerSecond, 0.0)
    }
}
