package com.yanjiyu.terminalspike.core.data.migration

import com.yanjiyu.terminalspike.core.model.ModelLimits
import com.yanjiyu.terminalspike.core.model.requireHost
import com.yanjiyu.terminalspike.core.model.requireIdentifier
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

internal data class LegacyKnownHostRecord(
    val id: String,
    val canonicalHost: String,
    val port: Int,
    val algorithm: String,
    val publicKey: ByteArray,
    val fingerprintSha256: String,
)

internal data class LegacyKnownHostsParseResult(
    val records: List<LegacyKnownHostRecord>,
    val warnings: List<LegacyKnownHostsWarning>,
)

internal data class LegacyKnownHostsWarning(
    val lineNumber: Int,
    val code: LegacyKnownHostsWarningCode,
)

internal enum class LegacyKnownHostsWarningCode {
    LINE_TOO_LONG,
    MALFORMED_LINE,
    UNSUPPORTED_HOST_PATTERN,
    INVALID_PORT,
    INVALID_HOST,
    INVALID_ALGORITHM,
    INVALID_KEY,
}

/** Parses the app's OpenSSH-shaped legacy file without mutating or echoing its contents. */
internal object LegacyKnownHostsParser {
    private const val DEFAULT_PORT = 22
    private const val MAX_SOURCE_CHARS = 1024 * 1024
    private const val MAX_LINES = 10_000
    private const val MAX_LINE_CHARS = 16 * 1024
    private const val MAX_KEY_BYTES = 64 * 1024
    private val whitespace = Regex("\\s+")
    private val bracketedHost = Regex("^\\[([^]]+)]:(\\d{1,5})$")

    fun parse(source: String): LegacyKnownHostsParseResult {
        require(source.length <= MAX_SOURCE_CHARS) { "Legacy known-host source is too large." }
        val records = LinkedHashMap<Triple<String, Int, String>, LegacyKnownHostRecord>()
        val warnings = mutableListOf<LegacyKnownHostsWarning>()

        source.lineSequence().take(MAX_LINES + 1).forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            if (lineNumber > MAX_LINES) {
                throw IllegalArgumentException("Legacy known-host source has too many lines.")
            }
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            if (line.length > MAX_LINE_CHARS) {
                warnings += LegacyKnownHostsWarning(lineNumber, LegacyKnownHostsWarningCode.LINE_TOO_LONG)
                return@forEachIndexed
            }
            val parts = line.split(whitespace, limit = 4)
            if (parts.size != 3) {
                warnings += LegacyKnownHostsWarning(lineNumber, LegacyKnownHostsWarningCode.MALFORMED_LINE)
                return@forEachIndexed
            }
            val algorithm = parts[1]
            val algorithmIsValid = runCatching {
                requireIdentifier(
                    algorithm,
                    "legacy host-key algorithm",
                    ModelLimits.MAX_ALGORITHM_LENGTH,
                )
            }.isSuccess
            if (!algorithmIsValid) {
                warnings += LegacyKnownHostsWarning(
                    lineNumber,
                    LegacyKnownHostsWarningCode.INVALID_ALGORITHM,
                )
                return@forEachIndexed
            }
            val key = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull()
            if (key == null || key.isEmpty() || key.size > MAX_KEY_BYTES) {
                key?.fill(0)
                warnings += LegacyKnownHostsWarning(lineNumber, LegacyKnownHostsWarningCode.INVALID_KEY)
                return@forEachIndexed
            }
            val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(key),
            )

            parts[0].split(',').forEach { candidate ->
                when (val endpoint = parseEndpoint(candidate)) {
                    is ParsedEndpoint.Valid -> {
                        val record = LegacyKnownHostRecord(
                            id = LegacyIds.knownHost(endpoint.host, endpoint.port, algorithm),
                            canonicalHost = endpoint.host,
                            port = endpoint.port,
                            algorithm = algorithm,
                            publicKey = key.copyOf(),
                            fingerprintSha256 = fingerprint,
                        )
                        records[Triple(endpoint.host, endpoint.port, algorithm)] = record
                    }
                    ParsedEndpoint.InvalidPort -> warnings += LegacyKnownHostsWarning(
                        lineNumber,
                        LegacyKnownHostsWarningCode.INVALID_PORT,
                    )
                    ParsedEndpoint.InvalidHost -> warnings += LegacyKnownHostsWarning(
                        lineNumber,
                        LegacyKnownHostsWarningCode.INVALID_HOST,
                    )
                    ParsedEndpoint.Unsupported -> warnings += LegacyKnownHostsWarning(
                        lineNumber,
                        LegacyKnownHostsWarningCode.UNSUPPORTED_HOST_PATTERN,
                    )
                }
            }
            key.fill(0)
        }

        return LegacyKnownHostsParseResult(
            records = records.values.sortedWith(
                compareBy<LegacyKnownHostRecord> { it.canonicalHost }
                    .thenBy { it.port }
                    .thenBy { it.algorithm },
            ),
            warnings = warnings.toList(),
        )
    }

    private fun parseEndpoint(candidate: String): ParsedEndpoint {
        if (
            candidate.isBlank() ||
            candidate.startsWith('|') ||
            '*' in candidate ||
            '?' in candidate ||
            candidate.any(Char::isWhitespace)
        ) {
            return ParsedEndpoint.Unsupported
        }
        val bracketed = bracketedHost.matchEntire(candidate)
        val rawHost: String
        val port: Int
        if (bracketed != null) {
            rawHost = bracketed.groupValues[1]
            port = bracketed.groupValues[2].toIntOrNull() ?: return ParsedEndpoint.InvalidPort
            if (port !in 1..65_535) return ParsedEndpoint.InvalidPort
        } else {
            if (candidate.startsWith('[') || candidate.endsWith(']')) return ParsedEndpoint.Unsupported
            rawHost = candidate
            port = DEFAULT_PORT
        }
        if (rawHost.isBlank() || rawHost.any(Char::isWhitespace)) return ParsedEndpoint.Unsupported
        val canonicalHost = rawHost.lowercase(Locale.ROOT)
        if (
            runCatching {
                requireHost(canonicalHost, "legacy known host", canonical = true)
            }.isFailure
        ) {
            return ParsedEndpoint.InvalidHost
        }
        return ParsedEndpoint.Valid(canonicalHost, port)
    }

    private sealed interface ParsedEndpoint {
        data class Valid(val host: String, val port: Int) : ParsedEndpoint
        data object InvalidPort : ParsedEndpoint
        data object InvalidHost : ParsedEndpoint
        data object Unsupported : ParsedEndpoint
    }
}
