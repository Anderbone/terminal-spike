package com.yanjiyu.terminalspike.terminal.selection

/** Explicit user action only: TUI hard wraps cannot reliably be distinguished from real newlines. */
internal fun terminalTextAsSingleLine(text: String): String =
    text.lineSequence().joinToString("") { row -> row.trim(' ', '\t') }
