package com.yanjiyu.terminalspike.performance

import android.view.Choreographer
import kotlin.math.ceil

class FrameStatsCollector(
    private val onSummary: (FrameTimingSnapshot) -> Unit,
) : Choreographer.FrameCallback {
    private val samplesNanos = LongArray(SAMPLE_CAPACITY)
    private val sortedScratch = LongArray(SAMPLE_CAPACITY)
    private var sampleCount = 0
    private var writeIndex = 0
    private var previousFrameNanos = 0L
    private var lastPublishNanos = 0L
    private var running = false

    fun start() {
        if (running) return
        running = true
        previousFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (previousFrameNanos != 0L) {
            samplesNanos[writeIndex] = frameTimeNanos - previousFrameNanos
            writeIndex = (writeIndex + 1) % SAMPLE_CAPACITY
            sampleCount = (sampleCount + 1).coerceAtMost(SAMPLE_CAPACITY)
        }
        previousFrameNanos = frameTimeNanos

        if (sampleCount > 0 && frameTimeNanos - lastPublishNanos >= PUBLISH_INTERVAL_NANOS) {
            lastPublishNanos = frameTimeNanos
            onSummary(summarize())
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun summarize(): FrameTimingSnapshot {
        var total = 0L
        var slow8 = 0
        var slow16 = 0
        repeat(sampleCount) { index ->
            val value = samplesNanos[index]
            sortedScratch[index] = value
            total += value
            if (value > FRAME_120_HZ_NANOS) slow8 += 1
            if (value > FRAME_60_HZ_NANOS) slow16 += 1
        }
        sortedScratch.sort(0, sampleCount)
        val averageNanos = total.toDouble() / sampleCount
        val p95Index = (ceil(sampleCount * 0.95).toInt() - 1).coerceIn(0, sampleCount - 1)
        return FrameTimingSnapshot(
            fps = if (averageNanos == 0.0) 0.0 else NANOS_PER_SECOND / averageNanos,
            averageFrameMs = averageNanos / NANOS_PER_MILLISECOND,
            p95FrameMs = sortedScratch[p95Index] / NANOS_PER_MILLISECOND,
            slowerThan8Ms = slow8,
            slowerThan16Ms = slow16,
        )
    }

    companion object {
        private const val SAMPLE_CAPACITY = 240
        private const val PUBLISH_INTERVAL_NANOS = 500_000_000L
        private const val FRAME_120_HZ_NANOS = 8_333_333L
        private const val FRAME_60_HZ_NANOS = 16_666_667L
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}
