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
    fun prependRetainsExistingIdsAndAssignsEarlierStableIds() {
        val buffer = TerminalBuffer(capacity = 6, initialNextId = 100)
        val existing = buffer.append(listOf(TerminalLine.plain("two"), TerminalLine.plain("three")))

        val prepended = buffer.prepend(listOf(TerminalLine.plain("zero"), TerminalLine.plain("one")))

        assertEquals(listOf("zero", "one", "two", "three"), buffer.snapshot().map { it.text })
        assertEquals(listOf(98L, 99L), prepended.map { it.id })
        assertEquals(listOf(100L, 101L), existing.map { it.id })
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
    fun replacingSuffixRetainsPrefixIdsAndDoesNotRetargetRemovedIds() {
        val buffer = TerminalBuffer(8)
        val original = buffer.append((0..4).map { TerminalLine.plain("old-$it") })

        val dropped = buffer.replaceSuffix(
            fromIndex = 3,
            replacement = listOf(TerminalLine.plain("new-3"), TerminalLine.plain("new-4")),
        )

        assertEquals(0, dropped)
        assertEquals(listOf("old-0", "old-1", "old-2", "new-3", "new-4"), buffer.snapshot().map { it.text })
        assertEquals(original.take(3).map { it.id }, buffer.snapshot().take(3).map { it.id })
        assertNull(buffer.indexOfId(original[3].id))
        assertNull(buffer.indexOfId(original[4].id))
        assertEquals(listOf(5L, 6L), buffer.snapshot().takeLast(2).map { it.id })
    }

    @Test
    fun replacingSuffixAtCapacityEvictsOnlyTheOldestPrefixRows() {
        val buffer = TerminalBuffer(5)
        buffer.append((0..4).map { TerminalLine.plain("old-$it") })

        val dropped = buffer.replaceSuffix(
            fromIndex = 4,
            replacement = listOf(TerminalLine.plain("new-4"), TerminalLine.plain("new-5")),
        )

        assertEquals(1, dropped)
        assertEquals(listOf("old-1", "old-2", "old-3", "new-4", "new-5"), buffer.snapshot().map { it.text })
        assertEquals(1L, buffer.oldestLineId())
        assertEquals(1L, buffer.oldestRowOrdinal())
        assertEquals(6L, buffer.newestLineId())

        buffer.append((6..8).map { TerminalLine.plain("later-$it") })

        assertEquals(listOf("new-4", "new-5", "later-6", "later-7", "later-8"), buffer.snapshot().map { it.text })
        assertEquals(5L, buffer.oldestLineId())
        assertEquals(4L, buffer.oldestRowOrdinal())
        assertEquals(0, buffer.indexOfId(5L))
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
