package com.yanjiyu.terminalspike.terminal.selection

import com.yanjiyu.terminalspike.terminal.model.TerminalLine

/** Identifies which bounded terminal history owns a stable line ID. */
enum class TerminalLineSpace {
    PRIMARY_HISTORY,
    ALTERNATE_HISTORY,
    VOLATILE_SCREEN,
}

/**
 * A scroll-safe terminal row reference.
 *
 * History IDs survive viewport movement and incoming output. Volatile screen rows use a content
 * revision as their ID, so a redraw invalidates selection instead of copying changed content.
 */
data class TerminalLineAnchor(
    val space: TerminalLineSpace,
    val id: Long,
    val volatileRow: Int = -1,
) {
    init {
        require(id >= 0L)
        require((space == TerminalLineSpace.VOLATILE_SCREEN) == (volatileRow >= 0))
    }
}

data class TerminalSelectableLine(
    val anchor: TerminalLineAnchor,
    val line: TerminalLine,
)

/** Main-thread read surface used by selection without publishing terminal rows as Compose state. */
interface TerminalSelectionSource {
    val selectionLineCount: Int

    fun selectionLineAt(index: Int): TerminalSelectableLine?

    fun selectionIndexOf(anchor: TerminalLineAnchor): Int?
}

/** A caret boundary. [textOffset] is UTF-16 and [column] is terminal-cell geometry. */
data class TerminalTextPosition(
    val line: TerminalLineAnchor,
    val textOffset: Int,
    val column: Int,
) {
    init {
        require(textOffset >= 0)
        require(column >= 0)
    }
}

data class TerminalSelectionRange(
    val start: TerminalTextPosition,
    val end: TerminalTextPosition,
)

data class TerminalSelectionSegment(
    val row: Int,
    val startColumn: Int,
    val endColumn: Int,
)

enum class TerminalSelectionEndpoint {
    START,
    END,
}

/**
 * Bounded selection state for the native renderer.
 *
 * It stores only two anchors. Text and rectangles are resolved on demand, so scrollback rows never
 * become per-line UI state and a trimmed anchor can be detected before copying.
 */
