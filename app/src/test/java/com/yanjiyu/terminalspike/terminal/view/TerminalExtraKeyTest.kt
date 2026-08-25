package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.ui.uiText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalExtraKeyTest {
    @Test
    fun directControlChordsUseCompactLabelsAndSpokenDescriptions() {
        val expected = listOf(
            TerminalExtraKey.CTRL_C to ("^C" to R.string.terminal_key_control_c),
            TerminalExtraKey.CTRL_W to ("^W" to R.string.terminal_key_control_w),
            TerminalExtraKey.CTRL_D to ("^D" to R.string.terminal_key_control_d),
            TerminalExtraKey.CTRL_L to ("^L" to R.string.terminal_key_control_l),
            TerminalExtraKey.CTRL_R to ("^R" to R.string.terminal_key_control_r),
            TerminalExtraKey.CTRL_U to ("^U" to R.string.terminal_key_control_u),
            TerminalExtraKey.CTRL_A to ("^A" to R.string.terminal_key_control_a),
            TerminalExtraKey.CTRL_E to ("^E" to R.string.terminal_key_control_e),
        )

        expected.forEach { (key, presentation) ->
            assertEquals(presentation.first, key.label)
            assertEquals(uiText(presentation.second), key.accessibilityDescription)
        }
    }

    @Test
    fun directControlChordsEmitExactAsciiControlBytes() {
        val expected = listOf(
            TerminalExtraKey.CTRL_C to 0x03,
            TerminalExtraKey.CTRL_W to 0x17,
            TerminalExtraKey.CTRL_D to 0x04,
            TerminalExtraKey.CTRL_L to 0x0c,
            TerminalExtraKey.CTRL_R to 0x12,
            TerminalExtraKey.CTRL_U to 0x15,
            TerminalExtraKey.CTRL_A to 0x01,
            TerminalExtraKey.CTRL_E to 0x05,
        )

        expected.forEach { (key, byte) ->
            assertArrayEquals(byteArrayOf(byte.toByte()), requireNotNull(key.bytes))
        }
    }

    @Test
    fun defaultDeckIsExactlyTwentyDirectChordsModifiersAndNavigationKeys() {
        assertEquals(
            listOf(
                TerminalExtraKey.ESC,
                TerminalExtraKey.SLASH,
                TerminalExtraKey.AT,
                TerminalExtraKey.DOLLAR,
                TerminalExtraKey.HOME,
                TerminalExtraKey.UP,
                TerminalExtraKey.END,
                TerminalExtraKey.PAGE_UP,
                TerminalExtraKey.CTRL_B,
                TerminalExtraKey.BACKSPACE,
                TerminalExtraKey.TAB,
                TerminalExtraKey.CTRL,
                TerminalExtraKey.ALT,
                TerminalExtraKey.CTRL_C,
                TerminalExtraKey.CTRL_W,
                TerminalExtraKey.LEFT,
                TerminalExtraKey.DOWN,
                TerminalExtraKey.RIGHT,
                TerminalExtraKey.ENTER,
                TerminalExtraKey.HIDE_KEYBOARD,
            ),
            TerminalExtraKey.DEFAULT_ORDER,
        )
        assertEquals(20, TerminalExtraKey.DEFAULT_ORDER.size)
    }
}
