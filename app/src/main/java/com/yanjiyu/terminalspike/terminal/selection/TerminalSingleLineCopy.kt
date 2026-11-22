package com.yanjiyu.terminalspike.terminal.selection

import com.yanjiyu.terminalspike.terminal.model.TerminalCellWidth

/**
 * Explicit user action only: TUI hard wraps cannot reliably be distinguished from real newlines.
 *
 * TUIs re-wrap text themselves and drop the row-break whitespace. Word wrappers (Claude Code)
 * break at a space, while a token longer than a row (Codex long commands, URLs) is broken mid-token.
 * A break is treated as mid-token only when the previous row reaches the right edge and the word
 * ending it plus the word starting the next row could not have fit on one row; otherwise the
 * dropped space is restored. Wide (CJK) text never takes a space between rows.
 *
 * [rowWidth] is the widest physical row around the selection, measured in terminal cells; it
 * defaults to the widest row of [text]. [firstRowOffset] is the unselected cell prefix of the
 * first row, so a selection starting mid-row still measures that row's full width.
 */
internal fun terminalTextAsSingleLine(
    text: String,
    rowWidth: Int = widestRow(text),
    firstRowOffset: Int = 0,
): String {
    val result = StringBuilder(text.length)
    var previous: String? = null
    var previousEnd = 0
    var offset = firstRowOffset.coerceAtLeast(0)
    for (raw in text.lineSequence()) {
        val row = raw.trim(' ', '\t', '\r')
        val end = offset + cells(raw.trimEnd(' ', '\t', '\r'))
        offset = 0
        if (row.isEmpty()) continue
        if (previous != null) {
            val indent = raw.length - raw.trimStart(' ', '\t').length
            val joined = cells(previous.substringAfterLast(' ').substringAfterLast('\t')) +
                cells(row.substringBefore(' ').substringBefore('\t'))
            val midToken = previousEnd >= rowWidth - EDGE_SLACK_CELLS && joined > rowWidth - indent
            val cjkJoin = TerminalCellWidth.of(previous.codePointBefore(previous.length)) == 2 &&
                TerminalCellWidth.of(row.codePointAt(0)) == 2
            if (!midToken && !cjkJoin) result.append(' ')
        }
        result.append(row)
        previous = row
        previousEnd = end
    }
    return result.toString()
}

/** A wide glyph or a TUI's reserved last column can stop a full row one or two cells early. */
private const val EDGE_SLACK_CELLS = 2

internal fun cellsOf(text: String): Int = cells(text)

internal fun widestRow(text: String): Int =
    text.lineSequence().maxOfOrNull { cells(it.trimEnd(' ', '\t', '\r')) } ?: 0

/** Terminal cells, so CJK rows compare against the same right edge as Latin rows. */
private fun cells(value: String): Int {
    var total = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        total += TerminalCellWidth.of(codePoint)
        index += Character.charCount(codePoint)
    }
    return total
}
