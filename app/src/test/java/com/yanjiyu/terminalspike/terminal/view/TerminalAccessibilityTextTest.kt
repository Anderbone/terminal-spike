package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAccessibilityTextTest {
    @Test fun partialPaneRetainsEveryUncoveredSideAndChromeRow() {
        val live = listOf("TOP", "SIDELIVEEND!", "ABCDHIDEKEEP", "BOTTOM").map { TerminalLine.plain(it) }
        assertEquals("TOP\nSIDEold1END!\nABCDold2KEEP\nBOTTOM", compose(live,
            pane(listOf("old1", "old2"), x = 4, y = 1, columns = 4, rows = 2), columns = 12, height = 40f))
    }

    @Test fun emptyAndShortHistoryRowsEraseCoveredLiveTextWithoutFillingFromIt() {
        val live = listOf(TerminalLine.plain("SIDELIVEKEEP"))
        assertEquals("SIDE    KEEP", compose(live, pane(listOf(""), x = 4, columns = 4), columns = 12))
        assertEquals("SIDEX   KEEP", compose(live, pane(listOf("X"), x = 4, columns = 4), columns = 12))
    }

    @Test fun wideCellCutAtEitherPaneBoundaryCannotLeakHiddenTextOrLoseUnrelatedSidebar() {
        assertEquals("A oldKEEP", compose(listOf(TerminalLine.plain("A界XXKEEP")),
            pane(listOf("old"), x = 2, columns = 3), columns = 9))
        assertEquals("LEFTH  TAIL", compose(listOf(TerminalLine.plain("LEFTx界TAIL")),
            pane(listOf("H"), x = 4, columns = 2), columns = 11))
    }

    @Test fun originalCombiningEmojiAndSupplementaryTextUseCellColumns() {
        for (cluster in listOf("a\u0301", "\uD834\uDD1E", "👩‍💻", "🇬🇧", "界")) {
            val prefix = TerminalLine.plain(cluster)
            val width = com.yanjiyu.terminalspike.terminal.selection.TerminalLineGeometry.columnCount(prefix)
            val live = listOf(TerminalLine.plain(cluster + "HIDE"))
            assertEquals(cluster + "P", compose(live, pane(listOf("P"), x = width, columns = 4), columns = width + 4))
        }
    }

    @Test fun explicitSparseRunColumnsPreserveTheirGapsAroundThePane() {
        val live = TerminalLine.styled(listOf(
            TerminalRun("X", startColumn = 2, columnWidth = 1),
            TerminalRun("Y", startColumn = 7, columnWidth = 1),
        ))
        assertEquals("  XP   Y", compose(listOf(live), pane(listOf("P"), x = 3, columns = 2), columns = 8))
    }

    @Test fun historyOwnsItsSoftWrapsAndUncoveredChromeRetainsHardRows() {
        val pane = pane(listOf("abc", "def", "ghi"), rows = 3).copy(lines = listOf(
            TerminalLine.plain("abc", softWrappedToNext = true),
            TerminalLine.plain("def"), TerminalLine.plain("ghi"),
        ))
        assertEquals("abcdef\nghi", compose(List(3) { TerminalLine.plain("HIDDEN") }, pane, height = 30f))
    }

    @Test fun characterCapIncludesPaddingAndNeverSplitsASurrogatePair() {
        val live = listOf(TerminalLine.plain("HIDDEN"))
        assertEquals("ab", compose(live, pane(listOf("ab🚀c")), maximum = 3))
        assertEquals("ab🚀", compose(live, pane(listOf("ab🚀c")), maximum = 4))
        assertEquals("   ", compose(listOf(TerminalLine.plain("")), pane(listOf("🚀"), x = 4, columns = 2), maximum = 3))
    }

    @Test fun productionCharacterLimitStopsBeforeASupplementaryCellWithoutExceedingTheBound() {
        val prefix = "x".repeat(16_383)
        assertEquals(prefix, compose(listOf(TerminalLine.plain("HIDDEN")),
            pane(listOf(prefix + "🚀tail"), columns = 17_000), columns = 17_000))
    }

    @Test fun fractionalReaderIncludesBothPartialRowsAndDoesNotRepeatSidebarOrUseLiveText() {
        val live = listOf("TOP", "SIDELIVEEND!", "ABCDHIDEKEEP", "BOTTOM").map { TerminalLine.plain(it) }
        val result = compose(live, pane(listOf("P0", "P1", "P2", "P3"),
            x = 4, y = 1, columns = 4, rows = 2, scrollY = 5f), columns = 12, height = 40f)
        for (marker in listOf("TOP", "SIDE", "ABCD", "BOTTOM", "P0", "P1", "P2")) {
            assertEquals("Each visible source appears once: $result", 1, Regex(marker).findAll(result).count())
        }
        assertFalse(result.contains("LIVE"))
        assertFalse(result.contains("HIDE"))
        assertFalse(result.contains("P3"))
        assertTrue(result.indexOf("P0") < result.indexOf("P1"))
        assertTrue(result.indexOf("P1") < result.indexOf("P2"))
    }

    @Test fun readerAndLiveRowsUseTheirIndependentScrollCoordinates() {
        val live = (0..12).map { TerminalLine.plain("CHROME$it") }
        val result = compose(live, pane(listOf("P0", "P1", "P2"), y = 1, rows = 1, scrollY = 10f),
            height = 30f, liveScrollY = 100f)
        assertEquals("CHROME10\nP1\nCHROME12", result)
    }

    private fun pane(
        lines: List<String>, x: Int = 0, y: Int = 0, columns: Int = 20,
        rows: Int = 1, scrollY: Float = 0f,
    ) = TerminalAccessibilityPane(x, y, columns, rows, scrollY, lines.map { TerminalLine.plain(it) })

    private fun compose(
        live: List<TerminalLine>, pane: TerminalAccessibilityPane,
        columns: Int = 20, height: Float = 10f, maximum: Int = 16_384, liveScrollY: Float = 0f,
    ) = composeTerminalAccessibilityText(live.size, live::getOrNull, liveScrollY,
        columns, 10f, height, pane, maximum)
}
