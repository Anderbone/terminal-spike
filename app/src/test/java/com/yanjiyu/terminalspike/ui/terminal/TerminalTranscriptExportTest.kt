package com.yanjiyu.terminalspike.ui.terminal

import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTranscriptExportTest {
    @Test
    fun streamsUtf8AndClosesProviderOutput() {
        val output = TrackingOutputStream()

        val result = writeTerminalTranscriptDocument(
            lines = listOf(TerminalLine.plain("héllo"), TerminalLine.plain("世界")),
            openOutput = { output },
        ).getOrThrow()

        assertEquals("héllo\n世界\n", output.bytes.decodeToString())
        assertEquals(2, result.lineCount)
        assertTrue(output.closed)
        assertFalse(result.truncated)
    }

    @Test
    fun providerFailureIsGenericAndAlreadyOpenedOutputStillCloses() {
        val output = TrackingOutputStream(failWrites = true)

        assertTrue(
            writeTerminalTranscriptDocument(
                lines = listOf(TerminalLine.plain("private terminal text")),
                openOutput = { output },
            ).isFailure,
        )
        assertTrue(output.closed)
        assertTrue(
            writeTerminalTranscriptDocument(
                lines = emptyList(),
                openOutput = { null },
            ).isFailure,
        )
    }

    @Test
    fun byteBoundTruncatesWithoutPartialLine() {
        val output = TrackingOutputStream()

        val result = writeTerminalTranscriptDocument(
            lines = listOf(TerminalLine.plain("first"), TerminalLine.plain("second")),
            openOutput = { output },
            maxBytes = 6,
        ).getOrThrow()

        assertEquals("first\n", output.bytes.decodeToString())
        assertEquals(1, result.lineCount)
        assertTrue(result.truncated)
        assertTrue(output.closed)
    }

    @Test
    fun suggestedNameContainsNoSessionMetadata() {
        assertEquals("terminal-transcript.txt", TERMINAL_TRANSCRIPT_FILE_NAME)
        assertFalse(TERMINAL_TRANSCRIPT_FILE_NAME.contains('@'))
        assertFalse(TERMINAL_TRANSCRIPT_FILE_NAME.any(Char::isDigit))
    }

    private class TrackingOutputStream(
        private val failWrites: Boolean = false,
    ) : ByteArrayOutputStream() {
        var closed = false
            private set

        val bytes: ByteArray
            get() = toByteArray()

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (failWrites) throw IOException("provider failed")
            super.write(buffer, offset, length)
        }

        override fun write(value: Int) {
            if (failWrites) throw IOException("provider failed")
            super.write(value)
        }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
