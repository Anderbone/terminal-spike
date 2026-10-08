package com.yanjiyu.terminalspike.terminal

/** Coarse foreground metadata, independent of terminal cells and task/spinner state. */
data class TerminalInputContext(
    val identity: String = "",
    val agent: Boolean = false,
    val herdrPiScrollPane: HerdrPiScrollPane? = null,
)

/** Confirmed fullscreen Pi pane bounds; navigation chrome must retain mouse routing. */
data class HerdrPiScrollPane(val x: Int, val y: Int, val columns: Int, val rows: Int) {
    fun contains(column: Int, row: Int): Boolean =
        column in x until x + columns && row in y until y + rows
}
