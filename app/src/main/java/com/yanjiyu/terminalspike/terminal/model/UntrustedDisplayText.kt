package com.yanjiyu.terminalspike.terminal.model

/**
 * Normalizes untrusted terminal text before it leaves the renderer for trusted application or
 * Android system UI. Visible international text is retained; controls and Unicode format
 * characters (including bidi overrides and isolates) are not.
 */
internal fun sanitizeUntrustedDisplayText(raw: String, maximumLength: Int): String {
    require(maximumLength > 0) { "maximumLength must be positive" }
    val normalized = buildString(raw.length.coerceAtMost(maximumLength)) {
        var pendingSpace = false
        raw.forEach { character ->
            when {
                Character.getType(character) == Character.FORMAT.toInt() -> Unit
                character.isISOControl() && character !in "\t\n\r" -> Unit
                character.isWhitespace() -> pendingSpace = isNotEmpty()
                else -> {
                    if (pendingSpace) append(' ')
                    append(character)
                    pendingSpace = false
                }
            }
        }
    }
    return normalized.take(maximumLength).trimEnd()
}
