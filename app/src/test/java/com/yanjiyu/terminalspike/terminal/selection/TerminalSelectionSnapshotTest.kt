package com.yanjiyu.terminalspike.terminal.selection

import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import org.junit.Assert.*
import org.junit.Test

class TerminalSelectionSnapshotTest {
    @Test fun boundsLargeHistoryAndRetainsVolatileRowsAfterSourceRedraw() {
        var revision = 1L
        val source = object : TerminalSelectionSource {
            override val selectionLineCount = 20_000
            override val selectionColumnCount = 80
            override fun selectionLineAt(index: Int) = index.takeIf { it in 0 until selectionLineCount }?.let {
                TerminalSelectableLine(TerminalLineAnchor(TerminalLineSpace.VOLATILE_SCREEN, revision, it),
                    TerminalLine.plain("row$it revision$revision"))
            }
            override fun selectionIndexOf(anchor: TerminalLineAnchor) = anchor.volatileRow.takeIf { anchor.id == revision }
        }
        val snapshot = requireNotNull(TerminalSelectionSnapshot.capture(source, 10_000))
        assertEquals(4096, snapshot.selectionLineCount)
        val row = 10_000 - snapshot.firstSourceRow
        val original = requireNotNull(snapshot.selectionLineAt(row))
        revision++
        assertNull(source.selectionIndexOf(original.anchor))
        assertEquals(row, snapshot.selectionIndexOf(original.anchor))
        assertEquals("row10000 revision1", snapshot.selectionLineAt(row)?.line?.text)
    }
}
