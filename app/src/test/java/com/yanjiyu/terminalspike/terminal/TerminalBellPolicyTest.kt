package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.core.model.BellSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBellPolicyTest {
    @Test
    fun profileTogglesApplyOnlyWhileForegroundUiOwnsTheSession() {
        val settings = BellSettings(
            visualBellEnabled = true,
            vibrationBellEnabled = false,
            audibleBellEnabled = true,
        )

        assertEquals(
            TerminalBellEffects(visual = true, audible = true),
            resolveTerminalBellEffects(settings, foregroundUiOwnsSession = true),
        )
        assertTrue(resolveTerminalBellEffects(settings, foregroundUiOwnsSession = false).isEmpty)
        assertTrue(resolveTerminalBellEffects(BellSettings(), foregroundUiOwnsSession = true).isEmpty)
    }

    @Test
    fun limiterCoalescesDuringMinimumIntervalAndAllowsOnlyFourPerRollingSecond() {
        val limiter = TerminalBellRateLimiter()

        assertPresent(limiter.offer(TerminalBellEvent(1, 1), nowMillis = 0), sequence = 1, count = 1)
        assertEquals(TerminalBellRateDecision.Deferred(250), limiter.offer(TerminalBellEvent(2, 1), 100))
        assertEquals(TerminalBellRateDecision.Deferred(250), limiter.offer(TerminalBellEvent(3, 2), 120))
        assertEquals(TerminalBellRateDecision.Deferred(250), limiter.poll(249))
        assertPresent(limiter.poll(250), sequence = 3, count = 3)
        assertPresent(limiter.offer(TerminalBellEvent(4, 1), 500), sequence = 4, count = 1)
        assertPresent(limiter.offer(TerminalBellEvent(5, 1), 750), sequence = 5, count = 1)

        assertEquals(TerminalBellRateDecision.Deferred(1_000), limiter.offer(TerminalBellEvent(6, 1), 900))
        assertEquals(TerminalBellRateDecision.Deferred(1_000), limiter.poll(999))
        assertPresent(limiter.poll(1_000), sequence = 6, count = 1)
        assertFalse(limiter.hasPendingEvent())
    }

    @Test
    fun recreationSuppressionDropsReplayAndSessionReplacementAcceptsFreshSequence() {
        val limiter = TerminalBellRateLimiter()
        assertPresent(limiter.offer(TerminalBellEvent(8, 1), 0), sequence = 8, count = 1)
        limiter.offer(TerminalBellEvent(9, 3), 10)
        assertTrue(limiter.hasPendingEvent())

        limiter.suppressThrough(9)

        assertEquals(TerminalBellRateDecision.None, limiter.poll(1_000))
        assertEquals(TerminalBellRateDecision.None, limiter.offer(TerminalBellEvent(9, 1), 1_000))
        assertEquals(TerminalBellRateDecision.None, limiter.offer(TerminalBellEvent(8, 1), 1_000))
        assertPresent(limiter.offer(TerminalBellEvent(10, 1), 1_000), sequence = 10, count = 1)

        limiter.resetForSessionReplacement()
        assertPresent(limiter.offer(TerminalBellEvent(1, 1), 1_000), sequence = 1, count = 1)
    }

    private fun assertPresent(
        decision: TerminalBellRateDecision,
        sequence: Long,
        count: Int,
    ) {
        val event = (decision as TerminalBellRateDecision.Present).event
        assertEquals(sequence, event.sequence)
        assertEquals(count, event.count)
    }
}
