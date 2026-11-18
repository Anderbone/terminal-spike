package com.yanjiyu.terminalspike.terminal.model

/**
 * Immutable draw-only text and sparse cell offsets, prepared with a run rather than during draw.
 * The original text remains authoritative for selection, copying and terminal history.
 */
internal class TerminalRunRendering private constructor(
    val text: String,
    private val columns: Int,
    val hasGridCells: Boolean,
    // Column, offset, end column, end offset, needs independent grid placement (1 or 0).
    private val cells: IntArray,
) {
    val cellCount: Int get() = cells.size / 5
    fun cellStartColumn(index: Int): Int = cells[index * 5]
    fun cellStartOffset(index: Int): Int = cells[index * 5 + 1]
    fun cellEndOffset(index: Int): Int = cells[index * 5 + 3]
    fun cellWidth(index: Int): Int = cells[index * 5 + 2] - cells[index * 5]
    fun cellNeedsGridPlacement(index: Int): Boolean = cells[index * 5 + 4] == 1

    fun endForColumns(visibleColumns: Int): Int {
        if (visibleColumns <= 0) return 0
        if (visibleColumns >= columns) return text.length
        val cell = lastCellBefore(visibleColumns, field = 0)
        if (cell < 0) return visibleColumns
        return if (visibleColumns < cells[cell + 2]) cells[cell + 3]
        else visibleColumns + cells[cell + 3] - cells[cell + 2]
    }

    fun columnAtOffset(offset: Int): Int {
        if (offset <= 0) return 0
        if (offset >= text.length) return columns
        val cell = lastCellBefore(offset, field = 1)
        if (cell < 0) return offset
        return if (offset < cells[cell + 3]) cells[cell]
        else offset + cells[cell + 2] - cells[cell + 3]
    }

    private fun lastCellBefore(value: Int, field: Int): Int {
        var low = 0
        var high = cells.size / 5
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cells[middle * 5 + field] < value) low = middle + 1 else high = middle
        }
        return (low - 1) * 5
    }

    companion object {
        /** Bounds shaping and legacy fallback work per visible cell, including hostile mark chains. */
        internal const val MAX_CELL_UTF16_UNITS = 64

        /** Null keeps ordinary one-unit/one-column runs on the existing allocation-free draw path. */
        fun from(source: String): TerminalRunRendering? {
            var offset = 0
            var column = 0
            var hasGridCells = false
            var replacement: StringBuilder? = null
            var specialCells: ArrayList<Int>? = null
            while (offset < source.length) {
                val start = offset
                var previous = source.codePointAt(offset)
                val supplementaryBase = Character.charCount(previous) == 2
                val replacementBase = previous == 0xFFFD
                val width = TerminalCellWidth.of(previous).coerceAtLeast(1)
                var regionalIndicators = if (TerminalCellWidth.isRegionalIndicator(previous)) 1 else 0
                offset += Character.charCount(previous)
                while (offset < source.length) {
                    val next = source.codePointAt(offset)
                    if (TerminalCellWidth.of(next) != 0 && previous != ZERO_WIDTH_JOINER &&
                        !(TerminalCellWidth.isRegionalIndicator(next) && regionalIndicators % 2 == 1)
                    ) break
                    if (TerminalCellWidth.isRegionalIndicator(next)) regionalIndicators++
                    previous = next
                    offset += Character.charCount(next)
                }
                val drawStart = replacement?.length ?: start
                val oversized = offset - start > MAX_CELL_UTF16_UNITS
                if (oversized) {
                    val target = replacement ?: StringBuilder().append(source, 0, start).also { replacement = it }
                    target.append('\uFFFD')
                    if (width == 2) target.append(' ')
                } else {
                    replacement?.append(source, start, offset)
                }
                val drawEnd = replacement?.length ?: offset
                val needsGridPlacement = width == 2 || supplementaryBase || replacementBase || oversized
                if (needsGridPlacement || drawEnd - drawStart != width) {
                    val target = specialCells ?: ArrayList<Int>().also { specialCells = it }
                    target.add(column)
                    target.add(drawStart)
                    target.add(column + width)
                    target.add(drawEnd)
                    target.add(if (needsGridPlacement) 1 else 0)
                }
                if (needsGridPlacement) hasGridCells = true
                column += width
            }
            if (replacement == null && specialCells == null) return null
            return TerminalRunRendering(replacement?.toString() ?: source, column, hasGridCells,
                specialCells?.toIntArray() ?: IntArray(0))
        }

        private const val ZERO_WIDTH_JOINER = 0x200D
    }
}
