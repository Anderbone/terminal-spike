package com.yanjiyu.terminalspike.terminal.view

import android.view.KeyEvent
import com.yanjiyu.terminalspike.terminal.selectKeypadSequence
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TerminalKeySequencesTest {
    @Test
    fun numericKeypadHasExactNormalAndDeckpamSequences() {
        val zero = requireNotNull(TerminalKeySequences.keypadForKeyCode(KeyEvent.KEYCODE_NUMPAD_0))
        val nine = requireNotNull(TerminalKeySequences.keypadForKeyCode(KeyEvent.KEYCODE_NUMPAD_9))
        val enter = requireNotNull(TerminalKeySequences.keypadForKeyCode(KeyEvent.KEYCODE_NUMPAD_ENTER))

        assertArrayEquals("0".toByteArray(), zero.normal)
        assertArrayEquals(byteArrayOf(0x1b, 0x4f, 0x70), zero.application)
        assertArrayEquals("9".toByteArray(), nine.normal)
        assertArrayEquals(byteArrayOf(0x1b, 0x4f, 0x79), nine.application)
        assertArrayEquals(byteArrayOf(0x0d), enter.normal)
        assertArrayEquals(byteArrayOf(0x1b, 0x4f, 0x4d), enter.application)
    }

    @Test
    fun applicationModeSelectsOnlyTheDeckpamVariant() {
        val key = requireNotNull(TerminalKeySequences.keypadForKeyCode(KeyEvent.KEYCODE_NUMPAD_ADD))

        assertArrayEquals(key.normal, selectKeypadSequence(false, key.normal, key.application))
        assertArrayEquals(key.application, selectKeypadSequence(true, key.normal, key.application))
    }

    @Test
    fun everySupportedVtKeypadKeyHasExactNormalAndSs3Encoding() {
        val expected = listOf(
            Triple(KeyEvent.KEYCODE_NUMPAD_0, '0', 'p'),
            Triple(KeyEvent.KEYCODE_NUMPAD_1, '1', 'q'),
            Triple(KeyEvent.KEYCODE_NUMPAD_2, '2', 'r'),
            Triple(KeyEvent.KEYCODE_NUMPAD_3, '3', 's'),
            Triple(KeyEvent.KEYCODE_NUMPAD_4, '4', 't'),
            Triple(KeyEvent.KEYCODE_NUMPAD_5, '5', 'u'),
            Triple(KeyEvent.KEYCODE_NUMPAD_6, '6', 'v'),
            Triple(KeyEvent.KEYCODE_NUMPAD_7, '7', 'w'),
            Triple(KeyEvent.KEYCODE_NUMPAD_8, '8', 'x'),
            Triple(KeyEvent.KEYCODE_NUMPAD_9, '9', 'y'),
            Triple(KeyEvent.KEYCODE_NUMPAD_DOT, '.', 'n'),
            Triple(KeyEvent.KEYCODE_NUMPAD_DIVIDE, '/', 'o'),
            Triple(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, '*', 'j'),
            Triple(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, '-', 'm'),
            Triple(KeyEvent.KEYCODE_NUMPAD_ADD, '+', 'k'),
            Triple(KeyEvent.KEYCODE_NUMPAD_COMMA, ',', 'l'),
            Triple(KeyEvent.KEYCODE_NUMPAD_EQUALS, '=', 'X'),
        )

        expected.forEach { (keyCode, normal, application) ->
            val key = requireNotNull(TerminalKeySequences.keypadForKeyCode(keyCode))
            assertArrayEquals(byteArrayOf(normal.code.toByte()), key.normal)
            assertArrayEquals(byteArrayOf(0x1b, 0x4f, application.code.toByte()), key.application)
        }

        val enter = requireNotNull(TerminalKeySequences.keypadForKeyCode(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertArrayEquals(byteArrayOf(0x0d), enter.normal)
        assertArrayEquals(byteArrayOf(0x1b, 0x4f, 0x4d), enter.application)
    }

    @Test
    fun physicalArrowAndFunctionModifiersUseXtermParameters() {
        assertArrayEquals(
            "\u001B[1;5A".toByteArray(),
            requireNotNull(
                TerminalKeySequences.forModifiedKeyCode(
                    KeyEvent.KEYCODE_DPAD_UP,
                    shift = false,
                    alt = false,
                    ctrl = true,
                ),
            ),
        )
        assertArrayEquals(
            "\u001B[15;8~".toByteArray(),
            requireNotNull(
                TerminalKeySequences.forModifiedKeyCode(
                    KeyEvent.KEYCODE_F5,
                    shift = true,
                    alt = true,
                    ctrl = true,
                ),
            ),
        )
        assertArrayEquals(
            byteArrayOf(0x1b, 0x5b, 0x5a),
            requireNotNull(
                TerminalKeySequences.forModifiedKeyCode(
                    KeyEvent.KEYCODE_TAB,
                    shift = true,
                    alt = false,
                    ctrl = false,
                ),
            ),
        )
    }

    @Test
    fun physicalControlCharactersCoverLettersAndAsciiPunctuation() {
        assertArrayEquals(
            byteArrayOf(0x03),
            byteArrayOf(requireNotNull(TerminalKeySequences.controlByteForKeyCode(KeyEvent.KEYCODE_C, false))),
        )
        assertArrayEquals(
            byteArrayOf(0x1c),
            byteArrayOf(
                requireNotNull(TerminalKeySequences.controlByteForKeyCode(KeyEvent.KEYCODE_BACKSLASH, false)),
            ),
        )
        assertArrayEquals(
            byteArrayOf(0x1f),
            byteArrayOf(requireNotNull(TerminalKeySequences.controlByteForKeyCode(KeyEvent.KEYCODE_MINUS, true))),
        )
    }
}
