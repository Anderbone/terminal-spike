package com.yanjiyu.terminalspike.terminal.engine

import org.junit.Assert.*
import org.junit.Test

class TerminalSynchronizedOutputTest {
    @Test fun reportsCapabilityAndTracksSplitMarkersWithoutResettingTheGeneration() {
        val engine = VtTerminalEngine(80, 4)
        fun feed(text: String): TerminalFrameUpdate {
            var result = engine.accept(byteArrayOf())
            for (byte in text.toByteArray()) result = engine.accept(byteArrayOf(byte))
            return result
        }
        assertEquals("\u001b[?2026;2\$y", feed("\u001b[?2026\$p").responses.single().decodeToString())
        val start = feed("\u001b[?2026h")
        assertTrue(start.synchronizedOutput)
        assertEquals(1L, start.synchronizedOutputGeneration)
        assertEquals("\u001b[?2026;1\$y", feed("\u001b[?2026\$p").responses.single().decodeToString())
        assertEquals(1L, feed("\u001b[?2026h").synchronizedOutputGeneration)
        val end = feed("\u001b[?2026l")
        assertFalse(end.synchronizedOutput)
        assertTrue(end.synchronizedOutputCompleted)
        assertFalse(engine.accept(byteArrayOf()).synchronizedOutputCompleted)
        assertEquals(2L, feed("\u001b[?2026h").synchronizedOutputGeneration)
        engine.reset()
        assertFalse(engine.accept(byteArrayOf()).synchronizedOutput)
        assertEquals(3L, feed("\u001b[?2026h").synchronizedOutputGeneration)
    }

    @Test fun resetAndResizeDoNotReplayTheOneShotCompletionOrLoseModeState() {
        val engine = VtTerminalEngine()
        engine.accept("\u001b[?2026h".toByteArray())
        assertTrue(engine.resize(40, 12).synchronizedOutput)
        val end = engine.accept("\u001b[?2026l".toByteArray())
        assertTrue(end.synchronizedOutputCompleted)
        assertFalse(engine.resize(40, 13).synchronizedOutputCompleted)
        assertFalse(engine.reset().synchronizedOutputCompleted)
    }
}
