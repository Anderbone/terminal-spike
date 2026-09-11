package com.yanjiyu.terminalspike.terminal.view

/** The live terminal starts on a whole row; unused pixels belong below the grid, not above it. */
internal data class TerminalGridGeometry(val columns: Int, val rows: Int, val heightPx: Int)

internal fun terminalGridGeometry(
    width: Int,
    height: Int,
    horizontalPadding: Float,
    verticalPadding: Float,
    cellWidth: Float,
    lineHeight: Float,
): TerminalGridGeometry? {
    if (!cellWidth.isFinite() || !lineHeight.isFinite() || cellWidth <= 0f || lineHeight <= 0f) return null
    val columns = ((width - horizontalPadding * 2f) / cellWidth).toInt()
    val rows = ((height - verticalPadding * 2f) / lineHeight).toInt()
    if (columns < 1 || rows < 1) return null
    return TerminalGridGeometry(columns, rows, (rows * lineHeight).toInt())
}
