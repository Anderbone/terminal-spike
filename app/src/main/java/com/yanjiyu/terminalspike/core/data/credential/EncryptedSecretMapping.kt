package com.yanjiyu.terminalspike.core.data.credential

import com.yanjiyu.terminalspike.core.data.db.EncryptedSecretEntity
import com.yanjiyu.terminalspike.core.security.credential.AesGcmCredentialStore
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.EncryptedCredentialRecord
import com.yanjiyu.terminalspike.core.security.credential.SecretId

/** Stable database states. These values are persisted and must not be renamed implicitly. */
enum class EncryptedSecretState(val wireCode: String) {
    READY("ready"),
    LEGACY_UNAVAILABLE("legacy_unavailable"),
}

enum class PersistedCredentialMalformedReason {
    RECORD_ID,
    KIND,
    STATE,
    STATE_METADATA,
    ENVELOPE_VERSION,
    KEY_VERSION,
    NONCE,
    CIPHERTEXT,
    TIMESTAMP,
}

/** Typed persistence failures. Callers must surface recovery instead of deleting or defaulting. */
sealed class PersistedCredentialException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class LegacyUnavailable(
        val secretId: SecretId,
        val failureCode: String,
        val legacyId: String,
    ) : PersistedCredentialException(
        "Legacy credential ${secretId.value} is unavailable ($failureCode).",
    )

    class Malformed(
        val storedSecretId: String,
        val reason: PersistedCredentialMalformedReason,
        cause: Throwable? = null,
    ) : PersistedCredentialException(
        "Persisted credential $storedSecretId is malformed ($reason).",
        cause,
    )

    class ConcurrentMutation(val storedSecretId: String) : PersistedCredentialException(
        "Persisted credential $storedSecretId changed during an explicit update.",
    )
}

