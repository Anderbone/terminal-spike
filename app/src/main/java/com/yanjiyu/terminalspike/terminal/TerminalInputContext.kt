package com.yanjiyu.terminalspike.terminal

/** Coarse foreground metadata, independent of terminal cells and task/spinner state. */
data class TerminalInputContext(
    val identity: String = "",
    val agent: Boolean = false,
)
