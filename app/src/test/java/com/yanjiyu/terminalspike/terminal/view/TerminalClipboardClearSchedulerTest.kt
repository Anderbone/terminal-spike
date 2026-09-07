package com.yanjiyu.terminalspike.terminal.view

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalClipboardClearSchedulerTest {
    @Test
    fun offAndEverySupportedDelayAreExact() = runTest {
        val scheduler = TerminalClipboardClearScheduler(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        val target = RecordingClearTarget()

        scheduler.schedule(target, token("off"))
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(emptyList<String>(), target.cleared)

        listOf(30, 60, 300).forEach { seconds ->
            val expected = "delay-$seconds"
            scheduler.updateDelaySeconds(seconds)
            scheduler.schedule(target, token(expected))
            advanceTimeBy(seconds * 1_000L - 1L)
            runCurrent()
            assertEquals(expected !in target.cleared, true)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(expected, target.cleared.last())
        }
    }

    @Test
    fun newerWriteReplacesOlderTimerEvenWhenTextWouldHaveMatched() = runTest {
        val scheduler = TerminalClipboardClearScheduler(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        val target = RecordingClearTarget()
        scheduler.updateDelaySeconds(30)

        scheduler.schedule(target, token("older"))
        advanceTimeBy(10_000)
        scheduler.schedule(target, token("newer"))
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(emptyList<String>(), target.cleared)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("newer"), target.cleared)
    }

    @Test
    fun changingPreferenceAndClosingProcessScopeCancelPendingClear() = runTest {
        val scheduler = TerminalClipboardClearScheduler(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        val target = RecordingClearTarget()
        scheduler.updateDelaySeconds(30)

        scheduler.schedule(target, token("preference-change"))
        scheduler.updateDelaySeconds(0)
        advanceTimeBy(30_000)
        runCurrent()

        scheduler.updateDelaySeconds(30)
        scheduler.schedule(target, token("process-close"))
        scheduler.close()
        advanceTimeBy(30_000)
        runCurrent()

        scheduler.schedule(target, token("after-close"))
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(emptyList<String>(), target.cleared)
    }

    @Test
    fun expiredTemporaryFailureRetriesWithTheCurrentTarget() = runTest {
        val scheduler = TerminalClipboardClearScheduler(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        val unavailable = RecordingClearTarget(
            result = TerminalClipboardClearResult.TEMPORARILY_UNAVAILABLE,
        )
        val resumed = RecordingClearTarget()
        scheduler.updateDelaySeconds(30)
        scheduler.schedule(unavailable, token("retry"))

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf("retry"), unavailable.attempted)
        assertEquals(emptyList<String>(), unavailable.cleared)

        scheduler.retryExpiredClear(resumed)
        runCurrent()
        assertEquals(listOf("retry"), resumed.cleared)

        scheduler.retryExpiredClear(resumed)
        runCurrent()
        assertEquals(listOf("retry"), resumed.cleared)
    }

    @Test
    fun tokenMismatchFinishesInsteadOfRetryingForever() = runTest {
        val scheduler = TerminalClipboardClearScheduler(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        val mismatch = RecordingClearTarget(
            result = TerminalClipboardClearResult.NO_LONGER_CURRENT,
        )
        val later = RecordingClearTarget()
        scheduler.updateDelaySeconds(30)
        scheduler.schedule(mismatch, token("foreign"))

        advanceTimeBy(30_000)
        runCurrent()
        scheduler.retryExpiredClear(later)
        runCurrent()

        assertEquals(listOf("foreign"), mismatch.attempted)
        assertEquals(emptyList<String>(), later.attempted)
    }

    private fun token(value: String) = TerminalClipboardToken(value)
}

private class RecordingClearTarget(
    private val result: TerminalClipboardClearResult = TerminalClipboardClearResult.CLEARED,
) : TerminalClipboardClearTarget {
    val attempted = mutableListOf<String>()
    val cleared = mutableListOf<String>()

    override fun tryClearIfCurrent(token: TerminalClipboardToken): TerminalClipboardClearResult {
        attempted += token.value
        if (result == TerminalClipboardClearResult.CLEARED) cleared += token.value
        return result
    }
}
