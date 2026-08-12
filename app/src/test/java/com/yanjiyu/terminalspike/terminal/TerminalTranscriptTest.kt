package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineSpace
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTranscriptTest {
    @Test
    fun transcriptStreamsUtf8WithStableLineBreaksWithoutClosingCallerStream() {
        val output = TrackingOutputStream()

        val result = TerminalTranscriptWriter.write(
            lines = listOf(TerminalLine.plain("one"), TerminalLine.plain("界")),
            output = output,
        )

        assertEquals("one\n界\n", output.toString(Charsets.UTF_8.name()))
        assertEquals(2, result.lineCount)
        assertEquals(output.size().toLong(), result.byteCount)
        assertFalse(result.truncated)
        assertFalse(output.closed)
    }

    @Test
    fun transcriptStopsBeforeConfiguredBound() {
        val output = ByteArrayOutputStream()

        val result = TerminalTranscriptWriter.write(
            lines = listOf(TerminalLine.plain("1234"), TerminalLine.plain("next")),
            output = output,
            maxBytes = 5,
        )

        assertEquals("1234\n", output.toString(Charsets.UTF_8.name()))
        assertEquals(1, result.lineCount)
        assertTrue(result.truncated)
    }

    @Test
    fun transcriptJoinsSoftWrappedRowsWithoutInventingLineBreaks() {
        val output = ByteArrayOutputStream()

        TerminalTranscriptWriter.write(
            lines = listOf(
                TerminalLine.plain("wrapped ", softWrappedToNext = true),
                TerminalLine.plain("line"),
            ),
            output = output,
        )

        assertEquals("wrapped line\n", output.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun findIsLiteralCaseSelectableWhitespaceSafeAndBounded() {
        val snapshot = TerminalTranscriptSnapshot.fromLines(
            lines = listOf(TerminalLine.plain("Alpha alpha  "), TerminalLine.plain("ALPHA")),
            revision = 7L,
        )

        val insensitive = findTerminalText(
            snapshot = snapshot,
            query = "alpha",
            maxMatches = 2,
        )

        assertEquals(7L, insensitive.revision)
        assertEquals(2, insensitive.matches.size)
        assertEquals(listOf(0, 6), insensitive.matches.map { it.segments.single().startColumn })
        assertTrue(insensitive.truncated)
        assertEquals(
            1,
            findTerminalText(snapshot, query = "Alpha", caseSensitive = true).matches.size,
        )
        assertEquals(
            1,
            findTerminalText(snapshot, query = "  ").matches.size,
        )
    }

    @Test
    fun findCrossesSoftWrapWithUnicodeCellRangesButNotHardLineBreaks() {
        val snapshot = TerminalTranscriptSnapshot.fromLines(
            listOf(
                TerminalLine.plain("deploy ", softWrappedToNext = true),
                TerminalLine.plain("🚀 READY"),
                TerminalLine.plain("deploy "),
                TerminalLine.plain("🚀 READY"),
            ),
            revision = 11L,
        )

        val result = findTerminalText(snapshot, "y 🚀")

        assertEquals(1, result.matches.size)
        assertEquals(
            listOf(5 to 7, 0 to 2),
            result.matches.single().segments.map { it.startColumn to it.endColumnExclusive },
        )
        assertTrue(result.matches.single().segments.all { it.line.id == 11L })
        assertTrue(result.matches.single().segments.all { it.line.space == TerminalLineSpace.VOLATILE_SCREEN })
    }

    @Test
    fun findRejectsOversizedQueryAndBoundsScannedCharacters() {
        val snapshot = TerminalTranscriptSnapshot.fromLines(
            listOf(TerminalLine.plain("alpha"), TerminalLine.plain("target")),
            revision = 3L,
        )

        val tooLong = findTerminalText(snapshot, "x".repeat(DEFAULT_MAX_QUERY_LENGTH + 1))
        val scanBound = findTerminalText(snapshot, "target", maxScannedCharacters = 5)

        assertTrue(tooLong.queryTooLong)
        assertTrue(tooLong.matches.isEmpty())
        assertTrue(scanBound.truncated)
        assertTrue(scanBound.matches.isEmpty())
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
