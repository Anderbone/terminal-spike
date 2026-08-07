package com.yanjiyu.terminalspike.workload

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkloadGeneratorTest {
    @Test
    fun streamingContentIsDeterministicForSeedAndSequence() {
        val first = WorkloadGenerator(42)
        val second = WorkloadGenerator(42)

        repeat(100) { sequence ->
            val left = first.streamingLine(sequence.toLong())
            val right = second.streamingLine(sequence.toLong())
            assertEquals(left.text, right.text)
            assertEquals(left.runs, right.runs)
        }
    }

    @Test
    fun fullScreenContentAndCursorAreDeterministic() {
        val first = WorkloadGenerator(99).fullScreen(123)
        val second = WorkloadGenerator(99).fullScreen(123)

        assertEquals(first.lines.map { it.text }, second.lines.map { it.text })
        assertEquals(first.cursor, second.cursor)
        assertTrue(first.cursor.visible)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun streamingRateCanChangeByStoppingAndRestartingWorkload() = runTest {
        val workload = StreamingWorkload(WorkloadGenerator(7))
        var fastCount = 0
        val fast = launch { workload.run(StreamingRate.LINES_1K) { fastCount += it.size } }
        advanceTimeBy(1_000)
        runCurrent()
        fast.cancelAndJoin()

        var slowCount = 0
        val slow = launch { workload.run(StreamingRate.LINES_10) { slowCount += it.size } }
        advanceTimeBy(1_000)
        runCurrent()
        slow.cancelAndJoin()

        assertTrue(fastCount >= 950)
        assertTrue(slowCount in 9..11)
        val stoppedCount = slowCount
        advanceTimeBy(1_000)
        assertEquals(stoppedCount, slowCount)
    }
}
