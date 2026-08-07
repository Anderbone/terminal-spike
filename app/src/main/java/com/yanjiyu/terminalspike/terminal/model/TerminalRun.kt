package com.yanjiyu.terminalspike.terminal.model

data class TerminalRun(
    val text: String,
    val style: TerminalStyle = TerminalStyle(),
)
