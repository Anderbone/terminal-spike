package com.yanjiyu.terminalspike.terminal.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class VtTerminalCellTextBudgetTest {
    @Test
    fun denseCombiningCellsHaveABoundedTotalScreenText() {
        val frame = fillScreenWithCombiningCells(engine())
        assertTrue("Resident cell text must stay within its screen budget",
            screenCellTextUnits(frame) <= cellTextBudgetBound(64, 40))
        assertTrue(frame.screen.any { '\uFFFD' in it.text })
    }

    @Test
    fun oneByteUtf8ChunksAtTheBudgetBoundaryCannotGrowOrConsumeTheNextGrapheme() {
        val engine = engine()
        fillScreenWithCombiningCells(engine)
        var frame: TerminalFrameUpdate? = null
        val overflow = "\u001b[40;64HZ" + "\u0301".repeat(18)
        for (byte in overflow.toByteArray(Charsets.UTF_8)) frame = engine.accept(byteArrayOf(byte))
        assertTrue(requireNotNull(frame).screen.last().text.endsWith("\uFFFD"))
        val next = "Z" + "\u0301".repeat(10)
        val recovered = feed(engine, "\u001b[40;64H$next")
        assertTrue(recovered.screen.last().text.endsWith(next))
    }

    @Test
    fun wideningTheScreenPreservesExistingTextReservations() {
        val engine = engine()
        fillScreenWithCombiningCells(engine)
        engine.resize(128, 40)
        val glyph = "Z" + "\u0301".repeat(399)
        val frame = feed(engine, "\u001b[40;101H$glyph")
        assertFalse(frame.screen.last().text.contains(glyph))
        assertTrue(frame.screen.last().text.endsWith("\uFFFD"))
        assertTrue(screenCellTextUnits(frame) <= cellTextBudgetBound(128, 40))
    }

    @Test
    fun alternateScreenOwnershipAndClearingKeepIndependentReservations() {
        val engine = engine()
        val primary = fillScreenWithCombiningCells(engine).screen.map { it.text }
        feed(engine, "\u001b[?1049h")
        val alternate = fillScreenWithCombiningCells(engine)
        assertTrue(alternate.screen.first().text.startsWith("a" + "\u0301".repeat(511)))
        assertEquals(primary, feed(engine, "\u001b[?1049l").screen.map { it.text })
        feed(engine, "\u001b[?1049h")
        val glyph = "Z" + "\u0301".repeat(4_095)
        assertEquals(glyph, feed(engine, glyph).screen.first().text)
        assertEquals(primary, feed(engine, "\u001b[?1049l").screen.map { it.text })
    }

    @Test
    fun continuedOverflowTailDoesNotInvalidateTheImmutableRow() {
        val engine = engine()
        val before = fillScreenWithCombiningCells(engine)
        val after = feed(engine, "\u0301".repeat(100))
        assertSame(before.screen.last(), after.screen.last())
        assertTrue(after.dirtyRows.isEmpty())
    }

    @Test
    fun globalOverflowRetainsTheTwoColumnReplacementAndNextCharacterPosition() {
        val engine = engine()
        fillScreenWithCombiningCells(engine, wideFirst = true)
        val frame = feed(engine, "\u001b[1;3H" + "\u0301".repeat(100) + "X")
        assertTrue(frame.screen.first().text.startsWith("\uFFFD X"))
        assertTrue(screenCellTextUnits(frame) <= cellTextBudgetBound(64, 40))
    }
}

enum class CellBudgetMutation {
    ERASE_SCREEN, ERASE_LINE, OVERWRITE, WIDE_CONTINUATION,
    INSERT_CHARACTERS, DELETE_CHARACTERS, INSERT_LINES, DELETE_LINES,
    SCROLL_UP, SCROLL_DOWN, SCROLL_REGION_UP, SCROLL_REGION_DOWN, RESIZE_NARROWER,
}

@RunWith(Parameterized::class)
class VtTerminalCellTextBudgetMutationTest(private val mutation: CellBudgetMutation) {
    @Test
    fun removedCellTextReleasesCapacityAndPreservesTheRemainingScreenBound() {
        val engine = engine()
        val reverse = mutation in setOf(CellBudgetMutation.INSERT_LINES,
            CellBudgetMutation.SCROLL_DOWN, CellBudgetMutation.SCROLL_REGION_DOWN)
        val before = fillScreenWithCombiningCells(engine, reverseRows = reverse,
            wideFirst = mutation == CellBudgetMutation.WIDE_CONTINUATION)
        var row = 0
        var column = 0
        var columns = 64
        when (mutation) {
            CellBudgetMutation.ERASE_SCREEN -> feed(engine, "\u001b[2J")
            CellBudgetMutation.ERASE_LINE -> feed(engine, "\u001b[1;1H\u001b[2K")
            CellBudgetMutation.OVERWRITE -> { feed(engine, "\u001b[1;1HX"); row = 39; column = 63 }
            CellBudgetMutation.WIDE_CONTINUATION -> { feed(engine, "\u001b[1;2HX"); row = 39; column = 63 }
            CellBudgetMutation.INSERT_CHARACTERS -> feed(engine, "\u001b[1;1H\u001b[64@")
            CellBudgetMutation.DELETE_CHARACTERS -> feed(engine, "\u001b[1;1H\u001b[64P")
            CellBudgetMutation.INSERT_LINES -> feed(engine, "\u001b[1;1H\u001b[L")
            CellBudgetMutation.DELETE_LINES -> { feed(engine, "\u001b[1;1H\u001b[M"); row = 39 }
            CellBudgetMutation.SCROLL_UP -> { feed(engine, "\u001b[S"); row = 39 }
            CellBudgetMutation.SCROLL_DOWN -> feed(engine, "\u001b[T")
            CellBudgetMutation.SCROLL_REGION_UP -> { feed(engine, "\u001b[2;39r\u001b[S"); row = 38 }
            CellBudgetMutation.SCROLL_REGION_DOWN -> { feed(engine, "\u001b[2;39r\u001b[T"); row = 1 }
            CellBudgetMutation.RESIZE_NARROWER -> { engine.resize(32, 40); columns = 32; row = 39 }
        }
        val glyph = "Z" + "\u0301".repeat(399)
        val frame = feed(engine, "\u001b[${row + 1};${column + 1}H$glyph")
        assertTrue("$mutation must return capacity for a new grapheme", frame.screen[row].text.contains(glyph))
        assertTrue("$mutation must preserve the total screen bound",
            screenCellTextUnits(frame) <= cellTextBudgetBound(columns, 40))
        if (mutation in setOf(CellBudgetMutation.SCROLL_REGION_UP, CellBudgetMutation.SCROLL_REGION_DOWN)) {
            assertEquals(before.screen.first().text, frame.screen.first().text)
            assertEquals(before.screen.last().text, frame.screen.last().text)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "mutation={0}")
        fun mutations(): List<Array<CellBudgetMutation>> = CellBudgetMutation.entries.map { arrayOf(it) }
    }
}

private fun engine() = VtTerminalEngine(CELL_TEXT_FIXTURE_COLUMNS, CELL_TEXT_FIXTURE_ROWS)
private fun feed(engine: VtTerminalEngine, text: String) = engine.accept(text.toByteArray(Charsets.UTF_8))
