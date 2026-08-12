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
    fun ed3ClearsOnlyPriorPrimaryHistoryAndRetainsPostClearRowsAndLiveScreen() {
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

        assertEquals(listOf("after-clear", "prompt"), controller.transcriptSnapshot().rows.map { it.line.text })
        assertEquals("prompt", controller.lineAt(controller.lineCount() - 1)?.text)
        assertEquals(6, controller.cursor.column)
        assertTrue(controller.cursor.visible)
        assertTrue(changes.single().fullRedraw)
    }

    @Test
    fun alternateEd3DoesNotErasePrimaryHistory() {
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
        assertEquals(listOf("alternate-screen"), controller.transcriptSnapshot().rows.map { it.line.text })

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

        fun drainAll() {
            var frame = 0L
            while (callbacks.isNotEmpty()) {
                callbacks.removeFirst().doFrame(++frame)
            }
        }
    }
}
