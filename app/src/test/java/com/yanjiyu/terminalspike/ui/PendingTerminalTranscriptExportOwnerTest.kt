package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.terminal.TerminalTranscriptSnapshot
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTerminalTranscriptExportOwnerTest {
    @Test
    fun retainedOwnerKeepsOneSnapshotAndConsumesItExactlyOnce() {
        val owner = PendingTerminalTranscriptExportOwner()
        val first = TerminalTranscriptSnapshot.fromLines(
            lines = listOf(TerminalLine.plain("first")),
            sourceId = 1L,
        )
        val second = TerminalTranscriptSnapshot.fromLines(
            lines = listOf(TerminalLine.plain("second")),
            sourceId = 2L,
        )

        assertTrue(owner.offer(first))
        assertFalse(owner.offer(second))
        assertSame(first, owner.consume())
        assertNull(owner.consume())

        assertTrue(owner.offer(second))
        owner.clear()
        assertNull(owner.consume())
    }
}
