package com.yanjiyu.terminalspike.terminal.view

internal object TerminalMouseSequences {
    fun wheel(up: Boolean, column: Int, row: Int, sgrEncoding: Boolean): ByteArray {
        val oneBasedColumn = column.coerceAtLeast(0) + 1
        val oneBasedRow = row.coerceAtLeast(0) + 1
        val button = if (up) WHEEL_UP else WHEEL_DOWN
        if (sgrEncoding) {
            return "\u001B[<${button};${oneBasedColumn};${oneBasedRow}M".toByteArray(Charsets.US_ASCII)
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