/** A READY ciphertext record plus its database-owned lifecycle timestamps. */
data class StoredEncryptedCredentialRecord(
    val encrypted: EncryptedCredentialRecord,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/**
 * Converts only READY rows. A retained legacy failure is deliberately distinguishable from a
 * malformed row so recovery UI cannot accidentally treat either as a missing credential.
 */
fun EncryptedSecretEntity.toStoredEncryptedCredentialRecord(): StoredEncryptedCredentialRecord {
    val header = validateHeader()
    when (header.state) {
        EncryptedSecretState.LEGACY_UNAVAILABLE -> throw PersistedCredentialException.LegacyUnavailable(
            secretId = header.secretId,
            failureCode = requireNotNull(failureCode),
            legacyId = requireNotNull(legacyId),
        )
        EncryptedSecretState.READY -> Unit
    }

    val storedNonce = nonce
        ?: throw malformed(PersistedCredentialMalformedReason.NONCE)
    val storedCiphertext = ciphertext
        ?: throw malformed(PersistedCredentialMalformedReason.CIPHERTEXT)
    if (storedNonce.isEmpty()) throw malformed(PersistedCredentialMalformedReason.NONCE)
    if (storedCiphertext.isEmpty()) throw malformed(PersistedCredentialMalformedReason.CIPHERTEXT)

    return StoredEncryptedCredentialRecord(
        encrypted = EncryptedCredentialRecord(
            secretId = id,
            kindCode = kindCode,
            envelopeVersion = envelopeVersion,
            keyVersion = keyVersion,
            nonce = storedNonce,
            ciphertext = storedCiphertext,
        ),
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

/** Builds a READY row using defensive copies from [EncryptedCredentialRecord]. */
fun EncryptedCredentialRecord.toReadyEntity(
    createdAtEpochMillis: Long,
    updatedAtEpochMillis: Long,
): EncryptedSecretEntity {
    validateRecordIdentityAndKind()
    if (envelopeVersion != AesGcmCredentialStore.ENVELOPE_VERSION) {
        throw malformed(secretId, PersistedCredentialMalformedReason.ENVELOPE_VERSION)
    }
    if (keyVersion != AesGcmCredentialStore.KEY_VERSION) {
        throw malformed(secretId, PersistedCredentialMalformedReason.KEY_VERSION)
    }
    validateTimestamps(secretId, createdAtEpochMillis, updatedAtEpochMillis)
    val storedNonce = copyNonce()
    val storedCiphertext = copyCiphertext()
    if (storedNonce.isEmpty()) {
        throw malformed(secretId, PersistedCredentialMalformedReason.NONCE)
    }
    if (storedCiphertext.isEmpty()) {
        throw malformed(secretId, PersistedCredentialMalformedReason.CIPHERTEXT)
    }
    return EncryptedSecretEntity(
        id = secretId,
        kindCode = kindCode,
        envelopeVersion = envelopeVersion,
        keyVersion = keyVersion,
        nonce = storedNonce,
        ciphertext = storedCiphertext,
        stateCode = EncryptedSecretState.READY.wireCode,
        failureCode = null,
        legacyId = null,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

internal data class ValidatedSecretHeader(
    val secretId: SecretId,
    val kind: CredentialSecretKind,
    val state: EncryptedSecretState,
)

/** Validates both READY rows and well-formed retained legacy rows before reads or replacement. */
internal fun EncryptedSecretEntity.validateHeader(): ValidatedSecretHeader {
    val secretId = try {
        SecretId.parseCanonical(id)
    } catch (error: IllegalArgumentException) {
        throw malformed(PersistedCredentialMalformedReason.RECORD_ID, error)
    }
    val kind = CredentialSecretKind.fromWireCodeOrNull(kindCode)
        ?: throw malformed(PersistedCredentialMalformedReason.KIND)
    val state = EncryptedSecretState.entries.firstOrNull { it.wireCode == stateCode }
        ?: throw malformed(PersistedCredentialMalformedReason.STATE)
    validateTimestamps(id, createdAtEpochMillis, updatedAtEpochMillis)

    when (state) {
        EncryptedSecretState.READY -> {
            if (envelopeVersion != AesGcmCredentialStore.ENVELOPE_VERSION) {
                throw malformed(PersistedCredentialMalformedReason.ENVELOPE_VERSION)
            }
            if (keyVersion != AesGcmCredentialStore.KEY_VERSION) {
                throw malformed(PersistedCredentialMalformedReason.KEY_VERSION)
            }
            if (failureCode != null || legacyId != null) {
                throw malformed(PersistedCredentialMalformedReason.STATE_METADATA)
            }
            if (nonce == null || nonce.isEmpty()) {
                throw malformed(PersistedCredentialMalformedReason.NONCE)
            }
            if (ciphertext == null || ciphertext.isEmpty()) {
                throw malformed(PersistedCredentialMalformedReason.CIPHERTEXT)
            }
        }
        EncryptedSecretState.LEGACY_UNAVAILABLE -> {
            if (
                envelopeVersion != 0 ||
                keyVersion != 0 ||
                nonce != null ||
                ciphertext != null ||
                failureCode.isNullOrBlank() ||
                legacyId.isNullOrBlank()
            ) {
                throw malformed(PersistedCredentialMalformedReason.STATE_METADATA)
            }
        }
    }
    return ValidatedSecretHeader(secretId, kind, state)
}

internal fun EncryptedCredentialRecord.validateRecordIdentityAndKind(): Pair<SecretId, CredentialSecretKind> {
    val parsedId = try {
        SecretId.parseCanonical(secretId)
    } catch (error: IllegalArgumentException) {
        throw malformed(secretId, PersistedCredentialMalformedReason.RECORD_ID, error)
    }
    val parsedKind = CredentialSecretKind.fromWireCodeOrNull(kindCode)
        ?: throw malformed(secretId, PersistedCredentialMalformedReason.KIND)
    return parsedId to parsedKind
}

private fun validateTimestamps(secretId: String, createdAt: Long, updatedAt: Long) {
    if (
        createdAt !in 0..MAX_SUPPORTED_EPOCH_MILLIS ||
        updatedAt !in createdAt..MAX_SUPPORTED_EPOCH_MILLIS
    ) {
        throw malformed(secretId, PersistedCredentialMalformedReason.TIMESTAMP)
    }
}

private fun EncryptedSecretEntity.malformed(
    reason: PersistedCredentialMalformedReason,
    cause: Throwable? = null,
) = malformed(id, reason, cause)

private fun malformed(
    secretId: String,
    reason: PersistedCredentialMalformedReason,
    cause: Throwable? = null,
) = PersistedCredentialException.Malformed(secretId, reason, cause)

// 9999-12-31T23:59:59.999Z, shared semantically with the domain model's timestamp bound.
private const val MAX_SUPPORTED_EPOCH_MILLIS = 253_402_300_799_999L
