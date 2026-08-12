package com.yanjiyu.terminalspike.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.IDN
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Locale
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Whether the device-local endpoint-identity key was reused or newly established. */
internal enum class RecentEndpointIdentityKeyState {
    EXISTING,
    CREATED,
    REPLACED_INVALIDATED,
}

internal data class RecentEndpointHmac(
    val bytes: ByteArray,
    val keyState: RecentEndpointIdentityKeyState,
)

/** Injectable cryptographic seam; JVM tests use an in-memory HMAC key. */
internal fun interface RecentEndpointHmacProvider {
    fun hmacSha256(message: ByteArray): RecentEndpointHmac
}

internal data class RecentEndpointIdentityToken(
    val value: String,
    val keyState: RecentEndpointIdentityKeyState,
) {
    init {
        require(value.length == TOKEN_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Recent endpoint identity must be a lowercase HMAC-SHA-256 token."
        }
    }

    private companion object {
        const val TOKEN_HEX_LENGTH = 64
    }
}

/** Redacted failure used when Android Keystore cannot currently issue an endpoint token. */
internal class RecentEndpointIdentityUnavailableException :
    IllegalStateException("Device-local recent endpoint identity is unavailable.")

/**
 * Privacy boundary for Recent-session endpoint identity.
 *
 * The canonical input is never persisted. The returned token is local, pseudonymous metadata and
 * is intentionally unsuitable for backup export or user-visible diagnostics.
 */
internal class RecentEndpointIdentityProvider(
    private val hmacProvider: RecentEndpointHmacProvider,
) {
    fun create(
        protocol: ConnectionProtocol,
        host: String,
        port: Int,
        username: String,
    ): RecentEndpointIdentityToken {
        val input = RecentEndpointIdentityEncoding.encode(protocol, host, port, username)
        val result = try {
            hmacProvider.hmacSha256(input)
        } finally {
            input.fill(0)
        }
        return try {
            require(result.bytes.size == HMAC_SHA256_BYTES) {
                "Recent endpoint HMAC provider returned an invalid digest length."
            }
            RecentEndpointIdentityToken(
                value = result.bytes.joinToString(separator = "") { byte ->
                    HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f].toString() +
                        HEX_DIGITS[byte.toInt() and 0x0f]
                },
                keyState = result.keyState,
            )
        } finally {
            result.bytes.fill(0)
        }
    }

    private companion object {
        const val HMAC_SHA256_BYTES = 32
        const val HEX_DIGITS = "0123456789abcdef"
    }
}

/** Android Keystore-backed HMAC key. No endpoint input or token is retained by this provider. */
internal class AndroidKeystoreRecentEndpointHmacProvider(
    private val alias: String = DEFAULT_KEY_ALIAS,
) : RecentEndpointHmacProvider {
    private val lock = Any()

    init {
        require(alias.isNotBlank()) { "Recent endpoint HMAC alias must not be blank." }
    }

    override fun hmacSha256(message: ByteArray): RecentEndpointHmac = synchronized(lock) {
        try {
            val keyStore = androidKeyStore()
            val loaded = loadOrCreateKey(keyStore)
            try {
                RecentEndpointHmac(
                    bytes = sign(loaded.key, message),
                    keyState = loaded.state,
                )
            } catch (failure: Exception) {
                if (!failure.containsPermanentInvalidation()) throw failure
                keyStore.deleteEntry(alias)
                val replacement = generateKey()
                RecentEndpointHmac(
                    bytes = sign(replacement, message),
                    keyState = RecentEndpointIdentityKeyState.REPLACED_INVALIDATED,
                )
            }
        } catch (_: RecentEndpointIdentityUnavailableException) {
            throw RecentEndpointIdentityUnavailableException()
        } catch (_: Exception) {
            throw RecentEndpointIdentityUnavailableException()
        }
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private fun loadOrCreateKey(keyStore: KeyStore): LoadedKey {
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) {
            return LoadedKey(existing, RecentEndpointIdentityKeyState.EXISTING)
        }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        return LoadedKey(generateKey(), RecentEndpointIdentityKeyState.CREATED)
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(HMAC_ALGORITHM, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setKeySize(HMAC_KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun sign(key: SecretKey, message: ByteArray): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(key)
            doFinal(message)
        }

    private fun Throwable.containsPermanentInvalidation(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is KeyPermanentlyInvalidatedException) return true
            current = current.cause?.takeUnless { it === current }
        }
        return false
    }

    private data class LoadedKey(
        val key: SecretKey,
        val state: RecentEndpointIdentityKeyState,
    )

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val HMAC_KEY_BITS = 256
        const val DEFAULT_KEY_ALIAS = "com.yanjiyu.terminalspike.recent_endpoint_hmac_v1"
    }
}

