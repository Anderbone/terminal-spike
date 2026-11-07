package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.connection.HerdrPaneArea
import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalColour
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineGeometry
import org.junit.Assert.*
import org.junit.Test

class HerdrHistoryLiveTailTest {
    @Test fun firstDragIncludesAllTwoHundredRowsWhenMoshHasOutrunTheSideChannel() {
        val source = history((1..189).map(::marker) + List(4) { "old footer $it" })
        val screen = screen((180..200).map(::marker) + List(4) { "new footer $it" })
        val merged = herdrHistoryWithLiveTail(source, screen)
        assertEquals((1..200).map(::marker), merged.lines.map { it.text.trimEnd() }.filter { it.startsWith("CODEX_SCROLL_") })
        assertEquals((180..200).map(::marker) + List(4) { "new footer $it" }, merged.lines.takeLast(25).map { it.text.trimEnd() })
        assertEquals(0, merged.offsetFromBottom)
        assertEquals(source.identity, merged.identity)
        assertEquals(source.lines.size, 193)
    }

    @Test fun aHerdrWarningAboveTheOverlapDoesNotHideTheNewerMoshRows() {
        val source = history((1..189).map { "  ${marker(it)}" } + List(7) { "footer $it" })
        val screen = screen(listOf(" config.toml has unknown keys; herdr") +
            (184..200).map { "  ${marker(it)}" } + List(7) { "footer $it" })
        val merged = herdrHistoryWithLiveTail(source, screen)
        assertEquals((1..200).map(::marker), merged.lines.map { it.text.trim() }.filter { it.startsWith("CODEX_SCROLL_") })
        assertFalse(merged.lines.any { it.text.contains("unknown keys") })
    }

    @Test fun ambiguousRepeatedRowsAndUnrelatedFramesNeverJoinHistory() {
        val repeated = history(List(100) { listOf("one", "two", "three")[it % 3] })
        assertSame(repeated, herdrHistoryWithLiveTail(repeated, screen(List(25) { listOf("one", "two", "three")[it % 3] })))
        val source = history((1..189).map(::marker))
        assertSame(source, herdrHistoryWithLiveTail(source, screen(List(25) { "other pane $it" })))
        assertSame(source, herdrHistoryWithLiveTail(source, screen(List(25) { "" })))
        assertSame("An incomplete redraw cannot replace known rows with blanks", source,
            herdrHistoryWithLiveTail(source, screen((180..185).map(::marker) + List(19) { "" })))
    }

    @Test fun remoteOffsetsMissingFramesAndUnverifiedSurfacesRetainTheirOriginalRows() {
        val live = screen((176..200).map(::marker))
        for (source in listOf(history((1..189).map(::marker)).copy(offsetFromBottom = 4),
            history((1..189).map(::marker)).copy(surface = null))) {
            assertSame(source, herdrHistoryWithLiveTail(source, live))
        }
        val source = history((1..189).map(::marker))
        assertSame(source, herdrHistoryWithLiveTail(source, null))
        assertSame(source, herdrHistoryWithLiveTail(source, live.take(10)))
    }

    @Test fun unchangedLiveRowsReuseThePassiveSnapshot() {
        val source = history((1..200).map(::marker))
        assertSame(source, herdrHistoryWithLiveTail(source, screen((176..200).map(::marker))))
    }

    @Test fun aNewTailRetainsTheExistingBoundAndAllNewRows() {
        val source = history((1..1000).map(::marker))
        val merged = herdrHistoryWithLiveTail(source, screen((986..1010).map(::marker)))
        assertEquals(1000, merged.lines.size)
        assertEquals(marker(11), merged.lines.first().text.trimEnd())
        assertEquals(marker(1010), merged.lines.last().text.trimEnd())
    }

    @Test fun croppedLiveRowsKeepColourLinksAndCompleteWideCells() {
        val source = history((1..189).map(::marker))
        val lines = (176..200).map { if (it == 200) "\u001B[31m\u001B]8;;https://example.com\u001B\\中🙂 tail\u001B]8;;\u001B\\\u001B[0m" else marker(it) }
        val merged = herdrHistoryWithLiveTail(source, screen(lines))
        val last = merged.lines.last()
        assertTrue(last.text.trimEnd().startsWith("中🙂 tail"))
        assertTrue(last.runs.any { it.style.foreground == TerminalColour.Indexed(1) && it.hyperlink?.uri == "https://example.com" })
        assertEquals(0, last.runs.first().startColumn)
    }

    @Test fun aPaneBoundaryThroughHalfOfAWideCellCannotCreateATextContinuation() {
        val line = VtTerminalEngine(10, 1).accept("AB中CD".toByteArray()).screen.single()
        assertNull(TerminalLineGeometry.sliceColumns(line, 3, 6))
        assertEquals("中", requireNotNull(TerminalLineGeometry.sliceColumns(line, 2, 4)).text)
    }

    private fun marker(row: Int) = "CODEX_SCROLL_%03d".format(row)
    private fun history(lines: List<String>) = HerdrPaneHistory(
        "owned/w1:p1/term1", 4, 2, 36, 25, 0, lines.map(TerminalLine::plain),
        surface = HerdrPaneArea(0, 0, 36, 25),
    )
    private fun screen(lines: List<String>): List<TerminalLine> {
        val engine = VtTerminalEngine(44, 30)
        return engine.accept(buildString {
            for ((row, line) in lines.withIndex()) append("\u001B[${row + 3};5H$line")
        }.toByteArray()).screen
    }
}
