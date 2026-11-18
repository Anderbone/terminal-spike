package com.yanjiyu.terminalspike.terminal.model

import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRunRenderingTest {
    @Test fun decomposedCellsKeepTheirCompleteMarksAtTheVisibleBoundary() {
        val text = "M\u0301".repeat(20) + "ZZZ"
        val line = VtTerminalEngine(40, 3).accept(text.toByteArray()).screen.first()
        val rendering = requireNotNull(line.runs.single().rendering)
        assertEquals(text, line.text)
        assertSame(line.runs.single().text, rendering.text)
        assertEquals("M\u0301".repeat(6), rendering.text.substring(0, rendering.endForColumns(6)))
        assertEquals(text.length, rendering.endForColumns(23))
    }

    @Test fun mixedWideSupplementaryJoinedAndFlagCellsUseParserColumns() {
        // These are actual parser cells, not UTF-16 units or a second parser implementation.
        val cells = listOf("A" to 1, "界" to 2, "\uD834\uDD1E" to 1,
            "\uD83D\uDC69\u200D\uD83D\uDCBB" to 2, "\uD83C\uDDEC\uD83C\uDDE7" to 2, "M\u0301" to 1)
        val text = cells.joinToString("") { it.first } + "ZZZ"
        val run = VtTerminalEngine(40, 3).accept(text.toByteArray()).screen.first().runs.single()
        val rendering = requireNotNull(run.rendering)
        var columns = 0
        var offset = 0
        for ((cell, width) in cells) {
            assertEquals(columns, rendering.columnAtOffset(offset))
            assertEquals(offset, rendering.endForColumns(columns))
            assertEquals(offset + cell.length, rendering.endForColumns(columns + 1))
            columns += width
            offset += cell.length
            assertEquals(offset, rendering.endForColumns(columns))
        }
        assertEquals(columns + 3, run.columnWidth)
        assertEquals(columns, rendering.columnAtOffset(offset))
        assertEquals(columns + 2, rendering.columnAtOffset(offset + 2))
    }

    @Test fun ordinaryGapsBetweenComplexCellsRetainTheirColumnOffsets() {
        val text = "M\u0301abc界xyzM\u0301ZZZ"
        val rendering = requireNotNull(TerminalRun(text).rendering)
        assertEquals("M\u0301abc", text.substring(0, rendering.endForColumns(4)))
        assertEquals("M\u0301abc界", text.substring(0, rendering.endForColumns(5)))
        assertEquals("M\u0301abc界xyzM\u0301Z", text.substring(0, rendering.endForColumns(11)))
        assertEquals(10, rendering.columnAtOffset(text.indexOf("ZZZ")))
    }

    @Test fun oversizedCombiningCellKeepsOriginalTextAndFollowingCells() {
        val cell = "M" + "\u0301".repeat(TerminalRunRendering.MAX_CELL_UTF16_UNITS + 1)
        val text = cell + "ZZZ"
        val line = VtTerminalEngine(10, 3).accept(text.toByteArray()).screen.first()
        val run = line.runs.single()
        val rendering = requireNotNull(run.rendering)
        assertEquals(text, run.text)
        assertEquals(text, line.text)
        assertEquals(4, run.columnWidth)
        assertEquals("\uFFFDZZZ", rendering.text)
        assertEquals(1, rendering.endForColumns(1))
        assertEquals(1, rendering.columnAtOffset(1))
        assertEquals(4, rendering.endForColumns(Int.MAX_VALUE))
    }

    @Test fun oversizedWideCellKeepsItsTwoColumnsBeforeTheNextMarker() {
        val cell = "界" + "\u0301".repeat(TerminalRunRendering.MAX_CELL_UTF16_UNITS + 1)
        val text = "M\u0301" + cell + "ZZZ"
        val line = VtTerminalEngine(10, 3).accept(text.toByteArray()).screen.first()
        val rendering = requireNotNull(line.runs.single().rendering)
        assertEquals(text, line.text)
        assertEquals("M\u0301\uFFFD ZZZ", rendering.text)
        assertEquals(3, rendering.columnAtOffset(rendering.text.indexOf("ZZZ")))
        assertEquals(4, rendering.endForColumns(3))
    }

    @Test fun hostileCellDoesNotIncreaseVisibleShapingWorkOrHideLaterComplexCells() {
        val source = "M" + "\u0301".repeat(100_000) + "界M\u0301ZZZ"
        val run = TerminalRun(source)
        val rendering = requireNotNull(run.rendering)
        assertEquals(source, run.text)
        assertEquals("\uFFFD界M\u0301ZZZ", rendering.text)
        assertTrue(rendering.endForColumns(4) <= 4 * TerminalRunRendering.MAX_CELL_UTF16_UNITS)
        assertEquals("\uFFFD界M\u0301", rendering.text.substring(0, rendering.endForColumns(4)))
        assertEquals(4, rendering.columnAtOffset(rendering.text.indexOf("ZZZ")))
    }

    @Test fun cellLimitIsInclusiveAndOnlyAffectsTheOversizedCell() {
        val limit = TerminalRunRendering.MAX_CELL_UTF16_UNITS
        val allowed = "M" + "\u0301".repeat(limit - 1)
        val oversized = allowed + "\u0301"
        val run = TerminalRun(allowed + "X" + oversized + "ZZZ")
        val rendering = requireNotNull(run.rendering)
        assertEquals(allowed + "X\uFFFDZZZ", rendering.text)
        assertEquals(limit, rendering.endForColumns(1))
        assertEquals(limit + 2, rendering.endForColumns(3))
        assertEquals(3, rendering.columnAtOffset(rendering.text.indexOf("ZZZ")))
    }

    @Test fun copyingARunRecomputesDrawMetadataForItsNewText() {
        val run = TerminalRun("M\u0301ZZZ", startColumn = 3, columnWidth = 4)
        val ascii = run.copy(text = "ABCD")
        assertNull(ascii.rendering)
        assertEquals(3, ascii.startColumn)
        assertEquals(4, ascii.columnWidth)
        assertEquals(run, run.copy())
        assertEquals(requireNotNull(run.rendering).endForColumns(1), requireNotNull(run.copy().rendering).endForColumns(1))
    }

    @Test fun lineSanitizationReusesValidRunsAndRecomputesInvalidSurrogateMetadata() {
        val valid = TerminalRun("M\u0301ZZZ")
        assertSame(valid, TerminalLine.styled(listOf(valid)).runs.single())
        val invalid = TerminalRun("M\u0301\uD800ZZZ", startColumn = 2, columnWidth = 5)
        val sanitized = TerminalLine.styled(listOf(invalid)).runs.single()
        assertEquals("M\u0301\uFFFDZZZ", sanitized.text)
        assertEquals(2, sanitized.startColumn)
        assertEquals(5, sanitized.columnWidth)
        assertEquals(3, requireNotNull(sanitized.rendering).endForColumns(2))
    }

    @Test fun asciiNeedsNoMetadataAndWideEmojiKeepIndependentGridPositions() {
        assertNull(TerminalRun("ordinary ASCII text").rendering)
        val emoji = requireNotNull(TerminalRun("\uD83D\uDE00\uD83D\uDE00").rendering)
        assertTrue(emoji.hasGridCells)
        assertEquals(2, emoji.cellCount)
        assertEquals(0, emoji.cellStartColumn(0))
        assertEquals(2, emoji.cellStartColumn(1))
        assertEquals(2, emoji.cellEndOffset(0))
        assertEquals(4, emoji.cellEndOffset(1))
        assertEquals(2, emoji.cellWidth(0))
        val rendering = requireNotNull(TerminalRun("M\u0301").rendering)
        assertEquals(0, rendering.endForColumns(-1))
        assertEquals(0, rendering.columnAtOffset(-1))
        assertEquals(1, rendering.columnAtOffset(Int.MAX_VALUE))
    }

    @Test fun fallbackGlyphsUseGridPlacementWhileCombiningTextKeepsSharedShaping() {
        val rendering = requireNotNull(TerminalRun("\uD834\uDD1EM\u0301\uFFFDZZZ").rendering)
        assertEquals(3, rendering.cellCount)
        assertTrue(rendering.cellNeedsGridPlacement(0))
        assertEquals(false, rendering.cellNeedsGridPlacement(1))
        assertTrue(rendering.cellNeedsGridPlacement(2))
        assertEquals(listOf(0, 1, 2), (0 until rendering.cellCount).map(rendering::cellStartColumn))
        assertEquals(listOf(2, 4, 5), (0 until rendering.cellCount).map(rendering::cellEndOffset))
        assertEquals(3, rendering.columnAtOffset(5))
    }
}
