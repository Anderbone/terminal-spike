package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshDisplayHistoryTest {
    @Test
    fun retainsRowsFromAnExactFramebufferStyleUpwardRewrite() {
        val engine = VtTerminalEngine(columns = 12, rows = 3)
        val history = MoshDisplayHistory()
        val first = engine.accept(rewriteScreen("001", "002", "003"))
        val shifted = engine.accept(rewriteScreen("002", "003", "004"))

        assertTrue(shifted.completedScrollback.isEmpty())
        history.retainDisplayedRows(first)

        val retained = history.retainDisplayedRows(shifted)

        assertEquals(listOf("001"), retained.completedScrollback.map { it.text.trimEnd() })
    }

    @Test
    fun retainsRowsWhenAFormerTrailingBlankBecomesNewOutput() {
        val engine = VtTerminalEngine(columns = 12, rows = 4)
        val history = MoshDisplayHistory()
        history.retainDisplayedRows(engine.accept(rewriteScreen("001", "002", "003", "")))

        val retained = history.retainDisplayedRows(
            engine.accept(rewriteScreen("002", "003", "004", "")),
        )

        assertEquals(listOf("001"), retained.completedScrollback.map { it.text.trimEnd() })
    }

    @Test
    fun doesNotTurnAnArbitraryFramebufferRepaintIntoHistory() {
        val engine = VtTerminalEngine(columns = 12, rows = 3)
        val history = MoshDisplayHistory()
        history.retainDisplayedRows(engine.accept(rewriteScreen("one", "two", "three")))

        val repainted = history.retainDisplayedRows(
            engine.accept(rewriteScreen("red", "green", "blue")),
        )

        assertTrue(repainted.completedScrollback.isEmpty())
    }

    @Test
    fun doesNotDuplicateScrollbackAlreadyReportedByTheVtEngine() {
        val engine = VtTerminalEngine(columns = 12, rows = 3)
        val history = MoshDisplayHistory()
        history.retainDisplayedRows(engine.accept("001\r\n002\r\n003".encodeToByteArray()))

        val parsedScroll = engine.accept("\r\n004".encodeToByteArray())
        val retained = history.retainDisplayedRows(parsedScroll)

        assertEquals(parsedScroll.completedScrollback, retained.completedScrollback)
        assertEquals(listOf("001"), retained.completedScrollback.map { it.text.trimEnd() })
    }

    private fun rewriteScreen(vararg rows: String): ByteArray = buildString {
        rows.forEachIndexed { index, row ->
            append("\u001B[${index + 1};1H\u001B[2K$row")
        }
    }.encodeToByteArray()
}
