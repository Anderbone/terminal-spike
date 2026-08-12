package com.yanjiyu.terminalspike.terminal.selection

import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSelectionModelTest {
    @Test
    fun wordSelectionExpandsAcrossRowsAndPreservesSoftWraps() {
        val source = FakeSelectionSource(
            lines = listOf(
                TerminalLine.plain("alpha bravo", softWrappedToNext = true),
                TerminalLine.plain(" charlie"),
                TerminalLine.plain("delta"),
            ),
        )
        val selection = TerminalSelectionModel()

        assertTrue(selection.beginWord(source, row = 0, column = 7))
        assertEquals("bravo", selection.selectedText(source))
        assertTrue(selection.updateEndpoint(source, TerminalSelectionEndpoint.END, row = 2, column = 5))

        assertEquals("bravo charlie\ndelta", selection.selectedText(source))
        assertEquals(
            listOf(
                TerminalSelectionSegment(0, 6, 11),
                TerminalSelectionSegment(1, 0, 8),
                TerminalSelectionSegment(2, 0, 5),
            ),
            selection.segments(source),
        )
    }

    @Test
    fun stableAnchorsSurviveAppendsButClearWhenTrimmed() {
        val source = FakeSelectionSource(
            lines = listOf(TerminalLine.plain("zero"), TerminalLine.plain("one")),
        )
        val selection = TerminalSelectionModel()
        selection.beginWord(source, row = 0, column = 1)

        source.append(TerminalLine.plain("two"))
        assertTrue(selection.validate(source))
        assertEquals("zero", selection.selectedText(source))

        source.trimFirst()
        assertFalse(selection.validate(source))
        assertFalse(selection.hasSelection)
        assertNull(selection.selectedText(source))
    }

    @Test
    fun volatileAnchorIsRejectedAfterContentRevisionChanges() {
        val source = FakeSelectionSource(
            lines = listOf(TerminalLine.plain("screen")),
            volatile = true,
        )
        val selection = TerminalSelectionModel()
        selection.beginWord(source, row = 0, column = 2)

        source.advanceRevision()

        assertFalse(selection.validate(source))
        assertFalse(selection.hasSelection)
    }

    @Test
    fun dragAndSelectAllStayWithinConfiguredLineBound() {
        val source = FakeSelectionSource(List(8) { TerminalLine.plain("row-$it") })
        val selection = TerminalSelectionModel(maximumSelectedLines = 3)
        selection.beginWord(source, row = 4, column = 1)

        selection.updateEndpoint(source, TerminalSelectionEndpoint.END, row = 7, column = 5)

        assertEquals(listOf(4, 5, 6), selection.segments(source).map { it.row })
        assertTrue(selection.selectAll(source))
        assertEquals(listOf(5, 6, 7), selection.segments(source).map { it.row })
    }

    @Test
    fun copiedTextIsCharacterBoundedWithoutSplittingSurrogatePair() {
        val source = FakeSelectionSource(listOf(TerminalLine.plain("ab🚀tail")))
        val selection = TerminalSelectionModel(maximumCopiedCharacters = 3)
        selection.selectAll(source)

        assertEquals("ab", selection.selectedText(source))
    }

    @Test
    fun reversedHandleDragNormalizesSelection() {
        val source = FakeSelectionSource(
            listOf(TerminalLine.plain("first"), TerminalLine.plain("second")),
        )
        val selection = TerminalSelectionModel()
        selection.beginWord(source, row = 1, column = 2)

        selection.updateEndpoint(source, TerminalSelectionEndpoint.START, row = 0, column = 1)

        assertEquals("irst\nsecond", selection.selectedText(source))

        selection.updateEndpoint(source, TerminalSelectionEndpoint.START, row = 0, column = 3)

        assertEquals("st\nsecond", selection.selectedText(source))
    }

    @Test
    fun geometryKeepsWideAndCombiningCharactersOnCaretBoundaries() {
        val line = TerminalLine.styled(
            listOf(
                TerminalRun(
                    text = "A界e\u0301",
                    startColumn = 0,
                    columnWidth = 4,
                ),
            ),
        )

        assertEquals(TerminalLinePosition(1, 1), TerminalLineGeometry.positionAtColumn(line, 1))
        assertEquals(TerminalLinePosition(1, 1), TerminalLineGeometry.positionAtColumn(line, 2))
        assertEquals(3, TerminalLineGeometry.columnAtOffset(line, 2))
        assertEquals(4, TerminalLineGeometry.columnAtOffset(line, line.text.length))
    }

    @Test
    fun geometryDoesNotBisectEmojiZwJModifiersOrRegionalIndicatorFlags() {
        val text = "A👩🏽‍💻🇬🇧┌─B"
        val line = TerminalLine.styled(
            listOf(TerminalRun(text = text, startColumn = 0, columnWidth = 8)),
        )

        assertEquals(TerminalLinePosition(textOffset = 1, column = 1), TerminalLineGeometry.positionAtColumn(line, 2))
        assertEquals(TerminalLinePosition(textOffset = 8, column = 3), TerminalLineGeometry.positionAtColumn(line, 4))
        assertEquals(8, TerminalLineGeometry.columnCount(line))
    }
}

private class FakeSelectionSource(
    lines: List<TerminalLine>,
    private val volatile: Boolean = false,
) : TerminalSelectionSource {
    private data class Entry(val id: Long, val line: TerminalLine)

    private val entries = lines.mapIndexedTo(mutableListOf()) { index, line -> Entry(index.toLong(), line) }
    private var nextId = entries.size.toLong()
    private var revision = 0L

    override val selectionLineCount: Int
        get() = entries.size

    override fun selectionLineAt(index: Int): TerminalSelectableLine? {
        val entry = entries.getOrNull(index) ?: return null
        return TerminalSelectableLine(
            anchor = if (volatile) {
                TerminalLineAnchor(TerminalLineSpace.VOLATILE_SCREEN, revision, index)
            } else {
                TerminalLineAnchor(TerminalLineSpace.PRIMARY_HISTORY, entry.id)
            },
            line = entry.line,
        )
    }

    override fun selectionIndexOf(anchor: TerminalLineAnchor): Int? = when (anchor.space) {
        TerminalLineSpace.PRIMARY_HISTORY -> entries.indexOfFirst { it.id == anchor.id }.takeIf { it >= 0 }
        TerminalLineSpace.VOLATILE_SCREEN -> anchor.volatileRow.takeIf {
            anchor.id == revision && it in entries.indices
        }
        TerminalLineSpace.ALTERNATE_HISTORY -> null
    }

    fun append(line: TerminalLine) {
        entries += Entry(nextId++, line)
    }

    fun trimFirst() {
        entries.removeAt(0)
    }

    fun advanceRevision() {
        revision += 1
    }
}
