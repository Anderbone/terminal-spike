package com.yanjiyu.terminalspike.terminal.engine

import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxCopyModeProtocolTest {
    @Test
    fun realTmuxMouseScrollRepaintsTheOldestRows() {
        val captured = capturedProtocol()
        val update = VtTerminalEngine(columns = 80, rows = 24).accept(captured)

        assertOldestRowVisible(update)
    }

    @Test
    fun realTmuxMouseScrollSurvivesSshReadChunkBoundaries() {
        val engine = VtTerminalEngine(columns = 80, rows = 24)
        var update = engine.accept(byteArrayOf())

        capturedProtocol().asList().chunked(SSH_READ_BUFFER_BYTES).forEach { chunk ->
            update = engine.accept(chunk.toByteArray())
        }

        assertOldestRowVisible(update)
    }

    private fun capturedProtocol(): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/tmux-copy-mode-scroll.bin.gz"),
    ).use { compressed ->
        GZIPInputStream(compressed).use { it.readBytes() }
    }

    private fun assertOldestRowVisible(update: TerminalFrameUpdate) {
        assertTrue("The real tmux fixture must enter the alternate screen.", update.alternateScreen)
        assertEquals(
            "The real tmux fixture must remain in copy mode at its oldest retained row.",
            "PROTOCOL_SCROLL_001",
            update.screen.first().text.take("PROTOCOL_SCROLL_001".length),
        )
    }

    private companion object {
        const val SSH_READ_BUFFER_BYTES = 8 * 1024
    }
}
