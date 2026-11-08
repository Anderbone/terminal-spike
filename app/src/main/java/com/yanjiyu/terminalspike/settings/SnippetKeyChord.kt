package com.yanjiyu.terminalspike.settings

import android.view.KeyEvent
import com.yanjiyu.terminalspike.terminal.view.TerminalKeySequences
import java.util.Locale

/** Terminal chords use xterm navigation sequences, legacy control bytes, and CSI-u otherwise. */
object SnippetKeyChord {
    fun encode(source: String): ByteArray? {
        val parts = source.lowercase(Locale.ROOT).split('+')
        val modifiers = parts.dropLast(1)
        if (modifiers.distinct().size != modifiers.size ||
            modifiers.any { it !in setOf("ctrl", "alt", "shift", "meta", "super") }) return null
        val key = parts.last()
        val shift = "shift" in modifiers
        val alt = "alt" in modifiers
        val ctrl = "ctrl" in modifiers
        val meta = "meta" in modifiers || "super" in modifiers
        val modifier = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) +
            (if (ctrl) 4 else 0) + (if (meta) 8 else 0)
        val keyCode = namedKeys[key] ?: key.removePrefix("f").toIntOrNull()
            ?.takeIf { key.startsWith("f") && it in 1..12 }?.let { KeyEvent.KEYCODE_F1 + it - 1 }
        if (keyCode != null && key !in setOf("enter", "tab", "backspace", "escape", "esc")) {
            if (meta) {
                // Xterm modifier numbers also carry the Meta bit.
                val base = TerminalKeySequences.forModifiedKeyCode(keyCode, true, true, true) ?: return null
                return base.toString(Charsets.US_ASCII).replace(";8", ";$modifier").toByteArray()
            }
            return TerminalKeySequences.forModifiedKeyCode(keyCode, shift, alt, ctrl)
        }
        val character = when (key) {
            "enter" -> '\r'
            "tab" -> '\t'
            "backspace" -> '\u007f'
            "escape", "esc" -> '\u001b'
            "space" -> ' '
            "plus" -> '+'
            else -> source.substringAfterLast('+').singleOrNull()?.takeUnless { it.isWhitespace() || it.isISOControl() } ?: return null
        }
        if (key == "tab" && shift && !alt && !ctrl && !meta) return TerminalKeySequences.SHIFT_TAB
        val effectiveCharacter = if (shift) shiftCharacter(character) else character
        val control = if (ctrl) when (effectiveCharacter) {
            in 'a'..'z', in 'A'..'Z' -> effectiveCharacter.code and 31
            ' ', '@', '2' -> 0
            '[', '\\', ']' -> effectiveCharacter.code and 31
            '^' -> 30
            '_' -> 31
            '?' -> 127
            else -> null
        } else null
        if (control != null && !meta) {
            return (if (alt) TerminalKeySequences.ESCAPE else byteArrayOf()) + byteArrayOf(control.toByte())
        }
        if (modifiers.isEmpty()) return character.toString().toByteArray()
        val printable = character.code >= 32 && character.code != 127
        if (printable && !ctrl && !meta) {
            val shifted = effectiveCharacter
            return (if (alt) TerminalKeySequences.ESCAPE else byteArrayOf()) + shifted.toString().toByteArray()
        }
        return "\u001b[${character.code};${modifier}u".toByteArray(Charsets.US_ASCII)
    }

    private fun shiftCharacter(value: Char): Char {
        val normal = "`1234567890-=[]\\;',./"
        val shifted = "~!@#$%^&*()_+{}|:\"<>?"
        val index = normal.indexOf(value)
        return if (index >= 0) shifted[index] else value.uppercaseChar()
    }

    private val namedKeys = mapOf(
        "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "home" to KeyEvent.KEYCODE_MOVE_HOME, "end" to KeyEvent.KEYCODE_MOVE_END,
        "pageup" to KeyEvent.KEYCODE_PAGE_UP, "pagedown" to KeyEvent.KEYCODE_PAGE_DOWN,
        "insert" to KeyEvent.KEYCODE_INSERT, "delete" to KeyEvent.KEYCODE_FORWARD_DEL,
    )
}
