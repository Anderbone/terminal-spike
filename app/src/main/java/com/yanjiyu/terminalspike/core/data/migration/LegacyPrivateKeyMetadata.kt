package com.yanjiyu.terminalspike.core.data.migration

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.security.MessageDigest
import java.util.Base64

internal data class LegacyPrivateKeyMetadata(
    val algorithm: String,
    val fingerprintSha256: String,
    val publicKey: ByteArray,
)

/** Optional metadata extraction must never decide whether a valid legacy secret is retained. */
internal fun interface LegacyPrivateKeyMetadataDeriver {
    fun derive(privateKey: ByteArray): LegacyPrivateKeyMetadata?
}

internal object JschLegacyPrivateKeyMetadataDeriver : LegacyPrivateKeyMetadataDeriver {
    override fun derive(privateKey: ByteArray): LegacyPrivateKeyMetadata? {
        val workingCopy = privateKey.copyOf()
        val keyPair = try {
            KeyPair.load(JSch(), workingCopy, null)
        } catch (_: Exception) {
            workingCopy.fill(0)
            return null
        }
        return try {
            val publicKey = keyPair.publicKeyBlob?.copyOf()?.takeIf(ByteArray::isNotEmpty)
                ?: return null
            val algorithm = keyPair.keyTypeString
                .takeIf(String::isNotBlank)
                ?.take(MAX_LEGACY_KEY_TYPE_LENGTH)
                ?: return null
            val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(publicKey),
            )
            LegacyPrivateKeyMetadata(
                algorithm = algorithm,
                fingerprintSha256 = fingerprint,
                publicKey = publicKey,
            )
        } finally {
            keyPair.dispose()
            workingCopy.fill(0)
        }
    }

    private const val MAX_LEGACY_KEY_TYPE_LENGTH = 32
}
