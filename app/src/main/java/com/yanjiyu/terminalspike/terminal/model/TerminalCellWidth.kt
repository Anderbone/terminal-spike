package com.yanjiyu.terminalspike.terminal.model

/** Shared terminal-cell width rule used by both parser storage and legacy glyph fallback drawing. */
internal object TerminalCellWidth {
    fun of(codePoint: Int): Int = when {
        codePoint == 0 -> 0
        codePoint in 0x1F3FB..0x1F3FF -> 0
        isCombining(codePoint) -> 0
        isRegionalIndicator(codePoint) -> 2
        isWide(codePoint) -> 2
        else -> 1
    }

    fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

    private fun isCombining(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.FORMAT.toInt(),
        -> true
        else -> false
    }

    private fun isWide(codePoint: Int): Boolean =
        codePoint in 0x1100..0x115F ||
            codePoint in 0x2329..0x232A ||
            codePoint in 0x2E80..0xA4CF && codePoint != 0x303F ||
            codePoint in 0xAC00..0xD7A3 ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0xFE10..0xFE19 ||
            codePoint in 0xFE30..0xFE6F ||
            codePoint in 0xFF00..0xFF60 ||
            codePoint in 0xFFE0..0xFFE6 ||
            codePoint in 0x1F300..0x1FAFF ||
            codePoint in 0x20000..0x3FFFD
}
