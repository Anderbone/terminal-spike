package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.terminal.view.encodeTerminalChord

/** A one-shot modifier belongs to the first key, not an entire IME text batch. */
internal fun applyPendingInputModifiers(
    bytes: ByteArray,
    control: Boolean,
    alt: Boolean,
    shift: Boolean,
): ByteArray {
    if (bytes.isEmpty()) return bytes.copyOf()
    val first = bytes[0].toInt() and 0xff
    // Leave multi-byte characters and already encoded terminal sequences intact.
    if (first !in 0x20..0x7e) return bytes.copyOf()
    val character = first.toChar().let { if (shift) it.uppercaseChar() else it }
    val key = if (control) {
        encodeTerminalChord(if (character == ' ') "C-Space" else "C-$character")
            ?: byteArrayOf(character.code.toByte())
    } else {
        byteArrayOf(character.code.toByte())
    }
    val prefix = if (alt) byteArrayOf(0x1b) else byteArrayOf()
    return prefix + key + bytes.copyOfRange(1, bytes.size)
}
