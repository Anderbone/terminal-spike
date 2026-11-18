package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.terminal.model.sanitizeUntrustedDisplayText
import java.util.Locale

internal fun normalizeSessionLabel(value: String, protocol: ConnectionProtocol): String {
    val normalized = sanitizeUntrustedDisplayText(
        value.filterNot(Char::isISOControl), MAX_SESSION_LABEL_LENGTH,
    )
    if (normalized.isNotEmpty()) return normalized
    return if (protocol == ConnectionProtocol.MOSH) "Mosh session" else "SSH session"
}

internal fun sanitizeTerminalTitle(
    raw: String?,
    seed: RemoteSessionConnectionSeed,
): String? {
    val normalized = raw?.let { sanitizeUntrustedDisplayText(it, MAX_TERMINAL_TITLE_LENGTH) }
        ?.takeIf(String::isNotEmpty)
        ?: return null
    val folded = normalized.lowercase(Locale.ROOT)
    val sensitiveValues = listOf(seed.host, seed.username)
        .map(String::trim)
        .filter { it.length >= MIN_REDACTED_FRAGMENT_LENGTH }
    return normalized.takeUnless { sensitiveValues.any { folded.contains(it.lowercase(Locale.ROOT)) } }
}

private const val MAX_SESSION_LABEL_LENGTH = 96
private const val MAX_TERMINAL_TITLE_LENGTH = 128
private const val MIN_REDACTED_FRAGMENT_LENGTH = 2
