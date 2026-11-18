package com.yanjiyu.terminalspike.terminal.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VtTerminalGraphemeLimitTest {
    @Test fun aCellAtTheStorageLimitKeepsItsExactText() {
        for (base in listOf("A", "界", "👩")) {
            val cell = base + "\u0301".repeat(4_096 - base.length)
            val frame = VtTerminalEngine(12, 2).accept((cell + "Z").toByteArray())
            assertEquals(cell + "Z", frame.screen.first().text)
            assertEquals(if (base == "A") 2 else 3, frame.cursor.column)
        }
    }

    @Test fun exceedingTheLimitReplacesOnlyTheOversizedCell() {
        val engine = VtTerminalEngine(12, 2)
        val allowed = "A" + "\u0301".repeat(4_095)
        engine.accept(allowed.toByteArray())

        val frame = engine.accept("\u0301ZZZ".toByteArray())

        assertEquals("\uFFFDZZZ", frame.screen.first().text)
        assertEquals(4, frame.screen.first().runs.single().columnWidth)
        assertEquals(4, frame.cursor.column)
    }

    @Test fun aLargeWideCellKeepsItsColumnsAndEveryFollowingMarker() {
        val frame = VtTerminalEngine(12, 2).accept(
            ("W界" + "\u0301".repeat(16_384) + "ZZZ").toByteArray(),
        )

        assertEquals("W\uFFFD ZZZ", frame.screen.first().text)
        assertEquals(6, frame.cursor.column)
        assertEquals(6, frame.screen.first().runs.single().columnWidth)
    }

    @Test fun discardedJoinersStillJoinExactlyTheNextGraphic() {
        // Overflow on the joiner and overflow on the following graphic need the same tail state.
        for (marks in listOf(4_094, 4_095)) {
            val input = ("A" + "\u0301".repeat(marks) + "\u200DBCZ").toByteArray()
            for (oneByteChunks in listOf(false, true)) {
                val engine = VtTerminalEngine(12, 2)
                val frame = if (oneByteChunks) {
                    input.forEach { engine.accept(byteArrayOf(it)) }
                    engine.accept(byteArrayOf())
                } else {
                    engine.accept(input)
                }
                assertEquals("\uFFFDCZ", frame.screen.first().text)
                assertEquals(3, frame.cursor.column)
            }
        }
    }

    @Test fun aDiscardedCombiningMarkEndsTheJoinerState() {
        val frame = VtTerminalEngine(12, 2).accept(
            ("A" + "\u0301".repeat(4_095) + "\u200D\u0301BC").toByteArray(),
        )

        assertEquals("\uFFFDBC", frame.screen.first().text)
        assertEquals(3, frame.cursor.column)
    }

    @Test fun discardedRegionalIndicatorsRetainPairParity() {
        val engine = VtTerminalEngine(12, 2)
        engine.accept(("🇬" + "\u0301".repeat(4_094)).toByteArray())
        engine.accept("🇧".toByteArray()) // Completes the old pair and overflows its cell.

        val frame = engine.accept("🇺🇸Z".toByteArray())

        assertEquals("\uFFFD 🇺🇸Z", frame.screen.first().text)
        assertEquals(5, frame.cursor.column)
    }

    @Test fun aRegionalIndicatorJoinedAfterOverflowCanStillStartAPair() {
        val engine = VtTerminalEngine(12, 2)
        engine.accept(("A" + "\u0301".repeat(4_094) + "\u200D").toByteArray())
        engine.accept("🇬".toByteArray()) // Joiner puts the first indicator in the narrow old cell.

        val frame = engine.accept("🇧🇺🇸Z".toByteArray())

        assertEquals("\uFFFD🇺🇸Z", frame.screen.first().text)
        assertEquals(4, frame.cursor.column)
    }

    @Test fun ignoredTailUpdatesReuseThePublishedRow() {
        val engine = VtTerminalEngine(12, 2)
        val first = engine.accept(("A" + "\u0301".repeat(4_096)).toByteArray())

        val ignored = engine.accept("\u0301".repeat(8_192).toByteArray())

        assertEquals("\uFFFD", ignored.screen.first().text)
        assertSame(first.screen.first(), ignored.screen.first())
        assertArrayEquals(intArrayOf(), ignored.dirtyRows)
        val next = engine.accept("Z".toByteArray())
        assertEquals("\uFFFDZ", next.screen.first().text)
        assertArrayEquals(intArrayOf(0), next.dirtyRows)
    }

    @Test fun replacementRetainsItsStyleAndHyperlink() {
        val frame = VtTerminalEngine(12, 2).accept(
            ("\u001B[31m\u001B]8;;https://example.test\u0007A" +
                "\u0301".repeat(4_096) + "\u001B]8;;\u0007\u001B[0mZ").toByteArray(),
        )
        val runs = frame.screen.first().runs

        assertEquals("\uFFFDZ", frame.screen.first().text)
        assertEquals(2, runs.size)
        assertEquals("https://example.test", runs.first().hyperlink?.uri)
        assertTrue(runs.first().style != runs.last().style)
        assertEquals(1, runs.last().startColumn)
        assertEquals(null, runs.last().hyperlink)
    }

    @Test fun overwritingAndErasingDiscardTheOldTailState() {
        for (clear in listOf("\u001B[1;1H", "\u001B[2J\u001B[1;1H")) {
            val engine = VtTerminalEngine(12, 2)
            engine.accept(("A" + "\u0301".repeat(4_095) + "\u200D").toByteArray())

            val frame = engine.accept((clear + "XY").toByteArray())

            assertEquals("XY", frame.screen.first().text)
            assertEquals(2, frame.cursor.column)
        }
    }

    @Test fun resizingRetainsTheDiscardedTailWithoutReintroducingItsText() {
        val engine = VtTerminalEngine(8, 2)
        engine.accept(("A" + "\u0301".repeat(4_095) + "\u200D").toByteArray())
        engine.resize(12, 2)

        val frame = engine.accept("BCZ".toByteArray())

        assertEquals("\uFFFDCZ", frame.screen.first().text)
        assertEquals(3, frame.cursor.column)
    }
}