class TerminalSelectionModel(
    private val maximumSelectedLines: Int = DEFAULT_MAX_SELECTED_LINES,
    private val maximumCopiedCharacters: Int = DEFAULT_MAX_COPIED_CHARACTERS,
) {
    private var rawRange: TerminalSelectionRange? = null

    init {
        require(maximumSelectedLines > 0)
        require(maximumCopiedCharacters > 0)
    }

    val hasSelection: Boolean
        get() = rawRange != null

    fun clear(): Boolean {
        if (rawRange == null) return false
        rawRange = null
        return true
    }

    /** Starts selection at the word under [column], or at one terminal cell for whitespace. */
    fun beginWord(source: TerminalSelectionSource, row: Int, column: Int): Boolean {
        val selectable = source.selectionLineAt(row) ?: return clearAndFalse()
        val hit = TerminalLineGeometry.positionAtColumn(selectable.line, column)
        val bounds = wordBounds(selectable.line.text, hit.textOffset)
        val start = positionAtOffset(selectable, bounds.first)
        val end = positionAtOffset(selectable, bounds.last + 1)
        rawRange = TerminalSelectionRange(start, end)
        return true
    }

    fun updateEndpoint(
        source: TerminalSelectionSource,
        endpoint: TerminalSelectionEndpoint,
        row: Int,
        column: Int,
    ): Boolean {
        val current = rawRange ?: return false
        val fixed = if (endpoint == TerminalSelectionEndpoint.START) current.end else current.start
        val fixedRow = source.selectionIndexOf(fixed.line) ?: return clearAndFalse()
        val boundedRow = row.coerceIn(
            (fixedRow - maximumSelectedLines + 1).coerceAtLeast(0),
            (fixedRow + maximumSelectedLines - 1).coerceAtMost(source.selectionLineCount - 1),
        )
        val selectable = source.selectionLineAt(boundedRow) ?: return false
        val moving = TerminalLineGeometry.positionAtColumn(selectable.line, column).let { position ->
            TerminalTextPosition(selectable.anchor, position.textOffset, position.column)
        }
        rawRange = if (endpoint == TerminalSelectionEndpoint.START) {
            TerminalSelectionRange(moving, fixed)
        } else {
            TerminalSelectionRange(fixed, moving)
        }
        return validate(source)
    }

    fun selectAll(source: TerminalSelectionSource): Boolean {
        if (source.selectionLineCount <= 0) return clearAndFalse()
        val lastRow = source.selectionLineCount - 1
        val firstRow = (lastRow - maximumSelectedLines + 1).coerceAtLeast(0)
        val first = source.selectionLineAt(firstRow) ?: return clearAndFalse()
        val last = source.selectionLineAt(lastRow) ?: return clearAndFalse()
        rawRange = TerminalSelectionRange(
            start = positionAtOffset(first, 0),
            end = positionAtOffset(last, last.line.text.length),
        )
        return true
    }

    /** Clears both endpoints when either source row has been trimmed or redrawn. */
    fun validate(source: TerminalSelectionSource): Boolean {
        val normalized = normalizedRange(source) ?: return clearAndFalse()
        val startRow = source.selectionIndexOf(normalized.start.line) ?: return clearAndFalse()
        val endRow = source.selectionIndexOf(normalized.end.line) ?: return clearAndFalse()
        if (endRow - startRow + 1 > maximumSelectedLines) return clearAndFalse()
        val startLine = source.selectionLineAt(startRow)?.line ?: return clearAndFalse()
        val endLine = source.selectionLineAt(endRow)?.line ?: return clearAndFalse()
        if (
            normalized.start.textOffset > startLine.text.length ||
            normalized.end.textOffset > endLine.text.length
        ) {
            return clearAndFalse()
        }
        return true
    }

    fun normalizedRange(source: TerminalSelectionSource): TerminalSelectionRange? {
        val range = rawRange ?: return null
        val firstRow = source.selectionIndexOf(range.start.line) ?: return null
        val secondRow = source.selectionIndexOf(range.end.line) ?: return null
        return if (
            firstRow < secondRow ||
            (firstRow == secondRow && range.start.textOffset <= range.end.textOffset)
        ) {
            range
        } else {
            TerminalSelectionRange(range.end, range.start)
        }
    }

    fun segments(source: TerminalSelectionSource): List<TerminalSelectionSegment> {
        val range = normalizedRange(source) ?: return emptyList()
        val startRow = source.selectionIndexOf(range.start.line) ?: return emptyList()
        val endRow = source.selectionIndexOf(range.end.line) ?: return emptyList()
        if (endRow - startRow + 1 > maximumSelectedLines) return emptyList()
        val segments = ArrayList<TerminalSelectionSegment>(endRow - startRow + 1)
        for (row in startRow..endRow) {
            val line = source.selectionLineAt(row)?.line ?: return emptyList()
            val startColumn = if (row == startRow) range.start.column else 0
            val endColumn = if (row == endRow) {
                range.end.column
            } else {
                TerminalLineGeometry.columnCount(line)
            }
            segments += TerminalSelectionSegment(
                row = row,
                startColumn = minOf(startColumn, endColumn),
                endColumn = maxOf(startColumn, endColumn),
            )
        }
        return segments
    }

    /** Returns at most [maximumCopiedCharacters] UTF-16 characters. */
    fun selectedText(source: TerminalSelectionSource): String? {
        val range = normalizedRange(source) ?: return null
        val startRow = source.selectionIndexOf(range.start.line) ?: return null
        val endRow = source.selectionIndexOf(range.end.line) ?: return null
        if (endRow - startRow + 1 > maximumSelectedLines) return null
        val result = StringBuilder(minOf(maximumCopiedCharacters, 4_096))
        for (row in startRow..endRow) {
            val line = source.selectionLineAt(row)?.line ?: return null
            val startOffset = if (row == startRow) range.start.textOffset else 0
            val endOffset = if (row == endRow) range.end.textOffset else line.text.length
            if (startOffset !in 0..line.text.length || endOffset !in startOffset..line.text.length) {
                return null
            }
            appendBounded(result, line.text, startOffset, endOffset)
            if (result.length >= maximumCopiedCharacters) break
            if (row < endRow && !line.softWrappedToNext) result.append('\n')
            if (result.length >= maximumCopiedCharacters) break
        }
        return result.toString()
    }

    private fun appendBounded(destination: StringBuilder, text: String, start: Int, end: Int) {
        val remaining = maximumCopiedCharacters - destination.length
        if (remaining <= 0 || start >= end) return
        var boundedEnd = minOf(end, start + remaining)
        if (
            boundedEnd in (start + 1) until text.length &&
            Character.isHighSurrogate(text[boundedEnd - 1]) &&
            Character.isLowSurrogate(text[boundedEnd])
        ) {
            boundedEnd -= 1
        }
        destination.append(text, start, boundedEnd)
    }

    private fun positionAtOffset(
        selectable: TerminalSelectableLine,
        textOffset: Int,
    ): TerminalTextPosition = TerminalTextPosition(
        line = selectable.anchor,
        textOffset = textOffset,
        column = TerminalLineGeometry.columnAtOffset(selectable.line, textOffset),
    )

    private fun clearAndFalse(): Boolean {
        clear()
        return false
    }

    companion object {
        const val DEFAULT_MAX_SELECTED_LINES = 4_096
        const val DEFAULT_MAX_COPIED_CHARACTERS = 1_048_576
    }
}

private fun wordBounds(text: String, hitOffset: Int): IntRange {
    if (text.isEmpty()) return 0..-1
    val safeOffset = hitOffset.coerceIn(0, text.length)
    val pivot = when {
        safeOffset == text.length -> text.length - 1
        safeOffset > 0 && !isWordCharacter(text[safeOffset]) && isWordCharacter(text[safeOffset - 1]) -> safeOffset - 1
        else -> safeOffset
    }
    if (!isWordCharacter(text[pivot])) return pivot..pivot
    var start = pivot
    var end = pivot + 1
    while (start > 0 && isWordCharacter(text[start - 1])) start -= 1
    while (end < text.length && isWordCharacter(text[end])) end += 1
    return start until end
}

private fun isWordCharacter(character: Char): Boolean =
    !character.isWhitespace() && !character.isISOControl()