/** Exact domain-separated binary encoding fixed by Plan 004. */
private object RecentEndpointIdentityEncoding {
    private val DOMAIN = "terminal-spike:recent-endpoint:v1".toByteArray(StandardCharsets.UTF_8)
    private val SCOPED_ZONE = Regex("[A-Za-z0-9_.-]{1,32}")

    fun encode(
        protocol: ConnectionProtocol,
        host: String,
        port: Int,
        username: String,
    ): ByteArray {
        require(port in 1..65_535) { "Recent endpoint port is outside the valid range." }
        require(username.toByteArray(StandardCharsets.UTF_8).toString(StandardCharsets.UTF_8) == username) {
            "Recent endpoint username is not valid UTF-8."
        }
        require(username.none(Char::isISOControl)) {
            "Recent endpoint username contains unsupported control characters."
        }
        val protocolBytes = protocol.wireCode.toByteArray(StandardCharsets.UTF_8)
        val hostBytes = canonicalHostPayload(host)
        val usernameBytes = username.toByteArray(StandardCharsets.UTF_8)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(DOMAIN)
                output.writeLengthFramed(protocolBytes)
                output.writeLengthFramed(hostBytes)
                output.writeInt(port)
                output.writeLengthFramed(usernameBytes)
            }
            bytes.toByteArray()
        }
    }

    private fun canonicalHostPayload(input: String): ByteArray {
        require(input.isNotEmpty() && input == input.trim()) {
            "Recent endpoint host must not be blank or have surrounding whitespace."
        }
        require(input.none { it.isWhitespace() || it.isISOControl() }) {
            "Recent endpoint host contains unsupported characters."
        }
        val bracketed = input.startsWith('[') || input.endsWith(']')
        val host = if (bracketed) {
            require(input.startsWith('[') && input.endsWith(']') && input.length > 2) {
                "Recent endpoint host has unmatched IPv6 brackets."
            }
            input.substring(1, input.lastIndex)
        } else {
            input
        }
        require('[' !in host && ']' !in host) { "Recent endpoint host contains invalid brackets." }

        if (':' in host) {
            val zoneSeparator = host.indexOf('%')
            require(zoneSeparator == host.lastIndexOf('%')) {
                "Recent endpoint IPv6 scope is ambiguous."
            }
            val addressText = if (zoneSeparator >= 0) host.substring(0, zoneSeparator) else host
            val address = parseIpv6(addressText)
            if (zoneSeparator < 0) return byteArrayOf(IPV6_TAG) + address

            val zone = host.substring(zoneSeparator + 1)
            require(SCOPED_ZONE.matches(zone)) { "Recent endpoint IPv6 scope is invalid." }
            val zoneBytes = zone.toByteArray(StandardCharsets.US_ASCII)
            return ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeByte(SCOPED_IPV6_TAG.toInt())
                    output.write(address)
                    output.writeInt(zoneBytes.size)
                    output.write(zoneBytes)
                }
                bytes.toByteArray()
            }
        }

        require(!bracketed) { "Only IPv6 literals may use brackets." }
        if (host.all { it.isDigit() || it == '.' }) {
            return byteArrayOf(IPV4_TAG) + parseIpv4(host)
        }
        require('%' !in host) { "Recent endpoint scope syntax is valid only for IPv6 literals." }
        val withoutTrailingDot = host.removeSuffix(".")
        require(withoutTrailingDot.isNotEmpty() && !withoutTrailingDot.endsWith('.')) {
            "Recent endpoint DNS name has an invalid root suffix."
        }
        val ascii = try {
            IDN.toASCII(withoutTrailingDot, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Recent endpoint DNS name is invalid.")
        }
        require(ascii.length in 1..253 && ascii.split('.').all { label ->
            label.length in 1..63 && label.first() != '-' && label.last() != '-'
        }) { "Recent endpoint DNS name is invalid." }
        return byteArrayOf(DNS_TAG) + ascii.toByteArray(StandardCharsets.US_ASCII)
    }

    private fun parseIpv4(value: String): ByteArray {
        val octets = value.split('.')
        require(octets.size == 4) { "Recent endpoint IPv4 literal is invalid." }
        return ByteArray(4) { index ->
            val octet = octets[index]
            require(
                octet.isNotEmpty() &&
                    octet.length <= 3 &&
                    (octet.length == 1 || octet.first() != '0'),
            ) { "Recent endpoint IPv4 literal is ambiguous." }
            val parsed = octet.toIntOrNull()
            require(parsed != null && parsed in 0..255) {
                "Recent endpoint IPv4 literal is invalid."
            }
            parsed.toByte()
        }
    }

    private fun parseIpv6(value: String): ByteArray {
        require(value.isNotEmpty() && ':' in value) { "Recent endpoint IPv6 literal is invalid." }
        val compression = value.indexOf("::")
        require(compression < 0 || value.indexOf("::", compression + 2) < 0) {
            "Recent endpoint IPv6 literal has multiple compression markers."
        }
        val leftText = if (compression >= 0) value.substring(0, compression) else value
        val rightText = if (compression >= 0) value.substring(compression + 2) else ""
        val left = splitIpv6Side(leftText)
        val right = splitIpv6Side(rightText)
        val textualParts = left + right
        require(textualParts.withIndex().all { (index, part) ->
            '.' !in part || index == textualParts.lastIndex
        }) { "Embedded IPv4 is valid only at the end of an IPv6 literal." }
        val leftGroups = parseIpv6Groups(left)
        val rightGroups = parseIpv6Groups(right)
        val presentGroupCount = leftGroups.size + rightGroups.size
        val zeroGroups = if (compression >= 0) {
            require(presentGroupCount < IPV6_GROUP_COUNT) {
                "Compressed IPv6 literal must omit at least one group."
            }
            IPV6_GROUP_COUNT - presentGroupCount
        } else {
            require(presentGroupCount == IPV6_GROUP_COUNT) {
                "Uncompressed IPv6 literal must contain eight groups."
            }
            0
        }
        val groups = leftGroups + List(zeroGroups) { 0 } + rightGroups
        require(groups.size == IPV6_GROUP_COUNT) { "Recent endpoint IPv6 literal is invalid." }
        return ByteArray(IPV6_BYTE_COUNT).also { output ->
            groups.forEachIndexed { index, group ->
                output[index * 2] = (group ushr 8).toByte()
                output[index * 2 + 1] = group.toByte()
            }
        }
    }

    private fun splitIpv6Side(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        val parts = value.split(':')
        require(parts.none { it.isEmpty() }) { "Recent endpoint IPv6 literal is invalid." }
        return parts
    }

    private fun parseIpv6Groups(parts: List<String>): List<Int> = buildList {
        parts.forEach { part ->
            if ('.' in part) {
                val ipv4 = parseIpv4(part)
                add(((ipv4[0].toInt() and 0xff) shl 8) or (ipv4[1].toInt() and 0xff))
                add(((ipv4[2].toInt() and 0xff) shl 8) or (ipv4[3].toInt() and 0xff))
            } else {
                require(part.length in 1..4 && part.all { character -> character.isHexDigit() }) {
                    "Recent endpoint IPv6 group is invalid."
                }
                add(part.toInt(16))
            }
        }
    }

    private fun DataOutputStream.writeLengthFramed(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private const val IPV4_TAG: Byte = 0x01
    private const val IPV6_TAG: Byte = 0x02
    private const val DNS_TAG: Byte = 0x03
    private const val SCOPED_IPV6_TAG: Byte = 0x04
    private const val IPV6_GROUP_COUNT = 8
    private const val IPV6_BYTE_COUNT = 16
}
