package com.yanjiyu.terminalspike.core.security.credential

import java.util.UUID
import javax.crypto.SecretKey

/** Stable, canonical identifier for non-secret credential metadata. */
@JvmInline
value class CredentialId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): CredentialId = CredentialId(uuid.toString())

        fun parseCanonical(value: String): CredentialId =
            CredentialId(requireCanonicalUuid(value, "credential ID"))

        fun random(): CredentialId = from(UUID.randomUUID())
    }
}

/** Stable, canonical identifier for one encrypted secret record. */
@JvmInline
value class SecretId private constructor(val value: String) {
    companion object {
        fun from(uuid: UUID): SecretId = SecretId(uuid.toString())

        fun parseCanonical(value: String): SecretId =
            SecretId(requireCanonicalUuid(value, "secret ID"))

        fun random(): SecretId = from(UUID.randomUUID())
    }
}

/** The only plaintext purposes accepted by the device-bound credential store. */
enum class CredentialSecretKind(
    val wireCode: String,
    internal val maximumPlaintextBytes: Int,
) {
    PASSWORD("password", 4 * 1024),
    KEYBOARD_INTERACTIVE("keyboard_interactive", 4 * 1024),
    PRIVATE_KEY("private_key", 256 * 1024),
    KEY_PASSPHRASE("key_passphrase", 4 * 1024),
    ;

    companion object {
        internal fun fromWireCodeOrNull(wireCode: String): CredentialSecretKind? =
            entries.firstOrNull { it.wireCode == wireCode }
    }
}

/**
 * Immutable identity and purpose used to authenticate a secret envelope.
 *
 * Both UUIDs are deliberately independent of mutable host names, labels, and endpoints. A private
 * key can use its key-identity UUID as [credentialId], while a password or saved passphrase uses the
 * owning SSH credential UUID.
 */
data class CredentialSecretReference(
    val credentialId: CredentialId,
    val secretId: SecretId,
    val kind: CredentialSecretKind,
)

/**
 * Ciphertext-only persistence value. Array inputs and outputs are copied so a storage adapter cannot
 * accidentally mutate an envelope after it has been authenticated.
 */
class EncryptedCredentialRecord(
    val secretId: String,
    val kindCode: String,
    val envelopeVersion: Int,
    val keyVersion: Int,
    nonce: ByteArray,
    ciphertext: ByteArray,
) {
    private val storedNonce = nonce.copyOf()
    private val storedCiphertext = ciphertext.copyOf()

    val nonceSize: Int
        get() = storedNonce.size

    val ciphertextSize: Int
        get() = storedCiphertext.size

    fun copyNonce(): ByteArray = storedNonce.copyOf()

    fun copyCiphertext(): ByteArray = storedCiphertext.copyOf()
}

/**
 * Persistence boundary for encrypted records. A Room adapter can implement each method inside the
 * repository transaction that updates its credential metadata; plaintext never crosses this API.
 */
interface CredentialCiphertextStore {
    suspend fun read(secretId: SecretId): EncryptedCredentialRecord?

    suspend fun write(record: EncryptedCredentialRecord)

    suspend fun delete(secretId: SecretId): Boolean
}

/** Key boundary kept separate from record persistence for real Keystore and deterministic tests. */
interface CredentialKeyProvider {
    /** Returns the existing key or creates it for a deliberate credential write. */
    fun getOrCreateEncryptionKey(keyVersion: Int): SecretKey

    /** Returns only an existing key. A read must never create replacement key material. */
    fun getExistingDecryptionKey(keyVersion: Int): SecretKey?
}

interface CredentialStore {
    /**
     * Encrypts [secret] without persisting it and returns a defensive ciphertext record. The
     * supplied mutable array is consumed and zeroed on every exit. This lets a repository perform
     * the comparatively expensive Keystore operation before opening a metadata transaction, then
     * commit the returned ciphertext and its referencing row atomically.
     */
    suspend fun encryptAndWipe(
        reference: CredentialSecretReference,
        secret: ByteArray,
    ): EncryptedCredentialRecord

    /**
     * Encrypts and stores [secret]. The supplied mutable array is consumed and zeroed on every exit,
     * including validation, Keystore, encryption, and persistence failures.
     */
    suspend fun saveAndWipe(reference: CredentialSecretReference, secret: ByteArray)

    /**
     * Decrypts only for the duration of [use]. The exact array passed to [use] is zeroed in `finally`,
     * including when the callback fails or its coroutine is cancelled.
     */
    suspend fun <T> withSecret(
        reference: CredentialSecretReference,
        use: suspend (ByteArray) -> T,
    ): T

    suspend fun delete(secretId: SecretId): Boolean
}

/**
 * Scoped decryption of an immutable ciphertext record already captured by a trusted transaction.
 * Backup uses this boundary to avoid rereading a newer credential after its metadata snapshot.
 */
interface CredentialRecordDecryptor {
    suspend fun <T> withEncryptedRecord(
        reference: CredentialSecretReference,
        record: EncryptedCredentialRecord,
        use: suspend (ByteArray) -> T,
    ): T
}

enum class MalformedCredentialReason {
    RECORD_ID,
    KIND,
    ENVELOPE_VERSION,
    KEY_VERSION,
    NONCE,
    CIPHERTEXT_SIZE,
    PLAINTEXT_SIZE,
}

/** Stable error categories suitable for recovery UI without leaking credential contents. */
sealed class CredentialStoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Missing(val secretId: SecretId) : CredentialStoreException(
        "Encrypted credential ${secretId.value} is missing.",
    )

    class Malformed(
        val secretId: SecretId,
        val reason: MalformedCredentialReason,
    ) : CredentialStoreException(
        "Encrypted credential ${secretId.value} is malformed ($reason).",
    )

    class Tampered(val secretId: SecretId) : CredentialStoreException(
        "Encrypted credential ${secretId.value} did not authenticate.",
    )

    class KeyUnavailable(
        val secretId: SecretId,
        cause: Throwable? = null,
    ) : CredentialStoreException(
        "The device credential key is unavailable for ${secretId.value}.",
        cause,
    )
}

private fun requireCanonicalUuid(value: String, fieldName: String): String {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull()
    require(value.length == 36 && parsed?.toString() == value) {
        "$fieldName must be a canonical lowercase UUID."
    }
    return value
}
