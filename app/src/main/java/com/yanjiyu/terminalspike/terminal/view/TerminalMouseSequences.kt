package com.yanjiyu.terminalspike.terminal.view

internal object TerminalMouseSequences {
    fun click(
        column: Int,
        row: Int,
        sgrEncoding: Boolean,
        reportRelease: Boolean = true,
        secondary: Boolean = false,
    ): ByteArray {
        val button = if (secondary) 2 else 0
        val press = report(button, column, row, sgrEncoding)
        if (!reportRelease) return press
        return press + report(if (sgrEncoding) button else 3, column, row, sgrEncoding, release = true)
    }

    fun wheel(up: Boolean, column: Int, row: Int, sgrEncoding: Boolean): ByteArray {
        return report(if (up) WHEEL_UP else WHEEL_DOWN, column, row, sgrEncoding)
    }

    private fun report(
        button: Int,
        column: Int,
        row: Int,
        sgrEncoding: Boolean,
        release: Boolean = false,
    ): ByteArray {
        val oneBasedColumn = column.coerceAtLeast(0) + 1
        val oneBasedRow = row.coerceAtLeast(0) + 1
        if (sgrEncoding) {
            val suffix = if (release) 'm' else 'M'
            return "\u001B[<${button};${oneBasedColumn};${oneBasedRow}$suffix".toByteArray(Charsets.US_ASCII)
        }

        return byteArrayOf(
            ESCAPE,
            CSI,
            MOUSE,
            (button + LEGACY_OFFSET).toByte(),
            (oneBasedColumn.coerceAtMost(LEGACY_COORDINATE_MAX) + LEGACY_OFFSET).toByte(),
            (oneBasedRow.coerceAtMost(LEGACY_COORDINATE_MAX) + LEGACY_OFFSET).toByte(),
        )
    }

    private const val WHEEL_UP = 64
    private const val WHEEL_DOWN = 65
    private const val LEGACY_OFFSET = 32
    private const val LEGACY_COORDINATE_MAX = 223
    private const val ESCAPE = 0x1b.toByte()
    private const val CSI = 0x5b.toByte()
    private const val MOUSE = 0x4d.toByte()
}
