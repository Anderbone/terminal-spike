package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.selection.TerminalLineGeometry
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Existing immutable reader content and its independently scrolling, clipped screen rectangle. */
internal data class TerminalAccessibilityPane(
    val x: Int,
    val y: Int,
    val columns: Int,
    val rows: Int,
    val scrollY: Float,
    val lines: List<TerminalLine>,
)

/** Accessibility-only composition. It never captures history, changes a viewport, or draws. */
internal fun composeTerminalAccessibilityText(
    liveLineCount: Int,
    liveLineAt: (Int) -> TerminalLine?,
    liveScrollY: Float,
    columns: Int,
    lineHeight: Float,
    viewportHeight: Float,
    pane: TerminalAccessibilityPane,
    maxCharacters: Int,
): String {
    if (columns <= 0 || lineHeight <= 0f || !lineHeight.isFinite() ||
        viewportHeight <= 0f || maxCharacters <= 0
    ) return ""
    val parts = ArrayList<AccessiblePart>()
    val paneLeft = pane.x.coerceIn(0, columns)
    val paneRight = (pane.x.toLong() + pane.columns).coerceIn(paneLeft.toLong(), columns.toLong()).toInt()
    val paneTop = (pane.y * lineHeight).coerceAtLeast(0f)
    val paneBottom = minOf(viewportHeight, (pane.y.toLong() + pane.rows) * lineHeight)
    val hasPane = paneRight > paneLeft && paneBottom > paneTop
    val firstLive = floor(liveScrollY / lineHeight).toInt().coerceAtLeast(0)
    val lastLive = ceil((liveScrollY + viewportHeight) / lineHeight).toInt().coerceAtMost(liveLineCount)
    for (index in firstLive until lastLive) {
        val line = liveLineAt(index) ?: continue
        val top = index * lineHeight - liveScrollY
        if (hasPane && top < paneBottom && top + lineHeight > paneTop) {
            // The overlay's blank cells cover live content too. Never fall back to that content.
            if (paneLeft > 0) parts += AccessiblePart(top, 0, line, 0, paneLeft, false, index)
            if (paneRight < columns) parts += AccessiblePart(top, paneRight, line, paneRight, columns, false, index)
        } else {
            parts += AccessiblePart(top, 0, line, 0, columns, false, index)
        }
    }
    if (hasPane) {
        val first = floor((pane.scrollY + paneTop - pane.y * lineHeight) / lineHeight).toInt().coerceAtLeast(0)
        val last = ceil((pane.scrollY + paneBottom - pane.y * lineHeight) / lineHeight).toInt()
            .coerceAtMost(pane.lines.size)
        for (index in first until last) {
            val top = pane.y * lineHeight + index * lineHeight - pane.scrollY
            parts += AccessiblePart(top, paneLeft, pane.lines[index], paneLeft - pane.x,
                paneRight - pane.x, true, index)
        }
    }
    // Fractional reader and live offsets can have different baselines. Preserve each visible source
    // row once instead of pretending their content-row indices share one coordinate system.
    parts.sortWith(compareBy<AccessiblePart> { it.top }.thenBy { it.column })
    val rows = ArrayList<AccessibleRow>()
    for (part in parts) {
        val last = rows.lastOrNull()
        if (last != null && abs(last.top - part.top) < 0.001f) last.parts += part
        else rows += AccessibleRow(part.top, arrayListOf(part))
    }
    val result = StringBuilder()
    for ((index, row) in rows.withIndex()) {
        var column = 0
        for (part in row.parts) {
            for (piece in part.pieces()) {
                val gap = (piece.column - column).coerceAtLeast(0)
                repeat(minOf(gap, maxCharacters - result.length)) { result.append(' ') }
                if (!appendAccessiblePrefix(result, piece.text, maxCharacters)) return result.toString()
                column = piece.column + piece.columns
            }
        }
        val owner = row.parts.firstOrNull { it.pinned } ?: row.parts.first()
        val next = rows.getOrNull(index + 1)
        val nextOwner = next?.let { it.parts.firstOrNull { part -> part.pinned } ?: it.parts.first() }
        val continues = owner.line.softWrappedToNext && nextOwner != null &&
            nextOwner.pinned == owner.pinned && nextOwner.sourceRow == owner.sourceRow + 1 &&
            abs(next.top - row.top - lineHeight) < 0.001f
        if (next != null && !continues) {
            if (result.length == maxCharacters) break
            result.append('\n')
        }
        if (result.length == maxCharacters) break
    }
    return result.toString()
}

private data class AccessibleRow(val top: Float, val parts: ArrayList<AccessiblePart>)
private data class AccessiblePiece(val column: Int, val columns: Int, val text: String)
private data class AccessiblePart(
    val top: Float,
    val column: Int,
    val line: TerminalLine,
    val start: Int,
    val end: Int,
    val pinned: Boolean,
    val sourceRow: Int,
) {
    fun pieces(): List<AccessiblePiece> {
        if (end <= start) return emptyList()
        val first = TerminalLineGeometry.positionAtColumn(line, start)
        // A clipped half of a wide cluster cannot expose either its hidden text or the whole live
        // row. Move inward to a complete-cell boundary, retaining all unrelated visible runs.
        val alignedStart = if (first.column < start) {
            TerminalLineGeometry.columnAtOffset(line, first.textOffset + 1)
        } else first.column
        val alignedEnd = minOf(end, TerminalLineGeometry.positionAtColumn(line, end).column)
        val cropped = TerminalLineGeometry.sliceColumns(line, alignedStart, alignedEnd) ?: return emptyList()
        return cropped.runs.filter { it.text.isNotEmpty() }.map { run ->
            AccessiblePiece(column + alignedStart - start + run.startColumn, run.columnWidth, run.text)
        }
    }
}

/** UTF-16 bound with no unpaired surrogate at the cut; source text is never rewritten. */
private fun appendAccessiblePrefix(target: StringBuilder, text: String, maximum: Int): Boolean {
    val remaining = maximum - target.length
    var end = minOf(text.length, remaining)
    if (end in 1 until text.length && Character.isHighSurrogate(text[end - 1])) end--
    target.append(text, 0, end)
    return end == text.length
}
