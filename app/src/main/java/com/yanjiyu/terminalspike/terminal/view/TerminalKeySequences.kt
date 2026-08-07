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
}
