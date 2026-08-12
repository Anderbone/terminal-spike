package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.terminal.TerminalCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCursorRenderingTest {
    @Test
    fun remoteDecscusrPresentationOverridesBothProfileValues() {
        val profileCursor = TerminalCursor(visible = true)
        assertEquals(
            CursorStyle.UNDERLINE,
            TerminalCursorRendering.resolveStyle(profileCursor, CursorStyle.UNDERLINE),
        )
        assertTrue(TerminalCursorRendering.resolveBlink(profileCursor, profileBlink = true))

        val remoteCursor = TerminalCursor(
            visible = true,
            styleOverride = CursorStyle.BEAM,
            blinkOverride = false,
        )
        assertEquals(
            CursorStyle.BEAM,
            TerminalCursorRendering.resolveStyle(remoteCursor, CursorStyle.UNDERLINE),
        )
        assertFalse(TerminalCursorRendering.resolveBlink(remoteCursor, profileBlink = true))
    }

    @Test
    fun eachCursorShapeStaysInsideItsExactMonospaceCell() {
        assertEquals(2f, TerminalCursorRendering.underlineThickness(20f, density = 1f), 0f)
        assertEquals(4f, TerminalCursorRendering.beamWidth(8f, density = 2f), 0f)
        assertEquals(1f, TerminalCursorRendering.beamWidth(1f, density = 3f), 0f)
        assertEquals(1f, TerminalCursorRendering.underlineThickness(1f, density = 3f), 0f)
    }
}
