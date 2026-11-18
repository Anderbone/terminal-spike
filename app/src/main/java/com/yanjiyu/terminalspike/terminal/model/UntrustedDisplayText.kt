package com.yanjiyu.terminalspike.terminal.model

/**
 * Normalizes untrusted terminal text before it leaves the renderer for trusted application or
 * Android system UI. Visible international text is retained; controls and Unicode format
 * characters (including bidi overrides and isolates) are not. The UTF-16 length limit retains
 * complete code points; malformed surrogate code units become replacement characters.
 */
internal fun sanitizeUntrustedDisplayText(raw: String, maximumLength: Int): String {
    require(maximumLength > 0) { "maximumLength must be positive" }
    return buildString(raw.length.coerceAtMost(maximumLength)) {
        var pendingSpace = false
        var index = 0
        while (index < raw.length && length < maximumLength) {
            val rawCodePoint = raw.codePointAt(index)
            index += Character.charCount(rawCodePoint)
            val codePoint = if (rawCodePoint in 0xD800..0xDFFF) 0xFFFD else rawCodePoint
            when {
                Character.getType(codePoint) == Character.FORMAT.toInt() -> Unit
                Character.isISOControl(codePoint) && codePoint != '\t'.code &&
                    codePoint != '\n'.code && codePoint != '\r'.code -> Unit
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) ->
                    pendingSpace = isNotEmpty()
                else -> {
                    val separatorLength = if (pendingSpace) 1 else 0
                    if (Character.charCount(codePoint) > maximumLength - length - separatorLength) break
                    if (pendingSpace) append(' ')
                    appendCodePoint(codePoint)
                    pendingSpace = false
                }
            }
        }
    }
}

/** Rejects unsafe Unicode in OSC payloads and link targets without rewriting their content. */
internal fun isSafeTerminalControlText(raw: String): Boolean {
    var index = 0
    while (index < raw.length) {
        val codePoint = raw.codePointAt(index)
        if (
            Character.isISOControl(codePoint) ||
            Character.getType(codePoint) == Character.FORMAT.toInt() ||
            codePoint in 0xD800..0xDFFF || codePoint == 0xFFFD
        ) {
            return false
        }
        index += Character.charCount(codePoint)
    }
    return true
}
