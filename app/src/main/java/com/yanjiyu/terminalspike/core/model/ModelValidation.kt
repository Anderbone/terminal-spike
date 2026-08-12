package com.yanjiyu.terminalspike.core.model

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import java.util.UUID

/** Shared allocation and persistence bounds for non-secret product models. */
object ModelLimits {
    const val MAX_DISPLAY_NAME_LENGTH = 96
    const val MAX_HOST_LENGTH = 253
    const val MAX_USERNAME_LENGTH = 64
    const val MAX_GROUP_LENGTH = 64
    const val MAX_TAG_LENGTH = 48
    const val MAX_COMMAND_LENGTH = 4_096
    const val MAX_MOSH_SERVER_COMMAND_LENGTH = 512
    const val MAX_MOSH_LOCALE_LENGTH = 64
    const val MAX_ALGORITHM_LENGTH = 64
    const val MAX_FINGERPRINT_LENGTH = 256
    const val MAX_PUBLIC_KEY_LENGTH = 65_536
    const val MAX_COMMENT_LENGTH = 256
    const val MAX_PROFILE_REFERENCE_LENGTH = 128
    const val MAX_TERM_LENGTH = 64
    const val MAX_TERMINAL_TITLE_LENGTH = 256
    const val MAX_TMUX_PREFIX_LENGTH = 16

    const val MIN_PORT = 1
    const val MAX_PORT = 65_535
    const val MAX_MOSH_PORT_RANGE_SIZE = 10_000

    const val MIN_KEEPALIVE_SECONDS = 5
    const val MAX_KEEPALIVE_SECONDS = 3_600

    const val MIN_FONT_SIZE_SP = 8f
    const val MAX_FONT_SIZE_SP = 72f
    const val MIN_LINE_HEIGHT_MULTIPLIER = 0.8f
    const val MAX_LINE_HEIGHT_MULTIPLIER = 3f
    const val MIN_LETTER_SPACING_EM = -0.5f
    const val MAX_LETTER_SPACING_EM = 2f
    const val MAX_SCROLLBACK_LINES = 200_000

    // 9999-12-31T23:59:59.999Z. Keeping timestamps within this range avoids
    // overflow and unsupported dates in backup and database adapters.
    const val MAX_EPOCH_MILLIS = 253_402_300_799_999L
}

internal fun requireCanonicalUuid(value: String, fieldName: String) {
    require(value.length == 36 && runCatching { UUID.fromString(value).toString() }.getOrNull() == value) {
        "$fieldName must be a canonical lowercase UUID."
    }
}

internal fun requirePort(value: Int, fieldName: String) {
    require(value in ModelLimits.MIN_PORT..ModelLimits.MAX_PORT) {
        "$fieldName must be between ${ModelLimits.MIN_PORT} and ${ModelLimits.MAX_PORT}."
    }
}

internal fun requireEpochMillis(value: Long, fieldName: String) {
    require(value in 0..ModelLimits.MAX_EPOCH_MILLIS) { "$fieldName is outside the supported range." }
}

internal fun requireTimestampOrder(earlier: Long, later: Long, laterFieldName: String) {
    require(later >= earlier) { "$laterFieldName must not precede the related earlier timestamp." }
}

internal fun requirePlainText(
    value: String,
    fieldName: String,
    maximumLength: Int,
    allowEmpty: Boolean = false,
) {
    require(value.length <= maximumLength && (allowEmpty || value.isNotEmpty())) {
        "$fieldName must contain ${if (allowEmpty) "at most" else "between 1 and"} $maximumLength characters."
    }
    require(value == value.trim()) { "$fieldName must not have surrounding whitespace." }
    require(value.none(Char::isISOControl) && !value.hasUnpairedSurrogate()) {
        "$fieldName contains unsupported characters."
    }
}

internal fun requireOptionalPlainText(value: String?, fieldName: String, maximumLength: Int) {
    value?.let { requirePlainText(it, fieldName, maximumLength) }
}

