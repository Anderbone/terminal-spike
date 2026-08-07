package com.yanjiyu.terminalspike.terminal.view

enum class TerminalExtraKey(
    val label: String,
    val bytes: ByteArray? = null,
    val controlBytes: ByteArray? = null,
) {
    ESC("ESC", TerminalKeySequences.ESCAPE),
    CTRL("CTRL"),
    ALT("ALT"),
    TAB("TAB", TerminalKeySequences.TAB),
    UP("↑", TerminalKeySequences.ARROW_UP, byteArrayOf(0x1b, 0x5b, 0x31, 0x3b, 0x35, 0x41)),
    DOWN("↓", TerminalKeySequences.ARROW_DOWN, byteArrayOf(0x1b, 0x5b, 0x31, 0x3b, 0x35, 0x42)),
    LEFT("←", TerminalKeySequences.ARROW_LEFT, byteArrayOf(0x1b, 0x5b, 0x31, 0x3b, 0x35, 0x44)),
    RIGHT("→", TerminalKeySequences.ARROW_RIGHT, byteArrayOf(0x1b, 0x5b, 0x31, 0x3b, 0x35, 0x43)),
    PAGE_UP("PGUP", TerminalKeySequences.PAGE_UP, byteArrayOf(0x1b, 0x5b, 0x35, 0x3b, 0x35, 0x7e)),
    PAGE_DOWN("PGDN", TerminalKeySequences.PAGE_DOWN, byteArrayOf(0x1b, 0x5b, 0x36, 0x3b, 0x35, 0x7e)),
    HOME("HOME", TerminalKeySequences.HOME),
    END("END", TerminalKeySequences.END),
    DELETE("DEL", TerminalKeySequences.DELETE),
    ;

    val isModifier: Boolean get() = this == CTRL || this == ALT

    companion object {
        val DEFAULT_ORDER: List<TerminalExtraKey> = entries.toList()
    }
}
