package com.yanjiyu.terminalspike.terminal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBufferTest {
    @Test
    fun emptyBufferHasNoLinesOrIds() {
        val buffer = TerminalBuffer(4)

        assertEquals(0, buffer.lineCount())
        assertNull(buffer.lineAt(0))
        assertNull(buffer.oldestLineId())
        assertNull(buffer.newestLineId())
    }

    @Test
    fun appendAssignsStableMonotonicIds() {
        val buffer = TerminalBuffer(4)

        val first = buffer.append(TerminalLine.plain("first"))
        val second = buffer.append(TerminalLine.plain("second"))

        assertEquals(0L, first.id)
        assertEquals(1L, second.id)
        assertEquals("first", buffer.lineAt(0)?.text)
        assertEquals("second", buffer.lineAt(1)?.text)
    }

    @Test
    fun assigningAnIdReusesAlreadyValidatedImmutablePayload() {
        val line = TerminalLine.styled(listOf(TerminalRun("safe 🚀", TerminalStyle(bold = true))))

        val assigned = line.withId(42)

        assertEquals("safe 🚀", assigned.text)
        assertSame(line.text, assigned.text)
        assertSame(line.runs, assigned.runs)
    }

    @Test
    fun batchAppendRetainsOrder() {
        val buffer = TerminalBuffer(5)

        val stored = buffer.append(List(4) { TerminalLine.plain("line-$it") })

        assertEquals(listOf(0L, 1L, 2L, 3L), stored.map { it.id })
        assertEquals((0..3).map { "line-$it" }, (0..3).map { buffer.lineAt(it)?.text })
    }

    @Test
    fun wraparoundDropsOnlyOldestLines() {
        val buffer = TerminalBuffer(3)
        repeat(5) { buffer.append(TerminalLine.plain("line-$it")) }

        assertEquals(3, buffer.lineCount())
        assertEquals(listOf("line-2", "line-3", "line-4"), (0..2).map { buffer.lineAt(it)?.text })
        assertEquals(2L, buffer.oldestLineId())
        assertEquals(4L, buffer.newestLineId())
    }

    @Test
    fun capacityTrimmingWorksForLargeBatch() {
        val buffer = TerminalBuffer(4)
        buffer.append(List(12) { TerminalLine.plain(it.toString()) })

        assertEquals(listOf("8", "9", "10", "11"), (0..3).map { buffer.lineAt(it)?.text })
        assertNull(buffer.lineAt(4))
    }

    @Test
    fun clearDoesNotReuseStableIds() {
        val buffer = TerminalBuffer(2)
        buffer.append(TerminalLine.plain("before"))
        buffer.clear()

        val after = buffer.append(TerminalLine.plain("after"))

        assertEquals(1L, after.id)
        assertEquals(1, buffer.lineCount())
    }

    @Test
    fun retrievalRemainsCorrectAfterSeveralWraparounds() {
        val buffer = TerminalBuffer(7)
        repeat(100) { buffer.append(TerminalLine.plain("value-$it")) }

        assertEquals((93..99).map { "value-$it" }, (0..6).map { buffer.lineAt(it)?.text })
        assertEquals(0, buffer.indexOfId(93L))
        assertEquals(6, buffer.indexOfId(99L))
        assertNull(buffer.indexOfId(92L))
    }

    @Test
    fun malformedUnicodeIsReplacedWithoutChangingValidPairs() {
        val buffer = TerminalBuffer(2)
        buffer.append(TerminalLine.plain("ok 🚀 bad \uD800 tail"))

        val text = buffer.lineAt(0)?.text.orEmpty()
        assertTrue(text.contains("🚀"))
        assertTrue(text.contains("�"))
    }
}
