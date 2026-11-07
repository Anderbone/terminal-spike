package com.yanjiyu.terminalspike.terminal

import android.view.Choreographer
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import org.junit.Assert.*
import org.junit.Test

class TerminalSynchronizedPresentationTest {
    @Test fun continuouslyChangingGenerationsCannotExtendThePresentationDeadline() {
        val clock = Clock()
        val controller = TerminalController(TerminalBuffer(), clock)
        val engine = VtTerminalEngine(40, 4)
        fun feed(text: String) { controller.updateTerminalFrame(engine.accept(text.toByteArray())); clock.drain() }
        feed("OLD")
        feed("\u001b[?2026h\u001b[2J\u001b[HFIRST")
        clock.advance(900)
        feed("\u001b[?2026l\u001b[?2026h\u001b[2J\u001b[HSECOND")
        assertEquals("OLD", controller.lineAt(0)?.text)
        clock.advance(100)
        assertEquals("SECOND", controller.lineAt(0)?.text)
    }
    @Test fun oneByteTransportKeepsOldDisplayAndPublishesAllTwoHundredRowsAtEnd() {
        val clock = Clock()
        val controller = TerminalController(TerminalBuffer(), clock)
        val engine = VtTerminalEngine(40, 4)
        fun feed(text: String) { controller.updateTerminalFrame(engine.accept(text.toByteArray())); clock.drain() }
        feed("OLD")
        feed("\u001b[?2026h")
        val revision = controller.renderedOutputRevision
        val update = "\u001b[2J\u001b[H" +
            (1..200).joinToString("\r\n") { "FRAME_$it" }
        for (byte in update.toByteArray()) {
            controller.updateTerminalFrame(engine.accept(byteArrayOf(byte)))
            clock.drain()
            assertEquals("OLD", controller.lineAt(0)?.text)
            assertEquals(revision, controller.renderedOutputRevision)
        }
        feed("\u001b[?2026l")
        val text = (0 until controller.lineCount()).mapNotNull { controller.lineAt(it)?.text }
        assertEquals((1..200).map { "FRAME_$it" }, text)
        assertEquals(controller.renderedOutputRevision, controller.renderedSynchronizedOutputRevision)
    }

    @Test fun missingEndReleasesAtDeadlineAndDoesNotRefreezeTheSameOpenBatch() {
        val clock = Clock()
        val controller = TerminalController(TerminalBuffer(), clock)
        val engine = VtTerminalEngine(40, 4)
        fun feed(text: String) { controller.updateTerminalFrame(engine.accept(text.toByteArray())); clock.drain() }
        feed("OLD")
        feed("\u001b[?2026h\u001b[2J\u001b[HNEW")
        clock.advance(900)
        feed("\u001b[?2026h") // repeated BSU cannot extend the deadline
        clock.advance(99)
        assertEquals("OLD", controller.lineAt(0)?.text)
        clock.advance(1)
        assertEquals("NEW", controller.lineAt(0)?.text)
        feed("!")
        assertEquals("NEW!", controller.lineAt(0)?.text)
        // A callback with no terminal frame must not reset the timed-out generation.
        controller.clear()
        feed("?")
        assertTrue(controller.lineAt(0)?.text?.contains("NEW!?") == true)
        feed("\u001b[?2026l\u001b[?2026h\u001b[2J\u001b[HSECOND")
        assertTrue(controller.lineAt(0)?.text?.contains("NEW!?") == true)
        feed("\u001b[?2026l")
        assertEquals("SECOND", controller.lineAt(0)?.text)
    }

    @Test fun excessivePendingHistoryReleasesWithoutDroppingOrderedRows() {
        val clock = Clock()
        val controller = TerminalController(TerminalBuffer(), clock)
        val engine = VtTerminalEngine(40, 4)
        controller.updateTerminalFrame(engine.accept(("\u001b[?2026h" +
            (1..4_100).joinToString("\r\n") { "ROW_$it" }).toByteArray()))
        clock.drain()
        assertEquals((1..4_100).map { "ROW_$it" },
            (0 until controller.lineCount()).map { controller.lineAt(it)?.text })
        assertEquals(0, clock.delayed.size)
    }

    private class Clock : TerminalFrameScheduler {
        var now = 0L
        private val frames = ArrayDeque<Choreographer.FrameCallback>()
        val delayed = mutableListOf<Pair<Long, Choreographer.FrameCallback>>()
        override fun uptimeMillis() = now
        override fun postFrame(callback: Choreographer.FrameCallback) { frames.add(callback) }
        override fun postDelayedFrame(callback: Choreographer.FrameCallback, delayMillis: Long) {
            delayed.add(now + delayMillis to callback)
        }
        fun drain() { while (frames.isNotEmpty()) frames.removeFirst().doFrame(now * 1_000_000L) }
        fun advance(millis: Long) {
            now += millis
            val due = delayed.filter { it.first <= now }
            delayed.removeAll(due.toSet())
            due.forEach { frames.add(it.second) }
            drain()
        }
    }
}
