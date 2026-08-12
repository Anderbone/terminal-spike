package com.yanjiyu.terminalspike.core.data.migration

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Deterministic IDs make legacy imports idempotent across crashes and restarts. */
internal object LegacyIds {
    internal val namespace: UUID = UUID.fromString("4b15d1d9-31d7-4d0e-9ea9-bd041f34fb72")

    fun hostProfile(legacyId: Long): String = legacyLong("profile", legacyId)

    fun snippet(legacyId: Long): String = legacyLong("snippet", legacyId)

    fun keyIdentity(legacyId: Long): String = legacyLong("identity", legacyId)

    fun passwordCredential(legacyProfileId: Long): String =
        legacyLong("credential/password", legacyProfileId)

    fun passwordSecret(legacyProfileId: Long): String =
        legacyLong("secret/password", legacyProfileId)

    fun privateKeySecret(legacyIdentityId: Long): String =
        legacyLong("secret/private-key", legacyIdentityId)

    fun knownHost(host: String, port: Int, algorithm: String): String {
        require(host.isNotBlank() && host.none(Char::isWhitespace)) { "Invalid legacy known host." }
        require(port in 1..65_535) { "Invalid legacy known-host port." }
        require(algorithm.isNotBlank() && algorithm.none(Char::isWhitespace)) {
            "Invalid legacy host-key algorithm."
        }
        val canonicalHost = host.lowercase(Locale.ROOT)
        return uuidV5("known-host/$canonicalHost/$port/$algorithm").toString()
    }

    val defaultTerminalProfile: String = uuidV5("terminal-profile/default").toString()

    val defaultKeyboardProfile: String = uuidV5("keyboard-profile/default").toString()

    private fun legacyLong(type: String, legacyId: Long): String {
        require(legacyId > 0) { "Legacy IDs must be positive." }
        return uuidV5("$type/$legacyId").toString()
    }

    private fun uuidV5(name: String): UUID {
        val namespaceBytes = ByteBuffer.allocate(16)
            .putLong(namespace.mostSignificantBits)
            .putLong(namespace.leastSignificantBits)
            .array()
        val hash = MessageDigest.getInstance("SHA-1").run {
            update(namespaceBytes)
            digest(name.toByteArray(StandardCharsets.UTF_8))
        }
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        return ByteBuffer.wrap(hash, 0, 16).let { bytes ->
            UUID(bytes.long, bytes.long)
        }
    }
}
