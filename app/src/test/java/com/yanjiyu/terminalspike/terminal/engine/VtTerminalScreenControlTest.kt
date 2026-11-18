package com.yanjiyu.terminalspike.terminal.engine

import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class VtTerminalScreenControlTest(private val oneByteChunks: Boolean) {
    private val history = mutableListOf<TerminalLine>()

    @Test
    fun completeDisplayErasePreservesTheCursor() {
        val engine = VtTerminalEngine(columns = 8, rows = 4)
        val before = feed(engine, "\u001B[2;3r\u001B[2;4HA")
        val erased = feed(engine, "\u001B[2J")

        assertEquals(before.cursor, erased.cursor)
        assertTrue(erased.screen.all { it.text.isEmpty() })
        assertTrue(history.isEmpty())
    }

    @Test
    fun completeDisplayErasePreservesScrollMarginsAndOutsideRows() {
        val engine = VtTerminalEngine(columns = 8, rows = 4)
        val frame = feed(engine,
            "\u001B[2;3r\u001B[2J\u001B[1;1Htop\u001B[4;1Hbottom" +
                "\u001B[2;1Hmiddle\u001B[3;1Hend\r\nnext",
        )

        assertEquals(listOf("top", "end", "next", "bottom"), frame.screen.map { it.text })
        assertTrue(history.isEmpty())
    }

    @Test
    fun completeDisplayEraseKeepsOriginModeRelativeToItsMargins() {
        val engine = VtTerminalEngine(columns = 8, rows = 4)
        val before = feed(engine, "\u001B[2;3r\u001B[?6h\u001B[2;4HA")
        val erased = feed(engine, "\u001B[2J")
        assertEquals(before.cursor, erased.cursor)

        val frame = feed(engine, "\u001B[1;1HX")
        assertEquals("", frame.screen[0].text)
        assertEquals("X", frame.screen[1].text)
        assertTrue(history.isEmpty())
    }

    @Test
    fun reverseIndexAboveTheScrollRegionStopsAtPhysicalRowZero() {
        val engine = VtTerminalEngine(columns = 8, rows = 4)
        val frame = feed(engine, "\u001B[2;3r\u001B[1;1H\u001BM\u001BMx")

        assertEquals(0, frame.cursor.row)
        assertEquals("x", frame.screen[0].text)
        assertTrue(frame.screen.drop(1).all { it.text.isEmpty() })
        assertTrue(history.isEmpty())
    }

    @Test
    fun reverseIndexCancelsPendingWrapBeforeTheNextPrintableCharacter() {
        val engine = VtTerminalEngine(columns = 4, rows = 4)
        val frame = feed(engine, "\u001B[3;4HX\u001BMY")

        assertEquals("   Y", frame.screen[1].text)
        assertEquals("   X", frame.screen[2].text)
        assertEquals(1, frame.cursor.row)
        assertEquals(3, frame.cursor.column)
        assertTrue(history.isEmpty())
    }

    @Test
    fun reverseIndexAtTheTopMarginScrollsOnlyItsRegion() {
        val engine = VtTerminalEngine(columns = 8, rows = 4)
        val frame = feed(engine,
            "top\r\nfirst\r\nsecond\r\nbottom\u001B[2;3r\u001B[2;3H\u001BMx",
        )

        assertEquals(listOf("top", "  x", "first", "bottom"), frame.screen.map { it.text })
        assertEquals(1, frame.cursor.row)
        assertEquals(3, frame.cursor.column)
        assertTrue(history.isEmpty())
    }

    @Test
    fun reverseIndexBelowTheScrollRegionMovesOneRowWithoutScrolling() {
        val engine = VtTerminalEngine(columns = 8, rows = 4)
        val frame = feed(engine,
            "top\r\nfirst\r\nsecond\r\nbottom\u001B[2;3r\u001B[4;1H\u001BMZ",
        )

        assertEquals(listOf("top", "first", "Zecond", "bottom"), frame.screen.map { it.text })
        assertEquals(2, frame.cursor.row)
        assertTrue(history.isEmpty())
    }

    @Test
    fun reenteringTheClearedAlternateScreenStillResetsItsCursorAndMargins() {
        val engine = VtTerminalEngine(columns = 8, rows = 4)
        feed(engine, "primary\u001B[?1049h\u001B[2;3r\u001B[3;4HX\u001B[?1049l")
        val cleared = feed(engine, "\u001B[?1049h")
        assertEquals(0, cleared.cursor.row)
        assertEquals(0, cleared.cursor.column)
        assertTrue(cleared.screen.all { it.text.isEmpty() })
        val frame = feed(engine, "zero\r\none\r\ntwo\r\nthree\r\nfour")
        assertEquals(listOf("one", "two", "three", "four"), frame.screen.map { it.text })
        val primary = feed(engine, "\u001B[?1049l")
        assertEquals("primary", primary.screen[0].text)
    }

    private fun feed(engine: VtTerminalEngine, text: String): TerminalFrameUpdate {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val chunks = if (oneByteChunks) bytes.map { byteArrayOf(it) } else listOf(bytes)
        var frame: TerminalFrameUpdate? = null
        for (chunk in chunks) {
            frame = engine.accept(chunk)
            history += frame.completedScrollback
        }
        return requireNotNull(frame)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "oneByteChunks={0}")
        fun chunks(): List<Array<Boolean>> = listOf(arrayOf(false), arrayOf(true))
    }
}
