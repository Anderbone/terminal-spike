package com.yanjiyu.terminalspike.terminal.engine

import com.yanjiyu.terminalspike.terminal.model.TerminalPalette
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VtTerminalEngineTest {
    @Test
    fun preservesUtf8AcrossNetworkReadsAndCombiningMarks() {
        val engine = VtTerminalEngine(columns = 20, rows = 3)
        val bytes = "A🚀e\u0301".toByteArray(Charsets.UTF_8)

        engine.accept(bytes.copyOfRange(0, bytes.size - 2))
        val update = engine.accept(bytes.copyOfRange(bytes.size - 2, bytes.size))

        assertEquals("A🚀e\u0301", update.screen[0].text)
        assertEquals(4, update.cursor.column)
    }

    @Test
    fun scrollsPrimaryScreenIntoScrollback() {
        val engine = VtTerminalEngine(columns = 8, rows = 2)

        val update = engine.accept("one\r\ntwo\r\nthree".bytes())

        assertEquals(listOf("one"), update.completedScrollback.map { it.text })
        assertEquals("two", update.screen[0].text)
        assertEquals("three", update.screen[1].text)
    }

    @Test
    fun cursorAddressingAndEraseWorkOnScreenGrid() {
        val engine = VtTerminalEngine(columns = 8, rows = 3)

        val update = engine.accept("first\u001B[2;3HXY\u001B[1K".bytes())

        assertEquals("first", update.screen[0].text)
        assertEquals("", update.screen[1].text)
        assertEquals(1, update.cursor.row)
        assertEquals(4, update.cursor.column)
    }

    @Test
    fun alternateScreenReportsSeparateScrollableHistoryAndRestoresPrimary() {
        val engine = VtTerminalEngine(columns = 12, rows = 2)
        engine.accept("primary".bytes())

        val alternate = engine.accept("\u001B[?1049hfull\r\nscreen\r\nmore".bytes())
        val restored = engine.accept("\u001B[?1049l".bytes())

        assertTrue(alternate.alternateScreen)
        assertEquals(listOf("full"), alternate.completedScrollback.map { it.text })
        assertFalse(restored.alternateScreen)
        assertEquals("primary", restored.screen[0].text)
    }

    @Test
    fun alternateTopAnchoredScrollRegionReportsTmuxStyleHistory() {
        val engine = VtTerminalEngine(columns = 10, rows = 3)
        engine.accept("\u001B[?1049htop\r\nmiddle\r\nstatus".bytes())

        val update = engine.accept("\u001B[1;2r\u001B[2;1H\nnext".bytes())

        assertTrue(update.alternateScreen)
        assertEquals(listOf("top"), update.completedScrollback.map { it.text })
        assertEquals("middle", update.screen[0].text)
        assertEquals("next", update.screen[1].text)
        assertEquals("status", update.screen[2].text)
    }

    @Test
    fun scrollRegionKeepsRowsOutsideMarginsStable() {
        val engine = VtTerminalEngine(columns = 8, rows = 4)
        engine.accept("top\r\naaa\r\nbbb\r\nbottom".bytes())

        val update = engine.accept("\u001B[2;3r\u001B[3;1H\nnew".bytes())

        assertEquals("top", update.screen[0].text)
        assertEquals("bbb", update.screen[1].text)
        assertEquals("new", update.screen[2].text)
        assertEquals("bottom", update.screen[3].text)
        assertTrue(update.completedScrollback.isEmpty())
    }

    @Test
    fun insertDeleteLinesAndCharactersAreBounded() {
        val engine = VtTerminalEngine(columns = 6, rows = 3)
        engine.accept("abcdef\u001B[1;3H\u001B[2P".bytes())
        assertEquals("abef", engine.accept(byteArrayOf()).screen[0].text)

        engine.accept("\u001B[1;3H\u001B[2@XY".bytes())
        assertEquals("abXYef", engine.accept(byteArrayOf()).screen[0].text)

        engine.accept("\u001B[2;1Hrow2\u001B[2;1H\u001B[L".bytes())
        assertEquals("", engine.accept(byteArrayOf()).screen[1].text)
        assertEquals("row2", engine.accept(byteArrayOf()).screen[2].text)
    }

    @Test
    fun preservesStylesAcrossRunsAndResetsThem() {
        val engine = VtTerminalEngine(columns = 20, rows = 2)

        val update = engine.accept("plain\u001B[1;38;5;202;48;2;1;2;3mhot\u001B[0mend".bytes())

        assertEquals("plainhotend", update.screen[0].text)
        assertEquals(3, update.screen[0].runs.size)
        assertTrue(update.screen[0].runs[1].style.bold)
        assertEquals(TerminalPalette.xtermColour(202), update.screen[0].runs[1].style.foreground)
        assertEquals(TerminalPalette.rgb(1, 2, 3), update.screen[0].runs[1].style.background)
        assertFalse(update.screen[0].runs[2].style.bold)
    }

    @Test
    fun reportsApplicationCursorPasteFocusMouseAndCursorModes() {
        val engine = VtTerminalEngine(columns = 10, rows = 2)

        val enabled = engine.accept("\u001B[?1h\u001B[?1004h\u001B[?1000h\u001B[?1006h\u001B[?2004h\u001B[?25l".bytes())
        val disabled = engine.accept("\u001B[?1l\u001B[?1004l\u001B[?1000l\u001B[?1006l\u001B[?2004l\u001B[?25h".bytes())

        assertTrue(enabled.modes.applicationCursorKeys)
        assertTrue(enabled.modes.bracketedPaste)
        assertTrue(enabled.modes.focusReporting)
        assertTrue(enabled.modes.mouseTracking)
        assertTrue(enabled.modes.sgrMouseEncoding)
        assertFalse(enabled.cursor.visible)
        assertFalse(disabled.modes.applicationCursorKeys)
        assertFalse(disabled.modes.bracketedPaste)
        assertFalse(disabled.modes.focusReporting)
        assertFalse(disabled.modes.mouseTracking)
        assertFalse(disabled.modes.sgrMouseEncoding)
        assertTrue(disabled.cursor.visible)
    }

    @Test
    fun answersStatusCursorAndDeviceAttributeQueries() {
        val engine = VtTerminalEngine(columns = 10, rows = 2)

        val update = engine.accept("ab\u001B[5n\u001B[6n\u001B[c".bytes())

        assertEquals(3, update.responses.size)
        assertArrayEquals("\u001B[0n".bytes(), update.responses[0])
        assertArrayEquals("\u001B[1;3R".bytes(), update.responses[1])
        assertArrayEquals("\u001B[?1;2c".bytes(), update.responses[2])
    }

    @Test
    fun resizePreservesBoundedVisibleContentAndClampsCursor() {
        val engine = VtTerminalEngine(columns = 8, rows = 3)
        engine.accept("12345678\r\nrow2\r\nrow3".bytes())

        val update = engine.resize(newColumns = 4, newRows = 2)

        assertEquals(listOf("1234", "row2"), update.screen.map { it.text })
        assertTrue(update.cursor.row in 0..1)
        assertTrue(update.cursor.column in 0..3)
    }

    @Test
    fun oversizedControlSequenceIsDiscardedAndParserRecovers() {
        val engine = VtTerminalEngine(columns = 10, rows = 2)
        val hostile = "\u001B[" + "1".repeat(5_000) + "mOK"

        val update = engine.accept(hostile.bytes())

        assertTrue(update.screen[0].text.endsWith("OK"))
        assertTrue(update.screen.sumOf { it.text.length } <= 20)
    }

    @Test
    fun resetClearsScreenModesAndParserState() {
        val engine = VtTerminalEngine(columns = 10, rows = 2)
        engine.accept("text\u001B[?1049h\u001B[?2004h".bytes())

        val update = engine.reset()

        assertFalse(update.alternateScreen)
        assertFalse(update.modes.bracketedPaste)
        assertEquals(listOf("", ""), update.screen.map { it.text })
        assertEquals(0, update.cursor.row)
        assertEquals(0, update.cursor.column)
    }

    @Test
    fun malformedAndRandomChunkedInputAlwaysRemainsBounded() {
        val engine = VtTerminalEngine(columns = 40, rows = 12)
        val random = Random(765380)

        repeat(2_000) {
            val bytes = ByteArray(random.nextInt(1, 96)) { random.nextInt(0, 256).toByte() }
            val update = engine.accept(bytes)
            assertEquals(12, update.screen.size)
            assertTrue(update.screen.all { it.text.length <= 80 })
            assertTrue(update.completedScrollback.size <= 96)
            assertTrue(update.cursor.row in 0..11)
            assertTrue(update.cursor.column in 0..39)
        }
    }

    @Test
    fun oversizedOscPayloadIsDiscardedUntilTerminator() {
        val engine = VtTerminalEngine(columns = 10, rows = 2)

        val update = engine.accept(("\u001B]0;" + "x".repeat(5_000) + "\u0007OK").bytes())

        assertEquals("OK", update.screen[0].text)
    }

    private fun String.bytes(): ByteArray = toByteArray(Charsets.UTF_8)
}
