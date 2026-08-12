package com.yanjiyu.terminalspike.core.data.credential

import androidx.room.withTransaction
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.db.CredentialRecordDao
import com.yanjiyu.terminalspike.core.data.db.EncryptedSecretEntity
import com.yanjiyu.terminalspike.core.security.credential.CredentialCiphertextStore
import com.yanjiyu.terminalspike.core.security.credential.EncryptedCredentialRecord
import com.yanjiyu.terminalspike.core.security.credential.SecretId

fun interface CredentialEpochClock {
    fun nowEpochMillis(): Long

    companion object {
        val SYSTEM = CredentialEpochClock(System::currentTimeMillis)
    }
}

/** Room adapter that exposes only defensive ciphertext records to the crypto boundary. */
class RoomCredentialCiphertextStore(
    private val database: AppDatabase,
    private val clock: CredentialEpochClock = CredentialEpochClock.SYSTEM,
) : CredentialCiphertextStore {
    private val records: CredentialRecordDao
        get() = database.credentialRecordDao()

    override suspend fun read(secretId: SecretId): EncryptedCredentialRecord? =
        records.findSecretById(secretId.value)
            ?.toStoredEncryptedCredentialRecord()
            ?.encrypted

    override suspend fun write(record: EncryptedCredentialRecord) {
        // Validate and copy the ciphertext before acquiring the database transaction.
        val now = clock.nowEpochMillis()
        val candidate = record.toReadyEntity(
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        database.withTransaction {
            records.writeReadyRecord(candidate)
        }
    }

    override suspend fun delete(secretId: SecretId): Boolean = database.withTransaction {
        records.deleteSecretById(secretId.value) == 1
    }
}

/** Must be invoked inside the caller's Room transaction. Uses INSERT/UPDATE, never REPLACE. */
internal suspend fun CredentialRecordDao.writeReadyRecord(candidate: EncryptedSecretEntity) {
    val existing = findSecretById(candidate.id)
    if (existing == null) {
        insertSecret(candidate)
        return
    }

    val existingHeader = existing.validateHeader()
    val candidateHeader = candidate.validateHeader()
    if (existingHeader.kind != candidateHeader.kind) {
        throw PersistedCredentialException.Malformed(
            storedSecretId = candidate.id,
            reason = PersistedCredentialMalformedReason.KIND,
        )
    }
    val updated = candidate.copy(
        createdAtEpochMillis = existing.createdAtEpochMillis,
        updatedAtEpochMillis = maxOf(
            candidate.updatedAtEpochMillis,
            existing.createdAtEpochMillis,
            existing.updatedAtEpochMillis,
        ),
    )
    if (updateSecret(updated) != 1) {
        throw PersistedCredentialException.ConcurrentMutation(candidate.id)
    }
}

/** Must be invoked inside the caller's Room transaction. */
internal suspend fun CredentialRecordDao.deleteSecretIfUnreferenced(secretId: String): Boolean {
    if (countCredentialSecretReferences(secretId) != 0) return false
    if (countIdentitySecretReferences(secretId) != 0) return false
    return deleteSecretById(secretId) == 1
}
