package com.yanjiyu.terminalspike.connection

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
}
