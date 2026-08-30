package com.yanjiyu.terminalspike.terminal

import android.view.Choreographer
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalControllerTmuxProtocolTest {
    @Test
    fun coalescedRealTmuxFramesKeepTheOldestCopyModeRowOnScreen() {
        val scheduler = ManualFrameScheduler()
        val controller = TerminalController(TerminalBuffer(capacity = 512), scheduler)
        val engine = VtTerminalEngine(columns = 80, rows = TERMINAL_ROWS)

        capturedProtocol().asList().chunked(SSH_READ_BUFFER_BYTES).forEach { chunk ->
            controller.updateTerminalFrame(engine.accept(chunk.toByteArray()))
        }
        scheduler.drainAll()

        val firstScreenRow = controller.lineCount() - TERMINAL_ROWS
        assertEquals(
            "PROTOCOL_SCROLL_001",
            controller.lineAt(firstScreenRow)?.text?.take("PROTOCOL_SCROLL_001".length),
        )
    }

    private fun capturedProtocol(): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/tmux-copy-mode-scroll.bin.gz"),
    ).use { compressed ->
        GZIPInputStream(compressed).use { it.readBytes() }
    }

    private class ManualFrameScheduler : TerminalFrameScheduler {
        private val callbacks = ArrayDeque<Choreographer.FrameCallback>()

        override fun postFrame(callback: Choreographer.FrameCallback) {
            callbacks.addLast(callback)
        }

        fun drainAll() {
            var frameTime = 0L
            while (callbacks.isNotEmpty()) callbacks.removeFirst().doFrame(++frameTime)
        }
    }

    private companion object {
        const val SSH_READ_BUFFER_BYTES = 8 * 1024
        const val TERMINAL_ROWS = 24
    }
}
