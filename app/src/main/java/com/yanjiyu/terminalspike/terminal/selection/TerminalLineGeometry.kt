package com.yanjiyu.terminalspike.terminal.selection

import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import kotlin.math.roundToInt

data class TerminalLinePosition(
    val textOffset: Int,
    val column: Int,
)

/** Cell/text mapping used only for gestures, selection and links, never for the draw hot path. */
object TerminalLineGeometry {
    fun positionAtColumn(line: TerminalLine, requestedColumn: Int): TerminalLinePosition {
        val target = requestedColumn.coerceAtLeast(0)
        var textOffset = 0
        var fallbackColumn = 0
        line.runs.forEach { run ->
            val geometry = geometry(run, fallbackColumn)
            if (target <= geometry.startColumn) {
                return TerminalLinePosition(textOffset, geometry.startColumn)
            }
            if (target < geometry.endColumn) {
                val localPosition = geometry.positionAtColumn(target - geometry.startColumn)
                return TerminalLinePosition(
                    textOffset = textOffset + localPosition.textOffset,
                    column = geometry.startColumn + localPosition.column,
                )
            }
            textOffset += run.text.length
            fallbackColumn = maxOf(fallbackColumn, geometry.endColumn)
        }
        return TerminalLinePosition(line.text.length, fallbackColumn)
    }

    fun columnAtOffset(line: TerminalLine, requestedOffset: Int): Int {
        val target = requestedOffset.coerceIn(0, line.text.length)
        var textOffset = 0
        var fallbackColumn = 0
        line.runs.forEach { run ->
            val geometry = geometry(run, fallbackColumn)
            val runEndOffset = textOffset + run.text.length
            if (target <= runEndOffset) {
                return geometry.startColumn + geometry.columnAtOffset(target - textOffset)
            }
            textOffset = runEndOffset
            fallbackColumn = maxOf(fallbackColumn, geometry.endColumn)
        }
        return fallbackColumn
    }

    fun columnCount(line: TerminalLine): Int {
        var fallbackColumn = 0
        line.runs.forEach { run ->
            fallbackColumn = maxOf(fallbackColumn, geometry(run, fallbackColumn).endColumn)
        }
        return fallbackColumn
    }

    internal fun runColumns(run: TerminalRun, fallbackStartColumn: Int): IntRange {
        val value = geometry(run, fallbackStartColumn)
        return value.startColumn until value.endColumn
    }

    private fun geometry(run: TerminalRun, fallbackStartColumn: Int): RunGeometry {
        val clusters = textClusters(run.text)
        val measuredWidth = clusters.sumOf(TextCluster::cellWidth).coerceAtLeast(0)
        val start = if (run.startColumn >= 0) run.startColumn else fallbackStartColumn
        val width = if (run.columnWidth >= 0) run.columnWidth else measuredWidth
        return RunGeometry(start, width, run.text.length, clusters, measuredWidth)
    }
}

private data class TextCluster(
    val startOffset: Int,
    val endOffset: Int,
    val cellWidth: Int,
    val regionalIndicatorCount: Int = 0,
)

private data class RunGeometry(
    val startColumn: Int,
    val width: Int,
    val textLength: Int,
    val clusters: List<TextCluster>,
    val measuredWidth: Int,
) {
    val endColumn: Int
        get() = startColumn + width

    fun positionAtColumn(localColumn: Int): TerminalLinePosition {
        if (localColumn <= 0 || textLength == 0) return TerminalLinePosition(0, 0)
        if (localColumn >= width) return TerminalLinePosition(textLength, width)
        if (clusters.isEmpty() || measuredWidth <= 0 || width <= 0) return TerminalLinePosition(0, 0)
        val measuredTarget = localColumn.toDouble() * measuredWidth / width
        var measuredStart = 0
        for (cluster in clusters) {
            val measuredEnd = measuredStart + cluster.cellWidth
            if (measuredTarget < measuredEnd) {
                val snappedColumn = (measuredStart.toDouble() * width / measuredWidth)
                    .roundToInt()
                    .coerceIn(0, width)
                return TerminalLinePosition(cluster.startOffset, snappedColumn)
            }
            measuredStart = measuredEnd
        }
        return TerminalLinePosition(textLength, width)
    }

    fun columnAtOffset(localOffset: Int): Int {
        if (localOffset <= 0 || clusters.isEmpty() || measuredWidth <= 0 || width <= 0) return 0
        if (localOffset >= textLength) return width
        var cells = 0
        for (cluster in clusters) {
            if (localOffset <= cluster.startOffset) break
            cells += cluster.cellWidth
            if (localOffset < cluster.endOffset) break
        }
        return (cells.toDouble() * width / measuredWidth).roundToInt().coerceIn(0, width)
    }
}

private fun textClusters(text: String): List<TextCluster> {
    if (text.isEmpty()) return emptyList()
    val clusters = ArrayList<TextCluster>(text.codePointCount(0, text.length))
    var offset = 0
    var joinNext = false
    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        val next = offset + Character.charCount(codePoint)
        val width = terminalCellWidth(codePoint)
        val previous = clusters.lastOrNull()
        when {
            codePoint == ZERO_WIDTH_JOINER -> {
                if (previous != null) {
                    clusters[clusters.lastIndex] = previous.copy(endOffset = next)
                    joinNext = true
                }
            }
            joinNext && previous != null -> {
                clusters[clusters.lastIndex] = previous.copy(
                    endOffset = next,
                    cellWidth = maxOf(previous.cellWidth, width),
                )
                joinNext = false
            }
            width == 0 && previous != null -> {
                clusters[clusters.lastIndex] = previous.copy(endOffset = next)
            }
            isRegionalIndicator(codePoint) && previous?.regionalIndicatorCount == 1 -> {
                clusters[clusters.lastIndex] = previous.copy(
                    endOffset = next,
                    cellWidth = 2,
                    regionalIndicatorCount = 2,
                )
            }
            else -> {
                clusters += TextCluster(
                    startOffset = offset,
                    endOffset = next,
                    cellWidth = width.coerceAtLeast(1),
                    regionalIndicatorCount = if (isRegionalIndicator(codePoint)) 1 else 0,
                )
                joinNext = false
            }
        }
        offset = next
    }
    return clusters
}

private fun terminalCellWidth(codePoint: Int): Int {
    val type = Character.getType(codePoint)
    if (
        type == Character.NON_SPACING_MARK.toInt() ||
        type == Character.COMBINING_SPACING_MARK.toInt() ||
        type == Character.ENCLOSING_MARK.toInt() ||
        codePoint == ZERO_WIDTH_JOINER ||
        codePoint in 0x1F3FB..0x1F3FF ||
        codePoint in 0xFE00..0xFE0F ||
        codePoint in 0xE0020..0xE007F ||
        codePoint in 0xE0100..0xE01EF
    ) {
        return 0
    }
    return if (
        codePoint in 0x1100..0x115F ||
        codePoint == 0x2329 || codePoint == 0x232A ||
        codePoint in 0x2E80..0xA4CF ||
        codePoint in 0xAC00..0xD7A3 ||
        codePoint in 0xF900..0xFAFF ||
        codePoint in 0xFE10..0xFE19 ||
        codePoint in 0xFE30..0xFE6F ||
        codePoint in 0xFF00..0xFF60 ||
        codePoint in 0xFFE0..0xFFE6 ||
        codePoint in 0x1F1E6..0x1FAFF ||
        codePoint in 0x20000..0x3FFFD
    ) {
        2
    } else {
        1
    }
}

private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

private const val ZERO_WIDTH_JOINER = 0x200D
