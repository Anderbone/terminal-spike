package com.yanjiyu.terminalspike.terminal.model

data class TerminalStyle(
    val foreground: Int = TerminalPalette.FOREGROUND,
    val background: Int = TerminalPalette.BACKGROUND,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
)

object TerminalPalette {
    const val BACKGROUND: Int = -0xf7f3f0
    const val FOREGROUND: Int = -0x18202a
    const val BLACK: Int = -0xd5d1ce
    const val RED: Int = -0x32b8d
    const val GREEN: Int = -0x7f55a8
    const val YELLOW: Int = -0x4480
    const val BLUE: Int = -0xa94b01
    const val MAGENTA: Int = -0x5b2bb7
    const val CYAN: Int = -0xb75742
    const val WHITE: Int = -0x1
    const val CURSOR: Int = -0x102824
    const val SELECTION_MUTED: Int = -0xd5c2a2
}
