package com.yanjiyu.terminalspike.terminal.view

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TerminalMouseSequencesTest {
    @Test
    fun encodesSgrWheelAtOneBasedCellPosition() {
        assertArrayEquals(
            "\u001B[<64;5;8M".toByteArray(Charsets.US_ASCII),
            TerminalMouseSequences.wheel(up = true, column = 4, row = 7, sgrEncoding = true),
        )
        assertArrayEquals(
            "\u001B[<65;1;1M".toByteArray(Charsets.US_ASCII),
            TerminalMouseSequences.wheel(up = false, column = 0, row = 0, sgrEncoding = true),
        )
    }

    @Test
    fun encodesAndBoundsLegacyWheelCoordinates() {
        assertArrayEquals(
            byteArrayOf(0x1b, 0x5b, 0x4d, 96, 33, 33),
            TerminalMouseSequences.wheel(up = true, column = 0, row = 0, sgrEncoding = false),
        )
        assertArrayEquals(
            byteArrayOf(0x1b, 0x5b, 0x4d, 97, 0xff.toByte(), 0xff.toByte()),
            TerminalMouseSequences.wheel(up = false, column = 999, row = 999, sgrEncoding = false),
        )
    }
}
