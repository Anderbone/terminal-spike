package com.yanjiyu.terminalspike.terminal

/** Reject the whole paste; rewriting an ending marker would silently change user text. */
internal fun String.containsBracketedPasteEnd(): Boolean =
    contains("\u001b[201~") || contains("\u009b201~")

internal fun encodeTerminalPaste(
    text: String,
    bracketedPaste: Boolean,
    appendEnter: Boolean = false,
): ByteArray {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val paste = if (bracketedPaste) {
        BRACKETED_PASTE_START + bytes + BRACKETED_PASTE_END
    } else {
        bytes
    }
    return if (appendEnter) paste + PASTE_ENTER else paste
}

private val BRACKETED_PASTE_START = byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x30, 0x7e)
private val BRACKETED_PASTE_END = byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x31, 0x7e)
private val PASTE_ENTER = byteArrayOf(0x0d)
