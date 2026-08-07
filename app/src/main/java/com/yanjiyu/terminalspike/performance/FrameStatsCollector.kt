package com.yanjiyu.terminalspike.performance

import kotlin.math.ceil

/** Fixed-memory collector for actual terminal renderer draws and their CPU duration. */
class FrameStatsCollector(
    private val onSummary: (FrameTimingSnapshot) -> Unit,
) {
    private val startsNanos = LongArray(SAMPLE_CAPACITY)
    private val durationsNanos = LongArray(SAMPLE_CAPACITY)
    private val sortedScratch = LongArray(SAMPLE_CAPACITY)
    private var sampleCount = 0
    private var writeIndex = 0
    private var lastPublishNanos = 0L

    fun recordDraw(startNanos: Long, endNanos: Long) {
        require(endNanos >= startNanos) { "Draw end must not precede start." }
        startsNanos[writeIndex] = startNanos
        durationsNanos[writeIndex] = endNanos - startNanos
        writeIndex = (writeIndex + 1) % SAMPLE_CAPACITY
        sampleCount = (sampleCount + 1).coerceAtMost(SAMPLE_CAPACITY)
        if (endNanos - lastPublishNanos >= PUBLISH_INTERVAL_NANOS) {
            lastPublishNanos = endNanos
            onSummary(summarize())
        }
    }

    fun publishIdle() {
        onSummary(FrameTimingSnapshot(idle = true))
    }

    internal fun summarize(): FrameTimingSnapshot {
        if (sampleCount == 0) return FrameTimingSnapshot(idle = true)
        var totalDuration = 0L
        var slowerThan8 = 0
        var slowerThan16 = 0
        var oldestStart = Long.MAX_VALUE
        var newestStart = Long.MIN_VALUE
        repeat(sampleCount) { index ->
            val duration = durationsNanos[index]
            val start = startsNanos[index]
            sortedScratch[index] = duration
            totalDuration += duration
            oldestStart = minOf(oldestStart, start)
            newestStart = maxOf(newestStart, start)
            if (duration > FRAME_120_HZ_NANOS) slowerThan8 += 1
            if (duration > FRAME_60_HZ_NANOS) slowerThan16 += 1
        }
        sortedScratch.sort(0, sampleCount)
        val averageDuration = totalDuration.toDouble() / sampleCount
        val p95Index = (ceil(sampleCount * 0.95).toInt() - 1).coerceIn(0, sampleCount - 1)
        val span = newestStart - oldestStart
        return FrameTimingSnapshot(
            drawsPerSecond = if (sampleCount < 2 || span <= 0L) {
                0.0
            } else {
                (sampleCount - 1) * NANOS_PER_SECOND / span
            },
            averageDrawMs = averageDuration / NANOS_PER_MILLISECOND,
            p95DrawMs = sortedScratch[p95Index] / NANOS_PER_MILLISECOND,
            drawsSlowerThan8Ms = slowerThan8,
            drawsSlowerThan16Ms = slowerThan16,
            idle = false,
        )
    }

    companion object {
        const val IDLE_DELAY_MS = 1_000L
        private const val SAMPLE_CAPACITY = 240
        private const val PUBLISH_INTERVAL_NANOS = 500_000_000L
        private const val FRAME_120_HZ_NANOS = 8_333_333L
        private const val FRAME_60_HZ_NANOS = 16_666_667L
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}
