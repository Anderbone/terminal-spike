package com.yanjiyu.terminalspike.terminal.view

import android.view.KeyEvent

object TerminalKeySequences {
    data class KeypadKey(
        val normal: ByteArray,
        val application: ByteArray,
    )

    val ESCAPE = byteArrayOf(0x1b)
    val ENTER = byteArrayOf(0x0d)
    val BACKSPACE = byteArrayOf(0x7f)
    val TAB = byteArrayOf(0x09)
    val SHIFT_TAB = byteArrayOf(0x1b, 0x5b, 0x5a)
    val ARROW_UP = byteArrayOf(0x1b, 0x5b, 0x41)
    val ARROW_DOWN = byteArrayOf(0x1b, 0x5b, 0x42)
    val ARROW_RIGHT = byteArrayOf(0x1b, 0x5b, 0x43)
    val ARROW_LEFT = byteArrayOf(0x1b, 0x5b, 0x44)
    val PAGE_UP = byteArrayOf(0x1b, 0x5b, 0x35, 0x7e)
    val PAGE_DOWN = byteArrayOf(0x1b, 0x5b, 0x36, 0x7e)
    val HOME = byteArrayOf(0x1b, 0x5b, 0x48)
    val END = byteArrayOf(0x1b, 0x5b, 0x46)
    val INSERT = byteArrayOf(0x1b, 0x5b, 0x32, 0x7e)
    val DELETE = byteArrayOf(0x1b, 0x5b, 0x33, 0x7e)
    val F1 = byteArrayOf(0x1b, 0x4f, 0x50)
    val F2 = byteArrayOf(0x1b, 0x4f, 0x51)
    val F3 = byteArrayOf(0x1b, 0x4f, 0x52)
    val F4 = byteArrayOf(0x1b, 0x4f, 0x53)
    val F5 = byteArrayOf(0x1b, 0x5b, 0x31, 0x35, 0x7e)
    val F6 = byteArrayOf(0x1b, 0x5b, 0x31, 0x37, 0x7e)
    val F7 = byteArrayOf(0x1b, 0x5b, 0x31, 0x38, 0x7e)
    val F8 = byteArrayOf(0x1b, 0x5b, 0x31, 0x39, 0x7e)
    val F9 = byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x7e)
    val F10 = byteArrayOf(0x1b, 0x5b, 0x32, 0x31, 0x7e)
    val F11 = byteArrayOf(0x1b, 0x5b, 0x32, 0x33, 0x7e)
    val F12 = byteArrayOf(0x1b, 0x5b, 0x32, 0x34, 0x7e)

    fun keypadForKeyCode(keyCode: Int): KeypadKey? = when (keyCode) {
        KeyEvent.KEYCODE_NUMPAD_0 -> keypad('0', 'p')
        KeyEvent.KEYCODE_NUMPAD_1 -> keypad('1', 'q')
        KeyEvent.KEYCODE_NUMPAD_2 -> keypad('2', 'r')
        KeyEvent.KEYCODE_NUMPAD_3 -> keypad('3', 's')
        KeyEvent.KEYCODE_NUMPAD_4 -> keypad('4', 't')
        KeyEvent.KEYCODE_NUMPAD_5 -> keypad('5', 'u')
        KeyEvent.KEYCODE_NUMPAD_6 -> keypad('6', 'v')
        KeyEvent.KEYCODE_NUMPAD_7 -> keypad('7', 'w')
        KeyEvent.KEYCODE_NUMPAD_8 -> keypad('8', 'x')
        KeyEvent.KEYCODE_NUMPAD_9 -> keypad('9', 'y')
        KeyEvent.KEYCODE_NUMPAD_DOT -> keypad('.', 'n')
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> keypad('/', 'o')
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> keypad('*', 'j')
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> keypad('-', 'm')
        KeyEvent.KEYCODE_NUMPAD_ADD -> keypad('+', 'k')
        KeyEvent.KEYCODE_NUMPAD_COMMA -> keypad(',', 'l')
        KeyEvent.KEYCODE_NUMPAD_EQUALS -> keypad('=', 'X')
        KeyEvent.KEYCODE_NUMPAD_ENTER -> KeypadKey(ENTER, ss3('M'))
        else -> null
    }

    fun forKeyCode(keyCode: Int): ByteArray? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ENTER
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_ESCAPE -> ESCAPE
        KeyEvent.KEYCODE_DPAD_UP -> ARROW_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> ARROW_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> ARROW_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> ARROW_RIGHT
        KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> HOME
        KeyEvent.KEYCODE_MOVE_END -> END
        KeyEvent.KEYCODE_INSERT -> INSERT
        KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
        KeyEvent.KEYCODE_F1 -> F1
        KeyEvent.KEYCODE_F2 -> F2
        KeyEvent.KEYCODE_F3 -> F3
        KeyEvent.KEYCODE_F4 -> F4
        KeyEvent.KEYCODE_F5 -> F5
        KeyEvent.KEYCODE_F6 -> F6
        KeyEvent.KEYCODE_F7 -> F7
        KeyEvent.KEYCODE_F8 -> F8
        KeyEvent.KEYCODE_F9 -> F9
        KeyEvent.KEYCODE_F10 -> F10
        KeyEvent.KEYCODE_F11 -> F11
        KeyEvent.KEYCODE_F12 -> F12
        else -> null
    }

    /** Xterm-compatible modifier encoding for physical non-text keys. */
    fun forModifiedKeyCode(
        keyCode: Int,
        shift: Boolean,
        alt: Boolean,
        ctrl: Boolean,
    ): ByteArray? {
        if (!shift && !alt && !ctrl) return forKeyCode(keyCode)
        val modifier = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> modifiedCsi("1", modifier, 'A')
            KeyEvent.KEYCODE_DPAD_DOWN -> modifiedCsi("1", modifier, 'B')
            KeyEvent.KEYCODE_DPAD_RIGHT -> modifiedCsi("1", modifier, 'C')
            KeyEvent.KEYCODE_DPAD_LEFT -> modifiedCsi("1", modifier, 'D')
            KeyEvent.KEYCODE_MOVE_HOME -> modifiedCsi("1", modifier, 'H')
            KeyEvent.KEYCODE_MOVE_END -> modifiedCsi("1", modifier, 'F')
            KeyEvent.KEYCODE_INSERT -> modifiedTilde("2", modifier)
            KeyEvent.KEYCODE_FORWARD_DEL -> modifiedTilde("3", modifier)
            KeyEvent.KEYCODE_PAGE_UP -> modifiedTilde("5", modifier)
            KeyEvent.KEYCODE_PAGE_DOWN -> modifiedTilde("6", modifier)
            KeyEvent.KEYCODE_F1 -> modifiedCsi("1", modifier, 'P')
            KeyEvent.KEYCODE_F2 -> modifiedCsi("1", modifier, 'Q')
            KeyEvent.KEYCODE_F3 -> modifiedCsi("1", modifier, 'R')
            KeyEvent.KEYCODE_F4 -> modifiedCsi("1", modifier, 'S')
            KeyEvent.KEYCODE_F5 -> modifiedTilde("15", modifier)
            KeyEvent.KEYCODE_F6 -> modifiedTilde("17", modifier)
            KeyEvent.KEYCODE_F7 -> modifiedTilde("18", modifier)
            KeyEvent.KEYCODE_F8 -> modifiedTilde("19", modifier)
            KeyEvent.KEYCODE_F9 -> modifiedTilde("20", modifier)
            KeyEvent.KEYCODE_F10 -> modifiedTilde("21", modifier)
            KeyEvent.KEYCODE_F11 -> modifiedTilde("23", modifier)
            KeyEvent.KEYCODE_F12 -> modifiedTilde("24", modifier)
            KeyEvent.KEYCODE_TAB -> if (shift && !alt && !ctrl) SHIFT_TAB else altPrefix(TAB, alt)
            KeyEvent.KEYCODE_ENTER -> altPrefix(ENTER, alt)
            KeyEvent.KEYCODE_DEL -> altPrefix(BACKSPACE, alt)
            KeyEvent.KEYCODE_ESCAPE -> altPrefix(ESCAPE, alt)
            else -> null
        }
    }

    fun controlByteForKeyCode(keyCode: Int, shift: Boolean): Byte? = when {
        keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
            (keyCode - KeyEvent.KEYCODE_A + 1).toByte()
        keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_AT ||
            keyCode == KeyEvent.KEYCODE_2 -> 0
        keyCode == KeyEvent.KEYCODE_LEFT_BRACKET -> 0x1b
        keyCode == KeyEvent.KEYCODE_BACKSLASH -> 0x1c
        keyCode == KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x1d
        keyCode == KeyEvent.KEYCODE_6 && shift -> 0x1e
        keyCode == KeyEvent.KEYCODE_MINUS && shift -> 0x1f
        else -> null
    }

    private fun keypad(normal: Char, application: Char): KeypadKey = KeypadKey(
        normal = byteArrayOf(normal.code.toByte()),
        application = ss3(application),
    )

    private fun ss3(final: Char): ByteArray = byteArrayOf(0x1b, 0x4f, final.code.toByte())

    private fun modifiedCsi(prefix: String, modifier: Int, final: Char): ByteArray =
        "\u001B[$prefix;$modifier$final".toByteArray(Charsets.US_ASCII)

    private fun modifiedTilde(prefix: String, modifier: Int): ByteArray =
        "\u001B[$prefix;$modifier~".toByteArray(Charsets.US_ASCII)

    private fun altPrefix(bytes: ByteArray, alt: Boolean): ByteArray = if (alt) ESCAPE + bytes else bytes

}
