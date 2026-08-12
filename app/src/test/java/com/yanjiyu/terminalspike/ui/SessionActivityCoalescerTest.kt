package com.yanjiyu.terminalspike.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionActivityCoalescerTest {
    @Test
    fun inboundAndAcceptedOutboundActivityShareOneFifteenSecondWindow() {
        val coalescer = SessionActivityCoalescer(
            initialActivityAtEpochMillis = 1_000L,
            publishIntervalMillis = 15_000L,
        )

        assertNull(coalescer.recordInbound(10_000L))
        assertEquals(16_000L, coalescer.recordAcceptedOutbound(16_000L))
        assertNull(coalescer.recordInbound(30_999L))
        assertEquals(31_000L, coalescer.recordAcceptedOutbound(31_000L))
        assertEquals(31_000L, coalescer.lastPublishedAtEpochMillis)
    }

    @Test
    fun rejectedOutboundActivityNeverEntersTheCoalescer() {
        val coalescer = SessionActivityCoalescer(
            initialActivityAtEpochMillis = 1_000L,
            publishIntervalMillis = 15_000L,
        )

        // A rejected enqueue does not call recordAcceptedOutbound. The next accepted write owns
        // the publication timestamp instead of inheriting activity from the rejected attempt.
        assertEquals(20_000L, coalescer.recordAcceptedOutbound(20_000L))
        assertEquals(20_000L, coalescer.lastPublishedAtEpochMillis)
    }

    @Test
    fun forcePublishesStateTransitionsWithoutMovingTimeBackwards() {
        val coalescer = SessionActivityCoalescer(
            initialActivityAtEpochMillis = 20_000L,
            publishIntervalMillis = 15_000L,
        )

        assertEquals(20_000L, coalescer.force(10_000L))
        assertEquals(25_000L, coalescer.force(25_000L))
        assertEquals(25_000L, coalescer.lastPublishedAtEpochMillis)
    }
}
