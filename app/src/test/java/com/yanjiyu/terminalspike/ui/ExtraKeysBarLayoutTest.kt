package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ExtraKeysBarLayoutTest {
    @Test
    fun configuredOrderIsPreservedAcrossBoundedTwoRowPages() {
        val configured = listOf(
            TerminalExtraKey.CTRL_W,
            TerminalExtraKey.ESC,
            TerminalExtraKey.F12,
            TerminalExtraKey.ALT,
            TerminalExtraKey.LEFT,
            TerminalExtraKey.CTRL_C,
            TerminalExtraKey.HOME,
        )

        val pages = terminalShortcutPages(configured, columnCount = 3)

        assertEquals(listOf(6, 2), pages.map { it.size })
        assertEquals(configured, pages.flatten().filterNotNull())
        assertNull(pages.last().last())
    }

    @Test
    fun shippedDefaultIsExactlyOneTenByTwoPageWithoutAnExtraLiveAction() {
        val pages = terminalShortcutPages(
            keys = TerminalExtraKey.DEFAULT_ORDER,
            columnCount = 10,
            includeCustomizeAction = false,
        )

        assertEquals(20, TerminalExtraKey.DEFAULT_ORDER.size)
        assertEquals(listOf(20), pages.map { it.size })
        assertEquals(TerminalExtraKey.DEFAULT_ORDER, pages.single().filterNotNull())
    }

    @Test
    fun oneRowProfilePaginatesAtExactlyTenPhoneKeys() {
        val pages = terminalShortcutPages(
            keys = TerminalExtraKey.DEFAULT_ORDER,
            columnCount = 10,
            rowCount = 1,
            includeCustomizeAction = false,
        )

        assertEquals(listOf(10, 10), pages.map { it.size })
        assertEquals(TerminalExtraKey.DEFAULT_ORDER, pages.flatten().filterNotNull())
    }

    @Test
    fun columnCountIsExactlyTenAtCompactAndDeviceWidths() {
        assertEquals(10, terminalShortcutColumnCount(320f))
        assertEquals(10, terminalShortcutColumnCount(360f))
        assertEquals(10, terminalShortcutColumnCount(412f))
        assertEquals(10, terminalShortcutColumnCount(1_000f))
    }

    @Test
    fun columnCountReducesOnlyForWindowsNarrowerThanCompactPhoneWidth() {
        assertEquals(9, terminalShortcutColumnCount(288f))
        assertEquals(7, terminalShortcutColumnCount(240f))
    }

    @Test
    fun compactLabelSizesKeepLongUtilityLabelsInsideNineColumnCells() {
        assertEquals(12f, terminalShortcutLabelFontSizeSp(2))
        assertEquals(8f, terminalShortcutLabelFontSizeSp(4))
        assertEquals(6f, terminalShortcutLabelFontSizeSp(7))
    }

    @Test
    fun invalidLayoutInputsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            terminalShortcutColumnCount(Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            terminalShortcutPages(listOf(TerminalExtraKey.ESC), columnCount = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            terminalShortcutPages(
                listOf(TerminalExtraKey.ESC),
                columnCount = 1,
                rowCount = 3,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            terminalShortcutLabelFontSizeSp(0)
        }
    }
}
