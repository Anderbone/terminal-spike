package com.yanjiyu.terminalspike.terminal.view

import android.view.KeyEvent

object TerminalKeySequences {
    val ESCAPE = byteArrayOf(0x1b)
    val ENTER = byteArrayOf(0x0d)
    val BACKSPACE = byteArrayOf(0x7f)
    val TAB = byteArrayOf(0x09)
    val ARROW_UP = byteArrayOf(0x1b, 0x5b, 0x41)
    val ARROW_DOWN = byteArrayOf(0x1b, 0x5b, 0x42)
    val ARROW_RIGHT = byteArrayOf(0x1b, 0x5b, 0x43)
    val ARROW_LEFT = byteArrayOf(0x1b, 0x5b, 0x44)
    val PAGE_UP = byteArrayOf(0x1b, 0x5b, 0x35, 0x7e)
    val PAGE_DOWN = byteArrayOf(0x1b, 0x5b, 0x36, 0x7e)

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
        else -> null
    }
}
