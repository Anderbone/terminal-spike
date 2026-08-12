package com.yanjiyu.terminalspike.core.security.credential

import java.security.GeneralSecurityException
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM implementation with immutable, versioned associated data. */
class AesGcmCredentialStore(
    private val records: CredentialCiphertextStore,
    private val keys: CredentialKeyProvider,
) : CredentialStore, CredentialRecordDecryptor {
    override suspend fun encryptAndWipe(
        reference: CredentialSecretReference,
        secret: ByteArray,
    ): EncryptedCredentialRecord {
        var associatedData: ByteArray? = null
        var nonce: ByteArray? = null
        var ciphertext: ByteArray? = null
        try {
            validatePlaintextSize(reference, secret.size)
            associatedData = CredentialAssociatedData.encode(reference)
            val key = encryptionKey(reference.secretId)
            val cipher = createCipher(reference.secretId)
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key)
                nonce = cipher.iv?.copyOf()
                    ?: throw CredentialStoreException.KeyUnavailable(reference.secretId)
                if (nonce.size != NONCE_BYTES) {
                    throw CredentialStoreException.KeyUnavailable(reference.secretId)
                }
                cipher.updateAAD(associatedData)
                ciphertext = cipher.doFinal(secret)
            } catch (error: CredentialStoreException) {
                throw error
            } catch (error: InvalidKeyException) {
                throw CredentialStoreException.KeyUnavailable(reference.secretId, error)
            } catch (error: GeneralSecurityException) {
                throw CredentialStoreException.KeyUnavailable(reference.secretId, error)
            } catch (error: ProviderException) {
                throw CredentialStoreException.KeyUnavailable(reference.secretId, error)
            }
            validateCiphertextSize(reference, ciphertext.size)
            return EncryptedCredentialRecord(
                secretId = reference.secretId.value,
                kindCode = reference.kind.wireCode,
                envelopeVersion = ENVELOPE_VERSION,
                keyVersion = KEY_VERSION,
                nonce = nonce,
                ciphertext = ciphertext,
            )
        } finally {
            secret.fill(0)
            associatedData?.fill(0)
            nonce?.fill(0)
            ciphertext?.fill(0)
        }
    }

    override suspend fun saveAndWipe(reference: CredentialSecretReference, secret: ByteArray) {
        records.write(encryptAndWipe(reference, secret))
    }

    override suspend fun <T> withSecret(
        reference: CredentialSecretReference,
        use: suspend (ByteArray) -> T,
    ): T {
        val record = records.read(reference.secretId)
            ?: throw CredentialStoreException.Missing(reference.secretId)
        return withEncryptedRecord(reference, record, use)
    }

    override suspend fun <T> withEncryptedRecord(
        reference: CredentialSecretReference,
        record: EncryptedCredentialRecord,
        use: suspend (ByteArray) -> T,
    ): T {
        var nonce: ByteArray? = null
        var ciphertext: ByteArray? = null
        var associatedData: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            validateRecord(reference, record)
            validateEnvelopeSizes(reference, record.nonceSize, record.ciphertextSize)
            nonce = record.copyNonce()
            ciphertext = record.copyCiphertext()
            associatedData = CredentialAssociatedData.encode(reference)
            val key = decryptionKey(reference.secretId, record.keyVersion)
            val cipher = createCipher(reference.secretId)
            plaintext = try {
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(TAG_BITS, nonce),
                )
                cipher.updateAAD(associatedData)
                cipher.doFinal(ciphertext)
            } catch (error: AEADBadTagException) {
                throw CredentialStoreException.Tampered(reference.secretId)
            } catch (error: BadPaddingException) {
                throw CredentialStoreException.Tampered(reference.secretId)
            } catch (error: IllegalBlockSizeException) {
                throw CredentialStoreException.Tampered(reference.secretId)
            } catch (error: InvalidAlgorithmParameterException) {
                throw malformed(reference, MalformedCredentialReason.NONCE)
            } catch (error: InvalidKeyException) {
                throw CredentialStoreException.KeyUnavailable(reference.secretId, error)
            } catch (error: GeneralSecurityException) {
                throw CredentialStoreException.KeyUnavailable(reference.secretId, error)
            } catch (error: ProviderException) {
                throw CredentialStoreException.KeyUnavailable(reference.secretId, error)
            }
            validatePlaintextSize(reference, plaintext.size)
            return use(plaintext)
        } finally {
            plaintext?.fill(0)
            associatedData?.fill(0)
            nonce?.fill(0)
            ciphertext?.fill(0)
        }
    }

    override suspend fun delete(secretId: SecretId): Boolean = records.delete(secretId)

    private fun validateRecord(
        reference: CredentialSecretReference,
        record: EncryptedCredentialRecord,
    ) {
        if (record.secretId != reference.secretId.value) {
            throw malformed(reference, MalformedCredentialReason.RECORD_ID)
        }
        val recordKind = CredentialSecretKind.fromWireCodeOrNull(record.kindCode)
            ?: throw malformed(reference, MalformedCredentialReason.KIND)
        if (recordKind != reference.kind) {
            throw malformed(reference, MalformedCredentialReason.KIND)
        }
        if (record.envelopeVersion != ENVELOPE_VERSION) {
            throw malformed(reference, MalformedCredentialReason.ENVELOPE_VERSION)
        }
        if (record.keyVersion != KEY_VERSION) {
            throw malformed(reference, MalformedCredentialReason.KEY_VERSION)
        }
    }

    private fun validateEnvelopeSizes(
        reference: CredentialSecretReference,
        nonceSize: Int,
        ciphertextSize: Int,
    ) {
        if (nonceSize != NONCE_BYTES) {
            throw malformed(reference, MalformedCredentialReason.NONCE)
        }
        validateCiphertextSize(reference, ciphertextSize)
    }

    private fun validatePlaintextSize(reference: CredentialSecretReference, size: Int) {
        if (size !in 1..reference.kind.maximumPlaintextBytes) {
            throw malformed(reference, MalformedCredentialReason.PLAINTEXT_SIZE)
        }
    }

    private fun validateCiphertextSize(reference: CredentialSecretReference, size: Int) {
        val maximum = reference.kind.maximumPlaintextBytes + TAG_BYTES
        if (size !in (TAG_BYTES + 1)..maximum) {
            throw malformed(reference, MalformedCredentialReason.CIPHERTEXT_SIZE)
        }
    }

    private fun encryptionKey(secretId: SecretId): SecretKey = try {
        validateAes256Key(secretId, keys.getOrCreateEncryptionKey(KEY_VERSION))
    } catch (error: CredentialStoreException) {
        throw error
    } catch (error: Exception) {
        throw CredentialStoreException.KeyUnavailable(secretId, error)
    }

    private fun decryptionKey(secretId: SecretId, keyVersion: Int): SecretKey {
        val key = try {
            keys.getExistingDecryptionKey(keyVersion)
        } catch (error: Exception) {
            throw CredentialStoreException.KeyUnavailable(secretId, error)
        } ?: throw CredentialStoreException.KeyUnavailable(secretId)
        return validateAes256Key(secretId, key)
    }

    private fun validateAes256Key(secretId: SecretId, key: SecretKey): SecretKey {
        if (!key.algorithm.equals(AES, ignoreCase = true)) {
            throw CredentialStoreException.KeyUnavailable(secretId)
        }
        var encoded: ByteArray? = null
        try {
            encoded = key.encoded
            if (encoded != null && encoded.size != AES_256_BYTES) {
                throw CredentialStoreException.KeyUnavailable(secretId)
            }
        } catch (error: CredentialStoreException) {
            throw error
        } catch (error: Exception) {
            throw CredentialStoreException.KeyUnavailable(secretId, error)
        } finally {
            encoded?.fill(0)
        }
        return key
    }

    private fun createCipher(secretId: SecretId): Cipher = try {
        Cipher.getInstance(TRANSFORMATION)
    } catch (error: GeneralSecurityException) {
        throw CredentialStoreException.KeyUnavailable(secretId, error)
    }

    private fun malformed(
        reference: CredentialSecretReference,
        reason: MalformedCredentialReason,
    ) = CredentialStoreException.Malformed(reference.secretId, reason)

    companion object {
        const val ENVELOPE_VERSION = 2
        const val KEY_VERSION = 2
        const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val AES_256_BYTES = 256 / 8
        private const val AES = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Canonical, delimiter-safe AAD whose fields are all immutable UUID or protocol values. */
internal object CredentialAssociatedData {
    const val APPLICATION_ID = "com.yanjiyu.terminalspike"
    private const val DOMAIN = "credential-store"
    private const val SEPARATOR = '\u0000'

    fun encode(reference: CredentialSecretReference): ByteArray = buildString {
        append(APPLICATION_ID)
        append(SEPARATOR)
        append(DOMAIN)
        append(SEPARATOR)
        append(AesGcmCredentialStore.ENVELOPE_VERSION)
        append(SEPARATOR)
        append(reference.credentialId.value)
        append(SEPARATOR)
        append(reference.secretId.value)
        append(SEPARATOR)
        append(reference.kind.wireCode)
    }.toByteArray(Charsets.UTF_8)
}
