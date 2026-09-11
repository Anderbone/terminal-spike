package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.terminal.model.TerminalViewport
import org.junit.Assert.*
import org.junit.Test

class TerminalGridGeometryTest {
    @Test fun sparePixelsDoNotExposeAnOldHistoryRowAboveTheLiveHeader() {
        val grid = requireNotNull(terminalGridGeometry(816, 817, 8f, 5f, 10f, 20f))
        assertEquals(TerminalGridGeometry(80, 40, 800), grid)
        val oldGeometry = TerminalViewport().apply {
            updateGeometry(807, 20f)
            updateContent(1040, 1L)
        }
        assertEquals(999, oldGeometry.visibleRows(0).first)
        val viewport = TerminalViewport().apply {
            updateGeometry(grid.heightPx, 20f)
            updateContent(1040, 1L)
        }
        assertEquals(1000, viewport.visibleRows(0).first)
        assertEquals(20000f, viewport.scrollY, 0f)
        viewport.scrollBy(-3.25f)
        assertEquals(19996.75f, viewport.scrollY, 0f)
    }

    @Test fun anUnmeasuredOrHiddenViewDoesNotSendAOneCellTerminalSize() {
        assertNull(terminalGridGeometry(0, 0, 8f, 5f, 10f, 20f))
        assertNull(terminalGridGeometry(816, 0, 8f, 5f, 10f, 20f))
        assertNull(terminalGridGeometry(16, 817, 8f, 5f, 10f, 20f))
        assertNull(terminalGridGeometry(816, 817, 8f, 5f, Float.NaN, 20f))
    }

    @Test fun keyboardAndFullHeightUseTheirActualCompleteRows() {
        assertEquals(40, terminalGridGeometry(816, 817, 8f, 5f, 10f, 20f)!!.rows)
        assertEquals(20, terminalGridGeometry(816, 417, 8f, 5f, 10f, 20f)!!.rows)
        assertEquals(40, terminalGridGeometry(816, 817, 8f, 5f, 10f, 20f)!!.rows)
    }
}
