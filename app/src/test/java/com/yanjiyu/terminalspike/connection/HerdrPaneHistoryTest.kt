package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.view.HerdrHistoryViewport
import org.junit.Assert.*
import org.junit.Test

class HerdrPaneHistoryTest {
    @Test fun piRegularModeRetainsOrderedNativeHistory() {
        val captured = requireNotNull(captureHerdrPaneHistory(
            { fixtureResponse(it, agent = "pi") },
            HerdrStartupChoice("/usr/bin/herdr", "default"), null, false,
        ))
        assertEquals((1..1000).map { "row $it" }, captured.lines.map { it.text })
        val reader = HerdrHistoryViewport()
        reader.beginScroll(captured, 20f, -3.25f)
        reader.scrollBy(-3.25f)
        assertEquals(reader.viewport.maximumScrollY - 3.25f, reader.viewport.scrollY, 0f)
    }

    @Test fun fullscreenPiDoesNotClaimWrappedVisibleRowsAsNativeHistory() {
        var reads = 0
        val captured = captureHerdrPaneHistory({ command ->
            if ("'read'" in command) reads++
            val output = fixtureResponse(command, agent = "pi")
            output.copy(stdout = output.stdout.toString(Charsets.UTF_8)
                .replace("\"viewport_rows\":24", "\"max_offset_from_bottom\":0,\"viewport_rows\":24")
                .toByteArray())
        }, HerdrStartupChoice("/usr/bin/herdr", "default"), null, false)
        assertNull(captured)
        assertEquals("Do not read a screen-only pane as history", 0, reads)
    }

    @Test fun screenOnlyCodexCaptureDoesNotClaimNativeScrollOwnership() {
        for (lineCount in listOf(1, 23, 24)) {
            val captured = captureHerdrPaneHistory({ command ->
                if ("'read'" in command) TmuxExecOutput(
                    (1..lineCount).joinToString("\n", postfix = "\n") { "visible $it" }.toByteArray(), 0,
                ) else fixtureResponse(command)
            }, HerdrStartupChoice("/usr/bin/herdr", "default"), null, false)
            assertNull("A screen-only capture must leave scrolling to the live application", captured)
        }
    }

    @Test fun oneOlderRowStillProvidesFractionalNativeHistory() {
        val captured = requireNotNull(captureHerdrPaneHistory({ command ->
            if ("'read'" in command) TmuxExecOutput(
                (1..25).joinToString("\n", postfix = "\n") { "row $it" }.toByteArray(), 0,
            ) else fixtureResponse(command)
        }, HerdrStartupChoice("/usr/bin/herdr", "default"), null, false))
        val reader = HerdrHistoryViewport()
        reader.beginScroll(captured, 20f, -3.25f)
        reader.scrollBy(-3.25f)
        assertEquals(20f, reader.viewport.maximumScrollY, 0f)
        assertEquals(16.75f, reader.viewport.scrollY, 0f)
        assertEquals((1..25).map { "row $it" }, reader.snapshot!!.lines.map { it.text })
    }

    @Test fun switchingToScreenOnlyReleasesHistoryAndLaterInlineOutputRestoresIt() {
        val choice = HerdrStartupChoice("/usr/bin/herdr", "default")
        val initial = requireNotNull(captureHerdrPaneHistory({ fixtureResponse(it) }, choice, null, false))
        val screenOnly = captureHerdrPaneHistory({ command ->
            if ("'read'" in command) TmuxExecOutput(
                (1..24).joinToString("\n", postfix = "\n") { "screen $it" }.toByteArray(), 0,
            ) else fixtureResponse(command, revision = 2)
        }, choice, initial, false)
        assertNull("A changed screen-only pane must not reuse an older idle capture", screenOnly)
        val reader = HerdrHistoryViewport()
        reader.begin(initial, 20f)
        reader.scrollBy(-3.25f)
        assertFalse(reader.retainSource(screenOnly))
        assertNull(reader.snapshot)
        val restored = requireNotNull(captureHerdrPaneHistory(
            { fixtureResponse(it, revision = 3) }, choice, screenOnly, false,
        ))
        reader.beginScroll(restored, 20f, -3.25f)
        reader.scrollBy(-3.25f)
        assertEquals(reader.viewport.maximumScrollY - 3.25f, reader.viewport.scrollY, 0f)
        assertEquals((1..1000).map { "row $it" }, reader.snapshot!!.lines.map { it.text })
    }

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

    @Test fun letterAndMultiCharacterPaneNumbersRetainNativeHistory() {
        for (paneId in listOf("w6:pC", "wZ:pA1", "w1:p0")) {
            val commands = mutableListOf<String>()
            val captured = captureHerdrPaneHistory({ command ->
                commands += command
                fixtureResponse(command, paneId = paneId)
            }, HerdrStartupChoice("/usr/bin/herdr", "default"), null, false)
            assertNotNull("Valid Herdr pane $paneId must provide native history", captured)
            assertEquals("default/$paneId/term1", captured!!.identity)
            assertEquals(1000, captured.lines.size)
            assertTrue(commands.any { "'read' '$paneId'" in it })
        }
        for (paneId in listOf("w6:p", "w6:pC;id", "w6:pC extra", "w6:pC/other")) {
            val commands = mutableListOf<String>()
            assertNull(captureHerdrPaneHistory({ command ->
                commands += command
                fixtureResponse(command, paneId = paneId)
            }, HerdrStartupChoice("/usr/bin/herdr", "default"), null, false))
            assertEquals("Reject malformed ids before querying a pane", 1, commands.size)
        }
    }

