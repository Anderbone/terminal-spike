package com.yanjiyu.terminalspike.terminal.view

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalClipboardClearSchedulerTest {
    @Test
    fun offAndEverySupportedDelayAreExact() = runTest {
        val scheduler = TerminalClipboardClearScheduler(backgroundScope)
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
        val scheduler = TerminalClipboardClearScheduler(backgroundScope)
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
        val scheduler = TerminalClipboardClearScheduler(backgroundScope)
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

    private fun token(value: String) = TerminalClipboardToken(value)
}

private class RecordingClearTarget : TerminalClipboardClearTarget {
    val cleared = mutableListOf<String>()

    override fun clearIfCurrent(token: TerminalClipboardToken): Boolean {
        cleared += token.value
        return true
    }
}
