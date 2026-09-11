package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.view.HerdrHistoryViewport
import org.junit.Assert.*
import org.junit.Test

class HerdrPaneHistoryTest {
    @Test fun unchangedIdlePaneReusesItsSnapshotWithoutDownloadingAnotherThousandRows() {
        val choice = HerdrStartupChoice("/usr/bin/herdr", "default")
        val first = requireNotNull(captureHerdrPaneHistory({ fixtureResponse(it) }, choice, null, false))
        val commands = mutableListOf<String>()
        val second = captureHerdrPaneHistory({ command ->
            commands += command
            fixtureResponse(command)
        }, choice, first, false)
        assertSame(first, second)
        assertEquals(2, commands.size)
        assertTrue(commands.none { "'read'" in it })
    }

    @Test fun capturesOnlyTheSelectedSessionsFocusedCodexPaneWithoutInputCommands() {
        val commands = mutableListOf<String>()
        val capture = captureHerdrPaneHistory({ command ->
            commands += command
            fixtureResponse(command)
        }, HerdrStartupChoice("/usr/bin/herdr", "work ' quoted"), null, false)
        assertNotNull(capture)
        assertEquals("work ' quoted/w1:p2/term1", capture!!.identity)
        assertEquals(1000, capture.lines.size)
        assertTrue(commands.all { it.startsWith("'/usr/bin/herdr' '--session' 'work '\\'' quoted'") })
        assertEquals(5, commands.size)
        assertTrue(commands.all { "'layout'" in it || "'get'" in it || "'read'" in it })
        assertEquals(1, commands.count { "'read'" in it })
    }

    @Test fun rejectsChangedContentOrPaneRatherThanJoiningUnrelatedHistory() {
        var gets = 0
        val result = captureHerdrPaneHistory({ command ->
            val changed = "'get'" in command && ++gets == 2
            fixtureResponse(command, revision = if (changed) 2 else 1)
        }, HerdrStartupChoice("/usr/bin/herdr", "default"), null, false)
        assertNull(result)
        assertNull(captureHerdrPaneHistory({ command -> fixtureResponse(command, agent = "vim") },
            HerdrStartupChoice("/usr/bin/herdr", "default"), null, false))
    }

    @Test fun activeReaderChecksIdentityButDoesNotRefetchItsTranscript() {
        val previous = snapshot(identity = "default/w1:p2/term1").copy(columns = 80, rows = 24, x = 2, y = 1)
        val commands = mutableListOf<String>()
        val capture = captureHerdrPaneHistory({ command ->
            commands += command
            fixtureResponse(command)
        }, HerdrStartupChoice("/usr/bin/herdr", "default"), previous, true)
        assertSame(previous, capture)
        assertTrue(commands.none { "'read'" in it })
    }

    private fun fixtureResponse(command: String, revision: Int = 1, agent: String = "codex"): TmuxExecOutput {
        val value = when {
            "'layout'" in command -> """{"result":{"layout":{"focused_pane_id":"w1:p2","tab_id":"w1:t1","panes":[{"pane_id":"w1:p2","rect":{"x":2,"y":1,"width":80,"height":24}}]}}}"""
            "'get'" in command -> """{"result":{"pane":{"pane_id":"w1:p2","tab_id":"w1:t1","terminal_id":"term1","agent":"$agent","revision":$revision,"scroll":{"viewport_rows":24,"offset_from_bottom":0}}}}"""
            "'read'" in command -> (1..1000).joinToString("\n", postfix = "\n") { "row $it" }
            else -> error("Unexpected remote command")
        }
        return TmuxExecOutput(value.toByteArray(), 0)
    }

    @Test fun numberedRowsAndAnsiSurviveBoundedCapture() {
        val bytes = (1..1000).joinToString("\n", postfix = "\n") {
            "\u001b[32mCODEX_SCROLL_${it.toString().padStart(4, '0')}\u001b[0m"
        }.toByteArray()
        val rows = requireNotNull(parseHerdrHistoryRows(bytes, 80, 24))
        assertEquals(1000, rows.size)
        assertEquals((1..1000).map { "CODEX_SCROLL_${it.toString().padStart(4, '0')}" }, rows.map { it.text })
    }

    @Test fun rejectsOversizedOrInvalidCaptures() {
        assertNull(parseHerdrHistoryRows("x\n".repeat(1001).toByteArray(), 80, 24))
        assertNull(parseHerdrHistoryRows(ByteArray(HerdrPaneHistory.BYTE_LIMIT + 1), 80, 24))
        assertNull(parseHerdrHistoryRows("x\n".toByteArray(), 0, 24))
        assertNull(parseHerdrHistoryRows(ByteArray(0), 80, 24))
    }

    @Test fun fractionalReaderPinsAllRowsAndDoesNotMoveForNewOutput() {
        val original = snapshot()
        val reader = HerdrHistoryViewport()
        reader.begin(original, 20f)
        val bottom = reader.viewport.scrollY
        assertEquals(19520f, bottom, 0f)
        reader.viewport.scrollBy(-3.25f)
        assertEquals(bottom - 3.25f, reader.viewport.scrollY, 0f)
        assertTrue(reader.retainSource(snapshot(lines = List(1000) { TerminalLine.plain("new $it") })))
        assertSame(original, reader.snapshot)
        assertEquals(bottom - 3.25f, reader.viewport.scrollY, 0f)
        reader.viewport.scrollTo(0f)
        assertEquals("row 1", reader.snapshot!!.lines[reader.viewport.visibleRows(0).first].text)
        assertEquals(0f, reader.viewport.scrollBy(-200f), 0f)
        assertEquals((1..1000).map { "row $it" }, reader.snapshot!!.lines.map { it.text })
        reader.viewport.jumpToBottom()
        assertTrue(reader.viewport.autoFollow)
    }

    @Test fun paneReplacementLayoutChangeAndDisconnectInvalidateReader() {
        for (replacement in listOf(snapshot(identity = "other"), snapshot(x = 5), null)) {
            val reader = HerdrHistoryViewport()
            reader.begin(snapshot(), 20f)
            reader.viewport.scrollBy(-123.5f)
            assertFalse(reader.retainSource(replacement))
            assertNull(reader.snapshot)
        }
    }

    @Test fun existingRemoteScrollOffsetIsPreservedAtEntry() {
        val reader = HerdrHistoryViewport()
        reader.begin(snapshot().copy(offsetFromBottom = 10), 20f)
        assertEquals(reader.viewport.maximumScrollY - 200f, reader.viewport.scrollY, 0f)
    }

    private fun snapshot(identity: String = "default/w1:p1/term1", x: Int = 2,
        lines: List<TerminalLine> = (1..1000).map { TerminalLine.plain("row $it") },
    ) = HerdrPaneHistory(identity, x, 1, 80, 24, 0, lines)
}