    @Test fun remotelyScrolledPaneRemainsAvailableForNativeHistory() {
        val choice = HerdrStartupChoice("/usr/bin/herdr", "default")
        val first = requireNotNull(captureHerdrPaneHistory({ fixtureResponse(it, offset = 6) }, choice, null, false))
        assertEquals(6, first.offsetFromBottom)
        for (reading in listOf(false, true)) {
            val updated = requireNotNull(captureHerdrPaneHistory({ fixtureResponse(it, offset = 0) }, choice, first, reading))
            assertEquals("Remote position must update even while reader rows stay pinned", 0, updated.offsetFromBottom)
            if (reading) assertSame(first.lines, updated.lines)
        }
    }

    @Test fun nativeBottomDoesNotRevealTheRemotelyScrolledScreen() {
        val source = snapshot().copy(offsetFromBottom = 6)
        val reader = HerdrHistoryViewport()
        reader.beginScroll(source, 20f, 200f)
        reader.scrollBy(200f)
        assertNotNull("Latest input must stay visible above the old remote screen", reader.snapshot)
        assertTrue(reader.viewport.autoFollow)
        assertEquals(reader.viewport.maximumScrollY, reader.viewport.scrollY, 0f)
        reader.scrollBy(100f)
        assertNotNull(reader.snapshot)
        reader.retainSource(source.copy(offsetFromBottom = 0))
        assertNull("Return to live only when the remote screen is actually at bottom", reader.snapshot)
    }

    @Test fun heldLatestSnapshotUpdatesButOlderReaderKeepsItsPixelAnchor() {
        val source = snapshot().copy(offsetFromBottom = 6)
        val reader = HerdrHistoryViewport()
        reader.begin(source, 20f)
        reader.scrollBy(200f)
        val latest = source.copy(lines = source.lines + TerminalLine.plain("LATEST_INPUT"))
        reader.retainSource(latest)
        assertSame(latest, reader.snapshot)
        assertTrue(reader.viewport.autoFollow)
        assertEquals("LATEST_INPUT", reader.snapshot!!.lines.last().text)
        reader.scrollBy(-3.25f)
        val anchor = reader.viewport.scrollY
        reader.retainSource(source)
        assertSame(latest, reader.snapshot)
        assertEquals(anchor, reader.viewport.scrollY, 0f)
    }

    private fun fixtureResponse(command: String, revision: Int = 1, agent: String = "codex", offset: Int = 0, paneId: String = "w1:p2"): TmuxExecOutput {
        val value = when {
            "'layout'" in command -> """{"result":{"layout":{"focused_pane_id":"$paneId","tab_id":"w1:t1","panes":[{"pane_id":"$paneId","rect":{"x":2,"y":1,"width":80,"height":24}}]}}}"""
            "'get'" in command -> """{"result":{"pane":{"pane_id":"$paneId","tab_id":"w1:t1","terminal_id":"term1","agent":"$agent","revision":$revision,"scroll":{"viewport_rows":24,"offset_from_bottom":$offset}}}}"""
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

    @Test fun swipingPastLiveBottomDoesNotOpenCachedHistory() {
        val reader = HerdrHistoryViewport()
        repeat(5) {
            reader.beginScroll(snapshot(), 20f, 80f)
            reader.scrollBy(80f)
            assertNull(reader.snapshot)
        }
    }

    @Test fun reachingLiveBottomClearsSnapshotBeforeFingerUpAndIgnoresRemainingMoves() {
        val reader = HerdrHistoryViewport()
        reader.beginScroll(snapshot(), 20f, -100f)
        reader.scrollBy(-100f)
        assertNotNull(reader.snapshot)
        reader.scrollBy(150f)
        assertNull(reader.snapshot)
        reader.scrollBy(-40f)
        assertNull(reader.snapshot)
        // Only a new older-history gesture may start another reader.
        reader.beginScroll(snapshot(), 20f, -3.25f)
        reader.scrollBy(-3.25f)
        assertNotNull(reader.snapshot)
        assertEquals(reader.viewport.maximumScrollY - 3.25f, reader.viewport.scrollY, 0f)
    }

    @Test fun repeatedOldestBoundaryDragsKeepAllThousandRowsPinned() {
        val reader = HerdrHistoryViewport()
        val original = snapshot()
        reader.beginScroll(original, 20f, -20000f)
        repeat(5) {
            reader.scrollBy(-20000f)
            assertTrue(reader.retainSource(snapshot(lines = List(1000) { TerminalLine.plain("new $it") })))
            reader.beginScroll(snapshot(), 20f, -100f)
            assertSame(original, reader.snapshot)
            assertEquals(0f, reader.viewport.scrollY, 0f)
        }
        assertEquals((1..1000).map { "row $it" }, reader.snapshot!!.lines.map { it.text })
    }

    private fun snapshot(identity: String = "default/w1:p1/term1", x: Int = 2,
        lines: List<TerminalLine> = (1..1000).map { TerminalLine.plain("row $it") },
    ) = HerdrPaneHistory(identity, x, 1, 80, 24, 0, lines)
}
