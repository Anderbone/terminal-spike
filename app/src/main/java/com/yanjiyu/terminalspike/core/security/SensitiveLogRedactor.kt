package com.yanjiyu.terminalspike.core.security

/** Defense-in-depth for debug-only diagnostics; callers should still avoid supplying secrets. */
object SensitiveLogRedactor {
    private const val MAX_DIAGNOSTIC_CHARS = 2_048

    private val privateKeyBlock = Regex(
        pattern =
            "-----BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-----.*?" +
                "(?:-----END(?: [A-Z0-9]+)? PRIVATE KEY-----|\\z)",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val authorizationHeader = Regex(
        pattern = "(?i)\\bauthorization\\s*[:=]\\s*[^\\r\\n]+",
    )
    private val sensitiveAssignment = Regex(
        pattern = """(?i)\b(password|passphrase|token|secret|authorization|mosh[_-]?key)\s*[:=]\s*(?:\"[^\"]*\"|'[^']*'|[^\s,;]+)""",
    )
    private val connectionUri = Regex("""(?i)\b(?:ssh|mosh|sftp)://[^\s]+""")
    private val userAtHost = Regex(
        """(?i)(?<![\w.])[\w.%+\-]+@(?:\[[0-9a-f:]+]|[a-z0-9._-]+)(?::\d{1,5})?""",
    )
    private val unknownHost = Regex(
        """(?i)\b(UnknownHostException|UnresolvedAddressException)(\s*:\s*)[^\s,;]+""",
    )
    private val ipv4Address = Regex(
        """(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?(?![\d.])""",
    )
    private val bracketedIpv6Address = Regex("""\[[0-9a-fA-F:]+](?::\d{1,5})?""")

    fun redact(input: String): String {
        var value = input.take(MAX_DIAGNOSTIC_CHARS)
        value = privateKeyBlock.replace(value, "[REDACTED_PRIVATE_KEY]")
        value = authorizationHeader.replace(value, "authorization=[REDACTED]")
        value = sensitiveAssignment.replace(value) { match ->
            "${match.groupValues[1].lowercase()}=[REDACTED]"
        }
        value = connectionUri.replace(value, "[REDACTED_CONNECTION_URI]")
        value = userAtHost.replace(value, "[REDACTED_ENDPOINT]")
        value = unknownHost.replace(value) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}[REDACTED_HOST]"
        }
        value = bracketedIpv6Address.replace(value, "[REDACTED_IP]")
        value = ipv4Address.replace(value, "[REDACTED_IP]")
        return value
    }
}
