package com.yanjiyu.terminalspike.connection

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPlanTest {
    private val policy = RemoteSessionReliabilityPolicy(
        reconnectEnabled = true,
        reconnectMaxAttempts = 5,
        initialRetryDelayMillis = 1_000L,
        maximumRetryDelayMillis = 8_000L,
    )
    private val plan = ReconnectPlan(policy)

    @Test
    fun exponentialDelayIsBoundedAndAttemptCountIsOneBased() {
        assertEquals(ReconnectStep.RetryAfter(1, 1_000L), plan.next(0, true, false))
        assertEquals(ReconnectStep.RetryAfter(2, 2_000L), plan.next(1, true, false))
        assertEquals(ReconnectStep.RetryAfter(4, 8_000L), plan.next(3, true, false))
        assertEquals(ReconnectStep.RetryAfter(5, 8_000L), plan.next(4, true, false))
        assertEquals(ReconnectStep.Exhausted, plan.next(5, true, false))
    }

    @Test
    fun offlineStatePausesWithoutConsumingAnAttempt() {
        assertEquals(ReconnectStep.WaitForNetwork(3), plan.next(2, false, false))
        assertEquals(ReconnectStep.RetryAfter(3, 4_000L), plan.next(2, true, false))
    }

    @Test
    fun intentionalDisconnectAlwaysCancelsBeforeConnectivityOrBackoff() {
        assertEquals(ReconnectStep.Cancelled, plan.next(0, true, true))
        assertEquals(ReconnectStep.Cancelled, plan.next(4, false, true))
    }

    @Test
    fun disabledPolicyNeverSchedules() {
        val disabled = ReconnectPlan(policy.copy(reconnectEnabled = false))

        assertEquals(ReconnectStep.Exhausted, disabled.next(0, true, false))
    }

    @Test
    fun oddDelayCeilingDoesNotRoundThePreviousDoublingUp() {
        val plan = ReconnectPlan(policy.copy(initialRetryDelayMillis = 2L, maximumRetryDelayMillis = 5L))

        assertEquals(ReconnectStep.RetryAfter(1, 2L), plan.next(0, true, false))
        assertEquals(ReconnectStep.RetryAfter(2, 4L), plan.next(1, true, false))
        assertEquals(ReconnectStep.RetryAfter(3, 5L), plan.next(2, true, false))
    }

    @Test
    fun doublingImmediatelyBelowLongMaximumRemainsExactBeforeSaturating() {
        val plan = ReconnectPlan(policy.copy(
            initialRetryDelayMillis = Long.MAX_VALUE / 2L,
            maximumRetryDelayMillis = Long.MAX_VALUE,
        ))

        assertEquals(ReconnectStep.RetryAfter(2, Long.MAX_VALUE - 1L), plan.next(1, true, false))
        assertEquals(ReconnectStep.RetryAfter(3, Long.MAX_VALUE), plan.next(2, true, false))
    }

    @Test
    fun longRetryBudgetsReachTheCeilingInsteadOfFreezingBelowIt() {
        val plan = ReconnectPlan(policy.copy(
            reconnectMaxAttempts = 100,
            initialRetryDelayMillis = 1L,
            maximumRetryDelayMillis = Long.MAX_VALUE,
        ))

        assertEquals(ReconnectStep.RetryAfter(63, 1L shl 62), plan.next(62, true, false))
        assertEquals(ReconnectStep.RetryAfter(64, Long.MAX_VALUE), plan.next(63, true, false))
        assertEquals(ReconnectStep.RetryAfter(100, Long.MAX_VALUE), plan.next(99, true, false))
    }

    @Test
    fun boundedDelaysMatchAnIndependentIntegerArithmeticOracle() {
        for (initial in 1L..8L) for (ceiling in initial..19L) {
            val plan = ReconnectPlan(policy.copy(
                reconnectMaxAttempts = 100,
                initialRetryDelayMillis = initial,
                maximumRetryDelayMillis = ceiling,
            ))
            for (completed in 0..99) {
                val expected = BigInteger.valueOf(initial).shiftLeft(completed)
                    .min(BigInteger.valueOf(ceiling)).longValueExact()
                assertEquals(
                    "initial=$initial ceiling=$ceiling completed=$completed",
                    ReconnectStep.RetryAfter(completed + 1, expected),
                    plan.next(completed, true, false),
                )
            }
        }
    }
}