internal fun requireHost(value: String, fieldName: String, canonical: Boolean = false) {
    requirePlainText(value, fieldName, ModelLimits.MAX_HOST_LENGTH)
    require(value.none(Char::isWhitespace)) { "$fieldName must not contain whitespace." }
    require(isValidHost(value)) { "$fieldName must be a valid hostname, IPv4 address, or IPv6 address." }
    if (canonical) {
        require(value == value.lowercase(Locale.ROOT)) { "$fieldName must be canonical lowercase text." }
    }
}

private fun isValidHost(value: String): Boolean = when {
    ':' in value -> isValidIpv6Literal(value)
    value.all { it.isDigit() || it == '.' } -> isValidIpv4Literal(value)
    else -> isValidDnsName(value)
}

private fun isValidIpv4Literal(value: String): Boolean {
    val octets = value.split('.')
    return octets.size == 4 && octets.all { octet ->
        octet.isNotEmpty() &&
            octet.length <= 3 &&
            (octet.length == 1 || octet.first() != '0') &&
            octet.toIntOrNull() in 0..255
    }
}

private fun isValidIpv6Literal(value: String): Boolean {
    if ('%' in value || value.startsWith('[') || value.endsWith(']')) return false
    return runCatching { InetAddress.getByName(value) }
        .getOrNull() is Inet6Address
}

private fun isValidDnsName(value: String): Boolean {
    val withoutRootDot = value.removeSuffix(".")
    if (withoutRootDot.isEmpty()) return false
    val ascii = runCatching { IDN.toASCII(withoutRootDot, IDN.USE_STD3_ASCII_RULES) }.getOrNull()
        ?: return false
    if (ascii.length > ModelLimits.MAX_HOST_LENGTH) return false
    return ascii.split('.').all { label ->
        label.length in 1..63 && label.first() != '-' && label.last() != '-'
    }
}

internal fun requireUsername(value: String) {
    requirePlainText(value, "username", ModelLimits.MAX_USERNAME_LENGTH)
    require(value.none(Char::isWhitespace)) { "username must not contain whitespace." }
}

internal fun requireCommand(value: String, fieldName: String, maximumLength: Int) {
    require(value.isNotBlank() && value.length <= maximumLength && !value.hasUnpairedSurrogate()) {
        "$fieldName must contain between 1 and $maximumLength supported characters."
    }
    require(value.none { character -> character.isISOControl() && character !in "\r\n\t" }) {
        "$fieldName contains unsupported control characters."
    }
}

internal fun requireOptionalCommand(value: String?, fieldName: String, maximumLength: Int) {
    value?.let { requireCommand(it, fieldName, maximumLength) }
}

internal fun requireMoshLocale(value: String) {
    require(value.length in "C.UTF-8".length..ModelLimits.MAX_MOSH_LOCALE_LENGTH) {
        "Mosh locale is outside the supported length range."
    }
    require(value.first().isLetterOrDigit() && value.endsWith(".UTF-8")) {
        "Mosh locale must be an ASCII locale token ending in .UTF-8."
    }
    require(value.all { character ->
        character.code in 0x21..0x7e &&
            (character.isLetterOrDigit() || character == '_' || character == '.' ||
                character == '@' || character == '-')
    }) { "Mosh locale contains unsupported characters." }
}

internal fun requireIdentifier(value: String, fieldName: String, maximumLength: Int) {
    requirePlainText(value, fieldName, maximumLength)
    require(value.all { it.isLetterOrDigit() || it in "._:+-@" }) {
        "$fieldName contains unsupported identifier characters."
    }
}

internal fun requireTmuxPrefix(value: String) {
    requirePlainText(value, "tmux prefix", ModelLimits.MAX_TMUX_PREFIX_LENGTH)
    val isSingleVisibleKey = value.length == 1 && !value.single().isWhitespace()
    val chordParts = value.split('-', limit = 2)
    val isSupportedChord = chordParts.size == 2 &&
        chordParts.first() in setOf("C", "M") &&
        (chordParts.last().length == 1 || chordParts.last() in setOf("Space", "Tab", "Enter", "Escape"))
    require(isSingleVisibleKey || isSupportedChord) { "tmux prefix must identify one terminal key chord." }
}

internal fun String.hasUnpairedSurrogate(): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            character.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return true
                index += 2
            }
            character.isLowSurrogate() -> return true
            else -> index += 1
        }
    }
    return false
}
