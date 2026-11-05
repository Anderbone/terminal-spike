package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.connection.HerdrPaneHistory
import com.yanjiyu.terminalspike.terminal.model.TerminalHyperlink
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import com.yanjiyu.terminalspike.terminal.view.HerdrHistoryViewport
import org.junit.Assert.*
import org.junit.Test

class HerdrHistoryLinkTest {
    private val uri = "obsidian://open?vault=Obsjiyu&file=notes%2F%E4%B8%AD%E6%96%87%20note"

    @Test fun fractionalHistoryTapResolvesVisiblePlainAndOsc8LinksWithoutMovingTheReader() {
        for (line in listOf(TerminalLine.plain(uri), TerminalLine.styled(listOf(
            TerminalRun("中文 note", hyperlink = TerminalHyperlink(uri), startColumn = 0, columnWidth = 9),
        )))) {
            val reader = reader(line)
            val anchor = reader.viewport.scrollY
            val hit = reader.linkAt(283f, 77.75f, 10f, 20f, 8f, 8f, true, true)
            assertEquals(uri, hit?.uri)
            assertEquals(22, hit?.line?.volatileRow)
            assertEquals(anchor, reader.viewport.scrollY, 0f)
            assertFalse(reader.viewport.autoFollow)
            assertNull(reader.linkAt(267f, 77.75f, 10f, 20f, 8f, 8f, true, true))
            assertNull(reader.linkAt(283f, 27f, 10f, 20f, 8f, 8f, true, true))
            assertNull(reader.linkAt(283f, 508f, 10f, 20f, 8f, 8f, true, true))
        }
    }

    @Test fun wrappedObsidianLinkUsesThePinnedRowsAfterNewLiveOutput() {
        val lines = MutableList(100) { TerminalLine.plain("row $it") }
        lines[22] = TerminalLine.plain(uri.take(35), softWrappedToNext = true)
        lines[23] = TerminalLine.plain(uri.drop(35))
        val source = HerdrPaneHistory("default/w1:p1/term1", 26, 1, 80, 24, 0, lines)
        val reader = HerdrHistoryViewport().apply { begin(source, 20f); scrollTo(400.25f) }
        reader.retainSource(source.copy(lines = List(100) { TerminalLine.plain("https://wrong.example/live") }))
        assertEquals(uri, reader.linkAt(323f, 97.75f, 10f, 20f, 8f, 8f, true, true)?.uri)
        reader.clear()
        assertNull(reader.linkAt(323f, 97.75f, 10f, 20f, 8f, 8f, true, true))
    }

    @Test fun readerHonorsLinkPreferencesAndDoesNotClampClicksIntoAnotherRow() {
        val reader = reader(TerminalLine.styled(listOf(TerminalRun("note", hyperlink = TerminalHyperlink(uri)))))
        assertNull(reader.linkAt(283f, 77.75f, 10f, 20f, 8f, 8f, false, false))
        assertNull(reader.linkAt(283f, 117.75f, 10f, 20f, 8f, 8f, true, true))
    }

    private fun reader(line: TerminalLine) = HerdrHistoryViewport().apply {
        begin(HerdrPaneHistory("default/w1:p1/term1", 26, 1, 80, 24, 0,
            List(100) { if (it == 22) line else TerminalLine.plain("row $it") }), 20f)
        scrollTo(400.25f)
    }
}
