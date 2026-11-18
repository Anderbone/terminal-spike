package com.yanjiyu.terminalspike.terminal.engine

internal const val CELL_TEXT_FIXTURE_COLUMNS = 64
internal const val CELL_TEXT_FIXTURE_ROWS = 40
internal const val EXPECTED_SCREEN_EXTRA_UTF16_UNITS = 1_048_576

/** Independent retention contract: two base UTF-16 units per cell plus a bounded extension pool. */
internal fun cellTextBudgetBound(columns: Int, rows: Int): Long =
    2L * columns * rows + EXPECTED_SCREEN_EXTRA_UTF16_UNITS

internal fun screenCellTextUnits(frame: TerminalFrameUpdate): Long =
    frame.screen.sumOf { it.text.length.toLong() }

/** Only owned parser data: no terminal controller, document, network or user preference. */
internal fun fillScreenWithCombiningCells(
    engine: VtTerminalEngine,
    reverseRows: Boolean = false,
    wideFirst: Boolean = false,
): TerminalFrameUpdate {
    val marks = "\u0301".repeat(511)
    val packet = StringBuilder()
    var frame: TerminalFrameUpdate? = null
    fun flush() {
        if (packet.isNotEmpty()) {
            frame = engine.accept(packet.toString().toByteArray(Charsets.UTF_8))
            packet.setLength(0)
        }
    }
    val rowOrder = if (reverseRows) (CELL_TEXT_FIXTURE_ROWS - 1 downTo 0)
        else (0 until CELL_TEXT_FIXTURE_ROWS)
    for (row in rowOrder) {
        var column = 0
        while (column < CELL_TEXT_FIXTURE_COLUMNS) {
            val wide = wideFirst && row == 0 && column == 0
            packet.append("\u001b[").append(row + 1).append(';').append(column + 1).append('H')
                .append(if (wide) "界" else "a").append(marks)
            column += if (wide) 2 else 1
            if (packet.length >= 4_096) flush()
        }
    }
    flush()
    return requireNotNull(frame)
}
