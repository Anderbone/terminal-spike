package com.yanjiyu.terminalspike.terminal.selection

/** A bounded, detached copy: remote redraws cannot change text under the selection handles. */
internal data class TerminalTextExtraction(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
) {
    companion object {
        fun capture(
            source: TerminalSelectionSource,
            selection: TerminalSelectionModel,
            surroundingRows: Int = 8,
        ): TerminalTextExtraction? {
            val range = selection.normalizedRange(source) ?: return null
            val startRow = source.selectionIndexOf(range.start.line) ?: return null
            val endRow = source.selectionIndexOf(range.end.line) ?: return null
            // Include nearby context for adjusting selection without copying the entire session.
            val first = (startRow - surroundingRows.coerceAtLeast(0)).coerceAtLeast(0)
            val last = (endRow + surroundingRows.coerceAtLeast(0)).coerceAtMost(source.selectionLineCount - 1)
            val text = StringBuilder()
            var start = 0
            var end = 0
            for (row in first..last) {
                val line = source.selectionLineAt(row)?.line ?: return null
                if (row == startRow) start = text.length + range.start.textOffset
                if (row == endRow) end = text.length + range.end.textOffset
                if (text.length + line.text.length > 65_536) return null
                text.append(line.text)
                if (row < last && !line.softWrappedToNext) text.append('\n')
            }
            return TerminalTextExtraction(text.toString(), start, end)
        }
    }
}
