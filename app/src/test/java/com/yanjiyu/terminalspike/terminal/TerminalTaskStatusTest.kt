package com.yanjiyu.terminalspike.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalTaskStatusTest {
    @Test
    fun workEndsOnlyWhenTheTitleExplicitlyReturnsToReady() {
        val tracker = TerminalTaskTracker()
        assertEquals(TerminalTaskStatus(), tracker.observeTitle("project"))
        assertTrue(tracker.observeTitle("⠋ project").running)
        repeat(20) { assertTrue(tracker.observeTitle(null).running) }
        assertTrue(tracker.observeTitle("⠹ project").running)
        assertEquals(TerminalTaskStatus(finished = true), tracker.observeTitle("project"))
        tracker.acknowledge()
        assertFalse(tracker.observeTitle("project").finished)
        assertTrue(tracker.observeTitle("⠋ project").running)
        assertTrue(tracker.observeTitle("project").finished)
    }

    @Test
    fun genericBellsAndApprovalAreAttentionNotCompletion() {
        val tracker = TerminalTaskTracker()
        tracker.observeTitle("⠋ project")
        assertFalse(tracker.attention().finished)
        assertEquals(TerminalTaskStatus(needsAttention = true), tracker.observeTitle("🔔 project"))
        assertFalse(tracker.observeTitle("project").finished)
    }

    @Test
    fun unrelatedTitlesAndDisconnectNeverClaimCompletion() {
        val tracker = TerminalTaskTracker()
        tracker.observeTitle("⠋ project")
        assertFalse(tracker.observeTitle("shell").finished)
        assertFalse(TerminalTaskStatus(running = true).disconnected().finished)
        assertFalse(TerminalTaskStatus(running = true).disconnected().running)
    }

    @Test
    fun lostObservationStopsSpinnerWithoutInventingCompletionAndCanResume() {
        val tracker = TerminalTaskTracker()
        tracker.observeTitle("⠋ work")
        assertEquals(TerminalTaskStatus(), tracker.invalidateObservation())
        assertFalse(tracker.observeTitle("work").finished)
        assertTrue(tracker.observeTitle("⠋ work").running)
    }

    @Test
    fun explicitStatusAndSessionAggregationPreserveOtherPaneAlerts() {
        val tracker = TerminalTaskTracker()
        assertTrue(tracker.observeTitle("Working ⠋").running)
        assertTrue(tracker.observeTitle("Ready").finished)
        assertEquals(
            TerminalTaskStatus(running = true, finished = true, needsAttention = true),
            tracker.status + TerminalTaskStatus(running = true, needsAttention = true),
        )
    }
}
