package com.yanjiyu.terminalspike.terminal

import android.view.Choreographer
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.terminal.engine.TerminalFrameUpdate
import com.yanjiyu.terminalspike.terminal.engine.TerminalModes
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineSpace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalControllerWorkflowTest {
    @Test
    fun herdrMobileSwitcherRedrawDisablesHistoryWithoutWaitingForAnotherCapture() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 1000), scheduler)
        controller.reportTerminalSize(60, 30)
        controller.publishHerdrSidebarLayout(
            com.yanjiyu.terminalspike.connection.HerdrSidebarLayout(0, 60, 2, 28),
        )
        val engine = com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine(60, 30)
        fun header(text: String) {
            controller.updateTerminalFrame(engine.accept("\u001B[2;51H\u001B[K$text".toByteArray()))
            scheduler.drainAll()
        }
        val history = com.yanjiyu.terminalspike.connection.HerdrPaneHistory(
            "default/w1:p1/term1", 0, 2, 60, 28, 0,
            (1..1000).map { TerminalLine.plain("row $it") },
        )
        controller.publishHerdrHistory(history)
        header("│ switch  ")
        assertTrue(controller.isHerdrNativeHistoryVisible())
        header("│    ×    ")
        assertFalse(controller.isHerdrNativeHistoryVisible())
        assertTrue(controller.herdrHistory === history)
        header("│ switch  ")
        assertTrue(controller.isHerdrNativeHistoryVisible())
        controller.publishHerdrSidebarLayout(null)
        header("│    ×    ")
        assertTrue(controller.isHerdrNativeHistoryVisible()) // Ordinary terminals are unaffected.
    }

    @Test
    fun mouseClicksFollowNegotiatedModesAndTypingStillWorks() {
        val scheduler = ManualFrameScheduler()
        val sink = RecordingInputSink()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler)
        controller.setInputSink(sink, onResize = { _, _ -> })
        controller.reportTerminalSize(80, 24)
        val engine = com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine(columns = 80, rows = 24)
        fun output(text: String) {
            controller.updateTerminalFrame(engine.accept(text.toByteArray()))
            scheduler.drainAll()
        }

        assertFalse(controller.sendMouseClick(4, 7))
        // Enabling an encoding alone must not enable mouse reporting at a shell prompt.
        output("\u001B[?1006h")
        assertFalse(controller.sendMouseClick(4, 7))
        for (mode in listOf(1000, 1002, 1003)) {
            output("\u001B[?$mode;1006h")
            assertTrue(controller.sendMouseClick(4, 7))
            assertArrayEquals("\u001B[<0;5;8M\u001B[<0;5;8m".toByteArray(), sink.received.last())
            assertTrue(controller.sendMouseClick(4, 7, secondary = true))
            assertArrayEquals("\u001B[<2;5;8M\u001B[<2;5;8m".toByteArray(), sink.received.last())
            output("\u001B[?${mode}l")
            assertFalse(controller.sendMouseClick(4, 7))
        }
        output("\u001B[?1006l\u001B[?1000h")
        assertTrue(controller.sendMouseClick(0, 0))
        assertArrayEquals(byteArrayOf(27, 91, 77, 32, 33, 33, 27, 91, 77, 35, 33, 33), sink.received.last())
        output("\u001B[?1000l\u001B[?9h")
        assertTrue(controller.sendMouseClick(0, 0))
        assertArrayEquals(byteArrayOf(27, 91, 77, 32, 33, 33), sink.received.last())
        controller.send("hello chat".toByteArray())
        assertArrayEquals("hello chat".toByteArray(), sink.received.last())
    }

    @Test
    fun mouseClicksRejectHistoryAndOutsideGridWithoutJumpingOrSending() {
        val scheduler = ManualFrameScheduler()
        val sink = RecordingInputSink()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler)
        controller.setInputSink(sink, onResize = { _, _ -> })
        controller.reportTerminalSize(80, 24)
        controller.updateTerminalFrame(frame(modes = TerminalModes(mouseTracking = true, sgrMouseEncoding = true)))
        scheduler.drainAll()
        for ((column, row) in listOf(-1 to 0, 0 to -1, 80 to 0, 0 to 24)) {
            assertFalse(controller.sendMouseClick(column, row))
            assertFalse(controller.sendMouseClick(column, row, secondary = true))
        }
        controller.viewport.updateGeometry(heightPx = 100, newLineHeightPx = 10f)
        controller.viewport.updateContent(100, 0L)
        controller.viewport.scrollTo(123.5f)
        assertFalse(controller.sendMouseClick(4, 7))
        assertFalse(controller.sendMouseClick(4, 7, secondary = true))
        assertEquals(123.5f, controller.viewport.scrollY)
        assertTrue(sink.received.isEmpty())
    }

    @Test
    fun moshFramebufferRepaintCannotAdvanceAuthoritativeTmuxPagingCoordinates() {
        val scheduler = ManualFrameScheduler()
        var requested: com.yanjiyu.terminalspike.connection.TmuxHistoryPageRequest? = null
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            setInputSink(
                sink = RecordingInputSink(), onResize = { _, _ -> }, isTmuxSession = { true },
                requestOlderTmuxHistory = { requested = it }, trustTmuxStreamScrollback = false,
            )
        }
        controller.updateTerminalFrame(frame(screen = listOf(TerminalLine.plain("live"))))
        scheduler.drainAll()
        controller.stageTmuxHistory(TmuxLocalHistorySnapshot(
            sessionId = "$1", paneId = "%2",
            lines = List(4_096) { TerminalLine.plain("server-row-${it + 904}") },
            remoteHistoryRows = 5_000, capturedStartRow = 904,
            remoteMousePassthrough = false, historyIncluded = true,
            authoritative = true, truncatedBefore = true,
        ))
        scheduler.drainAll()
        // The framebuffer renderer may scroll while repainting the same remote history.
        controller.updateTerminalFrame(frame(screen = listOf(TerminalLine.plain("live"))).copy(
            completedScrollback = List(20) { TerminalLine.plain("repaint-$it") },
        ))
        scheduler.drainAll()
        controller.viewport.scrollTo(0f)
        controller.requestOlderTmuxHistoryIfNeeded()
        assertEquals(5_000, requested?.remoteHistoryRows)
        assertEquals(904, requested?.beforeRow)
        assertEquals(4_097, controller.lineCount())
    }

    @Test
    fun maximumTmuxSnapshotIsPreparedAcrossBoundedFramesAndPublishedAtomically() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
            )
        }
        val frameWork = mutableListOf<Int>()
        controller.observeTmuxReconciliationWork(frameWork::add)
        val history = List(20_480) { index ->
            TerminalLine.plain("maximum-tmux-row-$index")
        }

        controller.updateTerminalFrame(frame(screen = listOf(TerminalLine.plain("live"))))
        scheduler.drainAll()
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = history,
                remoteHistoryRows = history.size,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )

        scheduler.runNext()

        assertTrue(scheduler.hasPendingFrames())
        assertEquals(listOf("live"), controller.transcriptSnapshot().rows.map { it.line.text })

        scheduler.drainAll()

        assertEquals(history.size + 1, controller.lineCount())
        assertEquals("maximum-tmux-row-0", controller.lineAt(0)?.text)
        assertEquals("maximum-tmux-row-20479", controller.lineAt(history.lastIndex)?.text)
        assertEquals("live", controller.lineAt(history.size)?.text)
        assertTrue(frameWork.size > 1)
        assertEquals(history.size, frameWork.sum())
        assertTrue(frameWork.all {
            it in 1..TerminalController.MAX_TMUX_RECONCILIATION_ROW_WORK_PER_FRAME
        })
    }

    @Test
    fun newerTmuxSnapshotCancelsPartialPreparationBeforeItCanPublishStaleRows() {
        val scheduler = ManualFrameScheduler()
        val publishedFirstRows = mutableListOf<String?>()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
            )
            addListener {
                publishedFirstRows += lineAt(0)?.text
            }
        }
        controller.updateTerminalFrame(frame(screen = listOf(TerminalLine.plain("live"))))
        scheduler.drainAll()
        val stale = List(8_000) { TerminalLine.plain("stale-$it") }
        val current = List(8_000) { TerminalLine.plain("current-$it") }

        controller.stageTmuxHistory(tmuxSnapshot(stale, remoteRows = stale.size))
        scheduler.runNext()
        controller.stageTmuxHistory(tmuxSnapshot(current, remoteRows = current.size))
        scheduler.drainAll()

        assertFalse(publishedFirstRows.contains("stale-0"))
        assertEquals("current-0", controller.lineAt(0)?.text)
        assertEquals("current-7999", controller.lineAt(current.lastIndex)?.text)
    }

    @Test
    fun clearCancelsPartialTmuxPreparationWithoutPublishingItsRows() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
            )
        }
        val history = List(8_000) { TerminalLine.plain("cancelled-$it") }
        controller.stageTmuxHistory(tmuxSnapshot(history, remoteRows = history.size))
        scheduler.runNext()

        controller.clear()
        scheduler.drainAll()

        assertEquals(0, controller.lineCount())
        assertFalse(controller.isTmuxLocalScrollAvailable())
    }

    @Test
    fun detachCancelsPartialTmuxPreparationWithoutReplacingTheVisibleTranscript() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
            )
        }
        controller.updateTerminalFrame(frame(screen = listOf(TerminalLine.plain("visible"))))
        scheduler.drainAll()
        val history = List(8_000) { TerminalLine.plain("detached-$it") }
        controller.stageTmuxHistory(tmuxSnapshot(history, remoteRows = history.size))
        scheduler.runNext()

        controller.resetInputSink()
        scheduler.drainAll()

        assertEquals(listOf("visible"), controller.transcriptSnapshot().rows.map { it.line.text })
        assertFalse(controller.isTmuxSession())
    }

    @Test
    fun largeCoordinateResetStaysAtomicAndWithinTheRowWorkBudget() {
        val scheduler = ManualFrameScheduler()
        val frameWork = mutableListOf<Int>()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
            )
            observeTmuxReconciliationWork(frameWork::add)
        }
        val oldEpoch = List(8_000) { TerminalLine.plain("old-$it") }
        controller.updateTerminalFrame(frame(screen = listOf(TerminalLine.plain("live"))))
        controller.stageTmuxHistory(tmuxSnapshot(oldEpoch, remoteRows = oldEpoch.size))
        scheduler.drainAll()
        frameWork.clear()
        val nextEpoch = oldEpoch.takeLast(1_000) +
            List(3_000) { TerminalLine.plain("new-$it") }
        controller.stageTmuxHistory(tmuxSnapshot(nextEpoch, remoteRows = nextEpoch.size))

        scheduler.runNext()

        assertEquals("old-0", controller.lineAt(0)?.text)
        assertEquals(8_001, controller.lineCount())
        scheduler.drainAll()

        assertEquals(11_001, controller.lineCount())
        assertEquals("old-0", controller.lineAt(0)?.text)
        assertEquals("old-7999", controller.lineAt(7_999)?.text)
        assertEquals("new-0", controller.lineAt(8_000)?.text)
        assertEquals("new-2999", controller.lineAt(10_999)?.text)
        assertTrue(frameWork.size > 1)
        assertTrue(frameWork.all {
            it in 1..TerminalController.MAX_TMUX_RECONCILIATION_ROW_WORK_PER_FRAME
        })
    }

    @Test
    fun fullOlderPageIsPreparedAtomicallyWithinTheRowWorkBudget() {
        val scheduler = ManualFrameScheduler()
        val frameWork = mutableListOf<Int>()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
            )
            observeTmuxReconciliationWork(frameWork::add)
        }
        val newestPage = (4_096 until 8_192).map { TerminalLine.plain("row-$it") }
        controller.updateTerminalFrame(frame(screen = listOf(TerminalLine.plain("live"))))
        controller.stageTmuxHistory(
            tmuxSnapshot(newestPage, remoteRows = 8_192).copy(
                capturedStartRow = 4_096,
                truncatedBefore = true,
            ),
        )
        scheduler.drainAll()
        frameWork.clear()
        val olderPage = (0..4_096).map { TerminalLine.plain("row-$it") }
        controller.stageTmuxHistory(
            tmuxSnapshot(olderPage, remoteRows = 8_192).copy(
                capturedStartRow = 0,
                olderPage = true,
                authoritative = false,
            ),
        )

        scheduler.runNext()

        assertEquals(4_097, controller.lineCount())
        assertEquals("row-4096", controller.lineAt(0)?.text)
        scheduler.drainAll()

        assertEquals(8_193, controller.lineCount())
        assertEquals("row-0", controller.lineAt(0)?.text)
        assertEquals("row-8191", controller.lineAt(8_191)?.text)
        assertTrue(frameWork.size > 1)
        assertTrue(frameWork.all {
            it in 1..TerminalController.MAX_TMUX_RECONCILIATION_ROW_WORK_PER_FRAME
        })
    }

    @Test
    fun primaryHistoryExposesOldestMiddleAndNewestRowsThroughTheViewport() {
        assertHistoryReachable(alternate = false)
    }

    @Test
    fun alternateHistoryExposesOldestMiddleAndNewestRowsThroughTheViewport() {
        assertHistoryReachable(alternate = true)
    }

    @Test
    fun confirmedTmuxHistoryUsesTheFloatViewportAndRetainsLiveOutputWhileReading() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 512), scheduler).apply {
            viewport.updateGeometry(heightPx = 50, newLineHeightPx = 10f)
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
            )
        }
        val history = List(200) { index -> TerminalLine.plain("TMUX_%03d".format(index + 1)) }
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = history,
                remoteHistoryRows = history.size,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )
        controller.updateTerminalFrame(
            frame(
                screen = List(5) { index -> TerminalLine.plain("live-$index") },
                alternate = true,
            ),
        )
        scheduler.drainAll()
        controller.viewport.updateContent(controller.lineCount(), controller.oldestLineId())

        assertTrue(controller.isTmuxLocalScrollAvailable())
        assertEquals(205, controller.lineCount())
        assertEquals("TMUX_001", controller.lineAt(0)?.text)
        assertEquals("live-4", controller.lineAt(204)?.text)

        controller.markTmuxInteractionMetadataStale()
        assertTrue(controller.isTmuxLocalScrollAvailable())
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = emptyList(),
                remoteHistoryRows = history.size,
                remoteMousePassthrough = false,
                historyIncluded = false,
                authoritative = false,
                truncatedBefore = false,
            ),
        )
        scheduler.drainAll()
        assertTrue(controller.isTmuxLocalScrollAvailable())
        assertEquals("TMUX_001", controller.lineAt(0)?.text)

        controller.viewport.scrollBy(-17.4f)
        val fractionalScrollY = controller.viewport.scrollY
        assertFalse(controller.viewport.autoFollow)
        assertEquals(2.6f, fractionalScrollY % 10f, 0.001f)

        controller.updateTerminalFrame(
            frame(
                completed = listOf(TerminalLine.plain("new-history")),
                screen = List(5) { index -> TerminalLine.plain("new-live-$index") },
                alternate = true,
            ),
        )
        scheduler.drainAll()
        controller.viewport.updateContent(controller.lineCount(), controller.oldestLineId())

        assertEquals(fractionalScrollY, controller.viewport.scrollY, 0.001f)
        assertEquals("new-history", controller.lineAt(200)?.text)
        controller.jumpToBottom()
        assertTrue(controller.viewport.autoFollow)
        assertEquals(controller.viewport.maximumScrollY, controller.viewport.scrollY, 0.001f)
        assertEquals("new-live-4", controller.lineAt(controller.lineCount() - 1)?.text)
    }

    @Test
    fun confirmedTmuxPrimaryScreenHistoryUsesTheFloatViewport() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 512), scheduler).apply {
            viewport.updateGeometry(heightPx = 50, newLineHeightPx = 10f)
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
            )
        }
        val history = List(200) { index -> TerminalLine.plain("TMUX_PRIMARY_%03d".format(index + 1)) }

        controller.updateTerminalFrame(
            frame(
                screen = List(5) { index -> TerminalLine.plain("live-primary-$index") },
                alternate = false,
            ),
        )
        scheduler.drainAll()
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$40",
                paneId = "%50",
                lines = history,
                remoteHistoryRows = history.size,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )
        scheduler.drainAll()
        controller.viewport.updateContent(controller.lineCount(), controller.oldestLineId())

        assertTrue(controller.tmuxScrollDiagnostic(), controller.isTmuxLocalScrollAvailable())
        assertEquals(205, controller.lineCount())
        assertEquals("TMUX_PRIMARY_001", controller.lineAt(0)?.text)
        assertEquals("live-primary-4", controller.lineAt(204)?.text)

        controller.viewport.scrollBy(-17.4f)
        val fractionalScrollY = controller.viewport.scrollY
        assertFalse(controller.viewport.autoFollow)
        assertEquals(2.6f, fractionalScrollY % 10f, 0.001f)

        controller.updateTerminalFrame(
            frame(
                completed = listOf(TerminalLine.plain("new-primary-history")),
                screen = List(5) { index -> TerminalLine.plain("new-live-primary-$index") },
                alternate = false,
            ),
        )
        scheduler.drainAll()
        controller.viewport.updateContent(controller.lineCount(), controller.oldestLineId())

        assertTrue(controller.tmuxScrollDiagnostic(), controller.isTmuxLocalScrollAvailable())
        assertEquals(fractionalScrollY, controller.viewport.scrollY, 0.001f)
        assertEquals("new-primary-history", controller.lineAt(200)?.text)
        assertEquals("new-live-primary-4", controller.lineAt(controller.lineCount() - 1)?.text)
    }

    @Test
    fun tmuxMouseMetadataKeepsCapturedHistoryLocalWithoutPollutingItWithScreenRows() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
            )
        }
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = listOf(TerminalLine.plain("shell-history")),
                remoteHistoryRows = 1,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )
        controller.updateTerminalFrame(frame(alternate = true))
        scheduler.drainAll()
        assertTrue(controller.isTmuxLocalScrollAvailable())

        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = emptyList(),
                remoteHistoryRows = 1,
                remoteMousePassthrough = true,
                historyIncluded = false,
                authoritative = false,
                truncatedBefore = false,
            ),
        )
        scheduler.drainAll()
        controller.updateTerminalFrame(
            frame(
                completed = listOf(TerminalLine.plain("vim-screen-row")),
                alternate = true,
            ),
        )
        scheduler.drainAll()
        assertTrue(controller.isTmuxLocalScrollAvailable())

        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = listOf(
                    TerminalLine.plain("shell-history"),
                    TerminalLine.plain("after-vim"),
                ),
                remoteHistoryRows = 2,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = false,
                truncatedBefore = false,
            ),
        )
        scheduler.drainAll()

        assertTrue(controller.isTmuxLocalScrollAvailable())
        assertEquals(listOf("shell-history", "after-vim", "prompt"),
            (0 until controller.lineCount()).mapNotNull { controller.lineAt(it)?.text })
        assertFalse((0 until controller.lineCount()).any {
            controller.lineAt(it)?.text == "vim-screen-row"
        })
    }

    @Test
    fun tmuxMetadataCountMismatchRequestsOneFullHistoryRepair() {
        val scheduler = ManualFrameScheduler()
        val fullHistoryRequests = mutableListOf<Boolean>()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
                requestTmuxHistoryRefresh = fullHistoryRequests::add,
            )
        }
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = listOf(TerminalLine.plain("one")),
                remoteHistoryRows = 1,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )
        controller.updateTerminalFrame(frame(alternate = true))
        scheduler.drainAll()

        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = emptyList(),
                remoteHistoryRows = 2,
                remoteMousePassthrough = false,
                historyIncluded = false,
                authoritative = false,
                truncatedBefore = false,
            ),
        )
        scheduler.drainAll()

        assertTrue(controller.isTmuxLocalScrollAvailable())
        assertEquals(listOf(true), fullHistoryRequests)
    }

    @Test
    fun tmuxRepairPreservesFractionalReaderPositionAndPaneChangeReturnsLive() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            viewport.updateGeometry(heightPx = 50, newLineHeightPx = 10f)
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
            )
        }
        controller.addListener {
            controller.viewport.updateContent(controller.lineCount(), controller.oldestLineId())
        }
        val history = List(100) { index -> TerminalLine.plain("history-$index") }
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = history,
                remoteHistoryRows = history.size,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )
        controller.updateTerminalFrame(frame(alternate = true))
        scheduler.drainAll()
        controller.viewport.scrollTo(217.4f)

        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = history + TerminalLine.plain("repaired-tail"),
                remoteHistoryRows = history.size + 1,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )
        scheduler.drainAll()

        assertFalse(controller.viewport.autoFollow)
        assertEquals(217.4f, controller.viewport.scrollY, 0.001f)

        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%3",
                lines = listOf(TerminalLine.plain("other-pane")),
                remoteHistoryRows = 1,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )
        scheduler.drainAll()

        assertTrue(controller.viewport.autoFollow)
        assertEquals(controller.viewport.maximumScrollY, controller.viewport.scrollY, 0.001f)
    }

    @Test
    fun tmuxOlderPagePrependsInOrderAndPreservesExactVisiblePixelAnchor() {
        val scheduler = ManualFrameScheduler()
        val pageRequests = mutableListOf<com.yanjiyu.terminalspike.connection.TmuxHistoryPageRequest>()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            viewport.updateGeometry(heightPx = 20, newLineHeightPx = 10f)
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
                requestOlderTmuxHistory = pageRequests::add,
            )
            addListener {
                viewport.updateContent(lineCount(), oldestLineId())
            }
        }
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = (4..7).map { TerminalLine.plain("row-$it") },
                remoteHistoryRows = 8,
                capturedStartRow = 4,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = true,
            ),
        )
        controller.updateTerminalFrame(frame(screen = listOf(TerminalLine.plain("live")), alternate = true))
        scheduler.drainAll()
        controller.viewport.scrollTo(5.5f)

        controller.requestOlderTmuxHistoryIfNeeded()
        assertEquals(listOf(4), pageRequests.map { it.beforeRow })
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = (0..3).map { TerminalLine.plain("row-$it") } + TerminalLine.plain("shifted"),
                remoteHistoryRows = 8,
                capturedStartRow = 0,
                olderPage = true,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = false,
                truncatedBefore = false,
            ),
        )
        scheduler.drainAll()
        assertEquals(listOf("row-4", "row-5", "row-6", "row-7", "live"), controller.transcriptSnapshot().rows.map { it.line.text })
        assertEquals(5.5f, controller.viewport.scrollY, 0.001f)

        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = (0..4).map { TerminalLine.plain("row-$it") },
                remoteHistoryRows = 8,
                capturedStartRow = 0,
                olderPage = true,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = false,
                truncatedBefore = false,
            ),
        )
        scheduler.drainAll()

        assertEquals((0..7).map { "row-$it" } + "live", controller.transcriptSnapshot().rows.map { it.line.text })
        assertEquals(45.5f, controller.viewport.scrollY, 0.001f)
        controller.requestOlderTmuxHistoryIfNeeded()
        assertEquals(1, pageRequests.size)
    }

    @Test
    fun boundedTmuxRefreshRetainsPagedPrefixAndExactReaderAnchorWhileOutputGrows() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            viewport.updateGeometry(heightPx = 20, newLineHeightPx = 10f)
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
            )
            addListener {
                viewport.updateContent(lineCount(), oldestLineId())
            }
        }
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = (4..7).map { TerminalLine.plain("row-$it") },
                remoteHistoryRows = 8,
                capturedStartRow = 4,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = true,
            ),
        )
        controller.updateTerminalFrame(
            frame(screen = listOf(TerminalLine.plain("live")), alternate = true),
        )
        scheduler.drainAll()
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = (0..4).map { TerminalLine.plain("row-$it") },
                remoteHistoryRows = 8,
                capturedStartRow = 0,
                olderPage = true,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = false,
                truncatedBefore = false,
            ),
        )
        scheduler.drainAll()
        controller.viewport.scrollTo(25.5f)
        val readerAnchor = requireNotNull(controller.selectionLineAt(2)).anchor

        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = listOf(
                    TerminalLine.plain("row-5"),
                    TerminalLine.plain("row-6"),
                    TerminalLine.plain("updated-row-7"),
                    TerminalLine.plain("row-8"),
                ),
                remoteHistoryRows = 9,
                capturedStartRow = 5,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = true,
            ),
        )
        scheduler.drainAll()

        assertEquals(
            (0..6).map { "row-$it" } + listOf("updated-row-7", "row-8", "live"),
            controller.transcriptSnapshot().rows.map { it.line.text },
        )
        assertFalse(controller.viewport.autoFollow)
        assertEquals(25.5f, controller.viewport.scrollY, 0.001f)
        assertEquals(readerAnchor, requireNotNull(controller.selectionLineAt(2)).anchor)
    }

    @Test
    fun tmuxHistoryCoordinateResetKeepsOwnedTranscriptAndExactReaderAnchor() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            viewport.updateGeometry(heightPx = 20, newLineHeightPx = 10f)
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
            )
            addListener {
                viewport.updateContent(lineCount(), oldestLineId())
            }
        }
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = (0..7).map { TerminalLine.plain("row-$it") },
                remoteHistoryRows = 8,
                capturedStartRow = 0,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )
        controller.updateTerminalFrame(
            frame(screen = listOf(TerminalLine.plain("live")), alternate = true),
        )
        scheduler.drainAll()
        controller.viewport.scrollTo(25.5f)
        val readerAnchor = requireNotNull(controller.selectionLineAt(2)).anchor

        // tmux can reset its history coordinates after an inner full-screen program exits. The
        // overlapping rows identify the new epoch without surrendering app-owned transcript rows.
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = listOf(
                    TerminalLine.plain("row-6"),
                    TerminalLine.plain("row-7"),
                    TerminalLine.plain("row-8"),
                    TerminalLine.plain("row-9"),
                ),
                remoteHistoryRows = 4,
                capturedStartRow = 0,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )
        scheduler.drainAll()

        assertEquals(
            (0..9).map { "row-$it" } + "live",
            controller.transcriptSnapshot().rows.map { it.line.text },
        )
        assertFalse(controller.viewport.autoFollow)
        assertEquals(25.5f, controller.viewport.scrollY, 0.001f)
        assertEquals(readerAnchor, requireNotNull(controller.selectionLineAt(2)).anchor)

        // A reset without an overlap still archives the old epoch, and the next bounded refresh
        // must reconcile against only the current epoch rather than the archived prefix.
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = listOf(TerminalLine.plain("fresh-0"), TerminalLine.plain("fresh-1")),
                remoteHistoryRows = 2,
                capturedStartRow = 0,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )
        scheduler.drainAll()
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = listOf(
                    TerminalLine.plain("fresh-0"),
                    TerminalLine.plain("fresh-1"),
                    TerminalLine.plain("fresh-2"),
                ),
                remoteHistoryRows = 3,
                capturedStartRow = 0,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )
        scheduler.drainAll()

        assertEquals(
            (0..9).map { "row-$it" } + listOf("fresh-0", "fresh-1", "fresh-2", "live"),
            controller.transcriptSnapshot().rows.map { it.line.text },
        )
        assertFalse(controller.viewport.autoFollow)
        assertEquals(25.5f, controller.viewport.scrollY, 0.001f)
        assertEquals(readerAnchor, requireNotNull(controller.selectionLineAt(2)).anchor)
    }

    @Test
    fun captureOvertakenByTerminalOutputKeepsKnownHistoryLocalWhileMetadataRefreshes() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
            )
        }
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = listOf(TerminalLine.plain("captured-before-redraw")),
                remoteHistoryRows = 1,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
                interactionMetadataFresh = false,
            ),
        )
        controller.updateTerminalFrame(frame(alternate = true))
        scheduler.drainAll()

        assertTrue(controller.isTmuxLocalScrollAvailable())
        assertTrue(controller.hasTmuxLocalHistory())
    }

    @Test
    fun leavingAppSelectedTmuxCannotMisclassifyALaterShellApplication() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
            )
        }
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = listOf(TerminalLine.plain("tmux-history")),
                remoteHistoryRows = 1,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )
        controller.updateTerminalFrame(frame(alternate = true))
        scheduler.drainAll()
        assertTrue(controller.isTmuxLocalScrollAvailable())

        controller.updateTerminalFrame(frame(alternate = false))
        assertFalse(controller.isTmuxSession())
        scheduler.drainAll()

        controller.updateTerminalFrame(
            frame(
                completed = listOf(TerminalLine.plain("later-vim-row")),
                alternate = true,
            ),
        )
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = listOf(TerminalLine.plain("stale-tmux-history")),
                remoteHistoryRows = 1,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )
        scheduler.drainAll()

        assertFalse(controller.isTmuxSession())
        assertFalse(controller.isTmuxLocalScrollAvailable())
        assertTrue((0 until controller.lineCount()).any {
            controller.lineAt(it)?.text == "later-vim-row"
        })
        assertFalse((0 until controller.lineCount()).any {
            controller.lineAt(it)?.text == "stale-tmux-history"
        })
    }

    @Test
    fun selectingTmuxAgainAfterOuterExitRearmsPrimaryScreenHistory() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 32), scheduler).apply {
            setInputSink(
                sink = RecordingInputSink(),
                onResize = { _, _ -> },
                isTmuxSession = { true },
            )
        }
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$1",
                paneId = "%2",
                lines = listOf(TerminalLine.plain("old-tmux-history")),
                remoteHistoryRows = 1,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )
        controller.updateTerminalFrame(frame(alternate = true))
        scheduler.drainAll()
        controller.updateTerminalFrame(frame(alternate = false))
        scheduler.drainAll()
        assertFalse(controller.isTmuxSession())

        controller.beginManagedTmuxSession()
        controller.updateTerminalFrame(frame(alternate = false))
        controller.stageTmuxHistory(
            TmuxLocalHistorySnapshot(
                sessionId = "\$40",
                paneId = "%50",
                lines = listOf(TerminalLine.plain("resumed-primary-history")),
                remoteHistoryRows = 1,
                remoteMousePassthrough = false,
                historyIncluded = true,
                authoritative = true,
                truncatedBefore = false,
            ),
        )
        scheduler.drainAll()

        assertTrue(controller.tmuxScrollDiagnostic(), controller.isTmuxLocalScrollAvailable())
        assertEquals("resumed-primary-history", controller.lineAt(0)?.text)
    }

    private fun assertHistoryReachable(alternate: Boolean) {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 512), scheduler)
        controller.viewport.updateGeometry(heightPx = 50, newLineHeightPx = 10f)
        val completed = List(200) { index -> TerminalLine.plain("history-$index") }
        val screen = List(5) { index -> TerminalLine.plain("screen-$index") }
        val expected = completed.map(TerminalLine::text) + screen.map(TerminalLine::text)

        controller.updateTerminalFrame(
            frame(completed = completed, screen = screen, alternate = alternate),
        )
        scheduler.drainAll()
        controller.viewport.updateContent(controller.lineCount(), controller.oldestLineId())

        assertEquals(expected.size, controller.lineCount())

        controller.viewport.scrollTo(0f)
        assertEquals(0, controller.viewport.visibleRows(overscan = 0).first)
        assertEquals(expected.first(), controller.lineAt(0)?.text)

        controller.viewport.scrollTo(controller.viewport.maximumScrollY / 2f)
        val middleRow = controller.viewport.visibleRows(overscan = 0).first
        assertTrue(middleRow in 1 until expected.lastIndex)
        assertEquals(expected[middleRow], controller.lineAt(middleRow)?.text)

        controller.viewport.jumpToBottom()
        assertEquals(
            controller.lineCount(),
            controller.viewport.visibleRows(overscan = 0).lastExclusive,
        )
        assertEquals(expected.last(), controller.lineAt(controller.lineCount() - 1)?.text)
    }

    @Test
    fun clearLocalScrollbackPreservesLiveScreenAndTransportAndInvalidatesReferences() {
        val scheduler = ManualFrameScheduler()
        val sink = RecordingInputSink()
        val controller = TerminalController(TerminalBuffer(capacity = 4), scheduler).apply {
            setInputSink(sink, onResize = { _, _ -> })
            updateTerminalFrame(
                frame(
                    completed = listOf(TerminalLine.plain("old history")),
                    screen = listOf(TerminalLine.plain("live prompt")),
                ),
            )
        }
        scheduler.drainAll()
        val before = controller.transcriptSnapshot()
        val beforeFind = findTerminalText(before, "old")
        assertEquals(2, before.rows.size)
        assertEquals(TerminalLineSpace.PRIMARY_HISTORY, before.rows.first().anchor.space)

        // This captured history predates clear but has not reached the renderer frame yet.
        controller.updateTerminalFrame(
            frame(
                completed = listOf(TerminalLine.plain("queued history")),
                screen = listOf(TerminalLine.plain("new live prompt")),
            ),
        )
        controller.clearLocalScrollback()

        val immediatelyAfter = controller.transcriptSnapshot()
        assertEquals(listOf("live prompt"), immediatelyAfter.rows.map { it.line.text })
        assertNotEquals(beforeFind.revision, controller.contentRevision)
        assertTrue(controller.sendWithAcceptance(byteArrayOf('x'.code.toByte())))
        assertArrayEquals(byteArrayOf('x'.code.toByte()), sink.received.single())

        scheduler.drainAll()
        val afterPendingScreen = controller.transcriptSnapshot()
        assertEquals(listOf("new live prompt"), afterPendingScreen.rows.map { it.line.text })
        assertFalse(afterPendingScreen.rows.any { it.line.text == "queued history" })
    }

    @Test
    fun transcriptSnapshotIsBoundedAndVolatileScreenUsesCapturedRevision() {
        val scheduler = ManualFrameScheduler()
        val buffer = TerminalBuffer(capacity = TerminalTranscriptSnapshot.MAX_LINES + 10).apply {
            append(
                List(TerminalTranscriptSnapshot.MAX_LINES + 10) { index -> TerminalLine.plain("line-$index") },
            )
        }
        val controller = TerminalController(
            buffer = buffer,
            frameScheduler = scheduler,
        )
        controller.updateTerminalFrame(frame(screen = listOf(TerminalLine.plain("volatile"))))
        scheduler.drainAll()

        val snapshot = controller.transcriptSnapshot()

        assertEquals(TerminalTranscriptSnapshot.MAX_LINES, snapshot.rows.size)
        assertTrue(snapshot.truncatedBefore)
        assertEquals("volatile", snapshot.rows.last().line.text)
        assertEquals(TerminalLineSpace.VOLATILE_SCREEN, snapshot.rows.last().anchor.space)
        assertEquals(snapshot.revision, snapshot.rows.last().anchor.id)
    }

    @Test
    fun alternateSnapshotUsesSeparateHistoryAndTrimmedPrimaryFindAnchorCannotRetarget() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 2), scheduler)
        controller.updateTerminalFrame(
            frame(
                completed = listOf(TerminalLine.plain("oldest"), TerminalLine.plain("newer")),
                screen = listOf(TerminalLine.plain("primary")),
            ),
        )
        scheduler.drainAll()
        val primaryMatch = findTerminalText(controller.transcriptSnapshot(), "oldest")
            .matches.single().segments.single()

        controller.updateTerminalFrame(
            frame(
                completed = listOf(TerminalLine.plain("newest")),
                screen = listOf(TerminalLine.plain("primary")),
            ),
        )
        scheduler.drainAll()
        assertNull(controller.selectionIndexOf(primaryMatch.line))

        controller.updateTerminalFrame(
            frame(
                completed = listOf(TerminalLine.plain("alternate history")),
                screen = listOf(TerminalLine.plain("alternate live")),
                alternate = true,
            ),
        )
        scheduler.drainAll()
        val alternate = controller.transcriptSnapshot()
        assertEquals(listOf("alternate history", "alternate live"), alternate.rows.map { it.line.text })
        assertEquals(TerminalLineSpace.ALTERNATE_HISTORY, alternate.rows.first().anchor.space)
        assertEquals(TerminalLineSpace.VOLATILE_SCREEN, alternate.rows.last().anchor.space)
    }

    @Test
    fun bellFramesCoalesceBeforePublishAndListenersNeverReceiveRegistrationReplay() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(), scheduler)
        val firstOwner = mutableListOf<TerminalBellEvent>()
        val laterOwner = mutableListOf<TerminalBellEvent>()
        controller.addBellListener(firstOwner::add)

        controller.updateTerminalFrame(frame(bellSequence = 1, bellCount = 1))
        controller.updateTerminalFrame(frame(bellSequence = 3, bellCount = 2))
        scheduler.drainAll()

        assertEquals(listOf(TerminalBellEvent(sequence = 3, count = 3)), firstOwner)
        controller.addBellListener(laterOwner::add)
        assertTrue(laterOwner.isEmpty())
        assertEquals(3L, controller.latestBellSequence())
    }

    @Test
    fun remoteEd3RetainsAppOwnedPrimaryHistory() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 8), scheduler)
        controller.updateTerminalFrame(
            frame(
                completed = listOf(TerminalLine.plain("old-1"), TerminalLine.plain("old-2")),
                screen = listOf(TerminalLine.plain("prompt")),
            ),
        )
        scheduler.drainAll()
        val changes = mutableListOf<TerminalContentChange>()
        controller.addListener(changes::add)
        changes.clear()

        controller.updateTerminalFrame(
            frame(
                screen = listOf(TerminalLine.plain("prompt")),
                clearScrollback = true,
            ),
        )
        controller.updateTerminalFrame(
            frame(
                completed = listOf(TerminalLine.plain("after-clear")),
                screen = listOf(TerminalLine.plain("prompt")),
            ),
        )
        scheduler.drainAll()

        assertEquals(
            listOf("old-1", "old-2", "after-clear", "prompt"),
            controller.transcriptSnapshot().rows.map { it.line.text },
        )
        assertEquals("prompt", controller.lineAt(controller.lineCount() - 1)?.text)
        assertEquals(6, controller.cursor.column)
        assertTrue(controller.cursor.visible)
        assertTrue(changes.single().fullRedraw)
    }

    @Test
    fun remoteEd3RetainsAppOwnedAlternateAndPrimaryHistory() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 8), scheduler)
        controller.updateTerminalFrame(
            frame(
                completed = listOf(TerminalLine.plain("primary-history")),
                screen = listOf(TerminalLine.plain("primary-screen")),
            ),
        )
        scheduler.drainAll()
        controller.updateTerminalFrame(
            frame(
                completed = listOf(TerminalLine.plain("alternate-history")),
                screen = listOf(TerminalLine.plain("alternate-screen")),
                alternate = true,
            ),
        )
        scheduler.drainAll()

        controller.updateTerminalFrame(
            frame(
                screen = listOf(TerminalLine.plain("alternate-screen")),
                alternate = true,
                clearScrollback = true,
            ),
        )
        scheduler.drainAll()
        assertEquals(
            listOf("alternate-history", "alternate-screen"),
            controller.transcriptSnapshot().rows.map { it.line.text },
        )

        controller.updateTerminalFrame(frame(screen = listOf(TerminalLine.plain("primary-screen"))))
        scheduler.drainAll()
        assertEquals(
            listOf("primary-history", "primary-screen"),
            controller.transcriptSnapshot().rows.map { it.line.text },
        )
    }

    @Test
    fun coalescedTerminalFramesPublishTheUnionOfAbsoluteDirtyRows() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(), scheduler)
        val initial = listOf(
            TerminalLine.plain("a"),
            TerminalLine.plain("b"),
            TerminalLine.plain("c"),
        )
        controller.updateTerminalFrame(frame(screen = initial))
        scheduler.drainAll()
        val changes = mutableListOf<TerminalContentChange>()
        controller.addListener(changes::add)
        changes.clear()

        controller.updateTerminalFrame(
            frame(
                screen = listOf(TerminalLine.plain("A"), initial[1], initial[2]),
                dirtyRows = intArrayOf(0),
            ),
        )
        controller.updateTerminalFrame(
            frame(
                screen = listOf(TerminalLine.plain("A"), initial[1], TerminalLine.plain("C")),
                dirtyRows = intArrayOf(2),
            ),
        )
        scheduler.drainAll()

        val change = changes.single()
        assertFalse(change.fullRedraw)
        assertArrayEquals(intArrayOf(0, 2), change.dirtyRows)
    }

    @Test
    fun cursorPresentationChangesInvalidateOnlyItsRowAndModeOnlyFramesInvalidateNoRows() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(), scheduler)
        val screen = listOf(
            TerminalLine.plain("a"),
            TerminalLine.plain("b"),
            TerminalLine.plain("c"),
        )
        val baseCursor = TerminalCursor(row = 1, column = 0, visible = true)
        controller.updateTerminalFrame(frame(screen = screen, cursor = baseCursor))
        scheduler.drainAll()
        val changes = mutableListOf<TerminalContentChange>()
        controller.addListener(changes::add)
        changes.clear()

        val remoteCursor = baseCursor.copy(styleOverride = CursorStyle.BEAM, blinkOverride = false)
        controller.updateTerminalFrame(frame(screen = screen, cursor = remoteCursor))
        scheduler.drainAll()

        assertFalse(changes.single().fullRedraw)
        assertArrayEquals(intArrayOf(1), changes.single().dirtyRows)
        changes.clear()

        controller.updateTerminalFrame(
            frame(
                screen = screen,
                cursor = remoteCursor,
                modes = TerminalModes(applicationKeypad = true, insertMode = true),
            ),
        )
        scheduler.drainAll()

        assertFalse(changes.single().fullRedraw)
        assertTrue(changes.single().dirtyRows.isEmpty())
    }

    @Test
    fun deckpamChangesKeypadEncodingBeforeTheNextRenderFrame() {
        val scheduler = ManualFrameScheduler()
        val sink = RecordingInputSink()
        val controller = TerminalController(TerminalBuffer(), scheduler).apply {
            setInputSink(sink, onResize = { _, _ -> })
        }
        val normal = byteArrayOf('0'.code.toByte())
        val application = byteArrayOf(0x1b, 0x4f, 0x70)
        controller.updateTerminalFrame(frame(modes = TerminalModes()))
        scheduler.drainAll()

        assertTrue(controller.sendKeypad(normal, application))
        controller.updateTerminalFrame(frame(modes = TerminalModes(applicationKeypad = true)))
        assertTrue(controller.sendKeypad(normal, application))
        controller.updateTerminalFrame(frame(modes = TerminalModes(applicationKeypad = false)))
        assertTrue(controller.sendKeypad(normal, application))
        scheduler.drainAll()

        assertArrayEquals(normal, sink.received[0])
        assertArrayEquals(application, sink.received[1])
        assertArrayEquals(normal, sink.received[2])
    }

    private fun frame(
        completed: List<TerminalLine> = emptyList(),
        screen: List<TerminalLine> = listOf(TerminalLine.plain("prompt")),
        bellSequence: Long = 0L,
        bellCount: Int = 0,
        alternate: Boolean = false,
        clearScrollback: Boolean = false,
        dirtyRows: IntArray = IntArray(0),
        cursor: TerminalCursor = TerminalCursor(
            row = 0,
            column = screen.firstOrNull()?.text?.length ?: 0,
            visible = true,
        ),
        modes: TerminalModes = TerminalModes(),
    ) = TerminalFrameUpdate(
        completedScrollback = completed,
        screen = screen,
        cursor = cursor,
        alternateScreen = alternate,
        modes = modes,
        bellSequence = bellSequence,
        bellCount = bellCount,
        clearScrollbackRequested = clearScrollback,
        dirtyRows = dirtyRows,
    )

    private fun tmuxSnapshot(
        lines: List<TerminalLine>,
        remoteRows: Int,
        paneId: String = "%2",
    ) = TmuxLocalHistorySnapshot(
        sessionId = "\$1",
        paneId = paneId,
        lines = lines,
        remoteHistoryRows = remoteRows,
        remoteMousePassthrough = false,
        historyIncluded = true,
        authoritative = true,
        truncatedBefore = false,
    )

    private class RecordingInputSink : TerminalInputSink {
        val received = mutableListOf<ByteArray>()

        override fun send(bytes: ByteArray) {
            received += bytes.copyOf()
        }
    }

    private class ManualFrameScheduler : TerminalFrameScheduler {
        private val callbacks = ArrayDeque<Choreographer.FrameCallback>()

        override fun postFrame(callback: Choreographer.FrameCallback) {
            callbacks.addLast(callback)
        }

        fun hasPendingFrames(): Boolean = callbacks.isNotEmpty()

        fun runNext(frameTimeNanos: Long = 1L) {
            callbacks.removeFirst().doFrame(frameTimeNanos)
        }

        fun drainAll() {
            var frame = 0L
            while (callbacks.isNotEmpty()) {
                callbacks.removeFirst().doFrame(++frame)
            }
        }
    }
}
