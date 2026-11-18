package com.yanjiyu.terminalspike.terminal.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Owned parser data only; no controller, connection, document or real app preference. */
@RunWith(AndroidJUnit4::class)
class VtTerminalCellTextBudgetInstrumentedTest {
    @Test(timeout = 30_000)
    fun denseCombiningCellsStayWithinTheScreenTextBudget() {
        val engine = VtTerminalEngine(CELL_TEXT_FIXTURE_COLUMNS, CELL_TEXT_FIXTURE_ROWS)
        val frame = fillScreenWithCombiningCells(engine)
        assertTrue("Resident cell text must stay within its screen budget",
            screenCellTextUnits(frame) <= cellTextBudgetBound(CELL_TEXT_FIXTURE_COLUMNS, CELL_TEXT_FIXTURE_ROWS))
        val marker = "BUDGET_END"
        val after = engine.accept("\u001b[40;1H$marker".toByteArray())
        assertTrue("A following ordinary marker must remain writable", after.screen.last().text.startsWith(marker))
    }
}
