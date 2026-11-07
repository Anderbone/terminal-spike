package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import org.junit.Assert.*
import org.junit.Test

/** Controlled alternative, not a change to production viewport/history ownership. */
class TerminalKeyboardLayoutExperimentTest {
    @Test fun fixedGridCannotKeepTopChromeAndBottomInputVisibleAtOnce() {
        val engine = VtTerminalEngine(40, 40)
        val full = engine.accept("\u001b[HHEADER\u001b[40;1HINPUT".toByteArray()).screen
        val visibleRows = 20
        for (first in 0..20) {
            val window = full.subList(first, first + visibleRows).map { it.text }
            assertFalse(window.any { "HEADER" in it } && window.any { "INPUT" in it })
        }
        engine.resize(40, visibleRows)
        val responsive = engine.accept("\u001b[2J\u001b[HHEADER\u001b[20;1HINPUT".toByteArray()).screen
        assertTrue(responsive.first().text.contains("HEADER"))
        assertTrue(responsive.last().text.contains("INPUT"))
    }
}
