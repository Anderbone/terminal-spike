package com.yanjiyu.terminalspike.terminal.model

data class TerminalRun(
    val text: String,
    val style: TerminalStyle = TerminalStyle(),
    val hyperlink: TerminalHyperlink? = null,
    /** Screen column occupied by the first cell, or -1 for a non-screen synthetic run. */
    val startColumn: Int = -1,
    /** Number of terminal cells occupied, or -1 when the producer did not retain geometry. */
    val columnWidth: Int = -1,
) {
    init {
        require(startColumn >= -1)
        require(columnWidth >= -1)
        require((startColumn == -1) == (columnWidth == -1))
    }
}
