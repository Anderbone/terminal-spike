package com.yanjiyu.terminalspike.core.data.credential

import androidx.room.withTransaction
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.db.CredentialRecordDao
import com.yanjiyu.terminalspike.core.data.db.SshCredentialEntity
import com.yanjiyu.terminalspike.core.data.db.SshKeyIdentityEntity
import com.yanjiyu.terminalspike.core.model.SshCredentialKind
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.core.security.credential.CredentialId
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.CredentialStore
import com.yanjiyu.terminalspike.core.security.credential.EncryptedCredentialRecord
import com.yanjiyu.terminalspike.core.security.credential.SecretId

enum class CredentialAggregateMalformedReason {
    CREDENTIAL_ID,
    IDENTITY_ID,
    SECRET_ID,
    KIND,
    REFERENCE,
    TIMESTAMP,
}

sealed class CredentialAggregateException(message: String) : Exception(message) {
    class Malformed(
        val recordId: String,
        val reason: CredentialAggregateMalformedReason,
    ) : CredentialAggregateException("Credential aggregate $recordId is malformed ($reason).")

    class ConcurrentMutation(val recordId: String) : CredentialAggregateException(
        "Credential aggregate $recordId changed during an explicit update.",
    )
}

data class ClearCredentialSecretResult(
    val credentialFound: Boolean,
    val referenceCleared: Boolean,
    val secretDeleted: Boolean,
)

data class DeleteCredentialMetadataResult(
    val metadataDeleted: Boolean,
    val secretDeleted: Boolean,
)

data class SavedCredentialClearPreview(
    val credentialCount: Int,
    val keyIdentityCount: Int,
) {
    init {
        require(credentialCount >= 0 && keyIdentityCount >= 0) {
            "Saved credential clear counts must not be negative."
        }
    }
}

data class ClearAllSavedCredentialsResult(
    val hostReferencesDetached: Int,
    val credentialMetadataDeleted: Int,
    val keyIdentitiesDeleted: Int,
    val secretEnvelopesDeleted: Int,
)

enum class CredentialBulkClearCheckpoint {
    HOST_REFERENCES_DETACHED,
    CREDENTIAL_METADATA_DELETED,
    KEY_IDENTITIES_DELETED,
    SECRET_ENVELOPES_DELETED,
}

interface SavedCredentialBulkClear {
    suspend fun previewClearAllSavedCredentials(): SavedCredentialClearPreview

    suspend fun clearAllSavedCredentials(): ClearAllSavedCredentialsResult
}

/**
 * The only mutation surface used by non-secret SSH credential repositories.
 *
 * Implementations own the transaction joining an encrypted payload to its metadata row. Keeping
 * this interface entity-based also prevents a repository or UI caller from gaining a ciphertext
 * read API merely to update a display name.
 */
interface CredentialAggregateMutations {
    suspend fun saveCredentialMetadata(credential: SshCredentialEntity)

    suspend fun saveCredentialAndSecret(
        reference: CredentialSecretReference,
        secret: ByteArray,
        credential: SshCredentialEntity,
    )

    suspend fun updateIdentityMetadata(identity: SshKeyIdentityEntity): Boolean

    suspend fun saveIdentityAndPrivateKey(
        reference: CredentialSecretReference,
        privateKey: ByteArray,
        identity: SshKeyIdentityEntity,
    )

    suspend fun clearCredentialSecretAndDeleteIfUnreferenced(
        credentialId: CredentialId,
    ): ClearCredentialSecretResult

    suspend fun deleteCredentialAndUnreferencedSecret(
        credentialId: CredentialId,
    ): DeleteCredentialMetadataResult

    suspend fun deleteIdentityAndUnreferencedPrivateSecret(
        identityId: CredentialId,
    ): DeleteCredentialMetadataResult
}

/**
 * Transaction boundary for ciphertext plus the metadata row that references it. Encryption always
 * completes, and consumes the caller's plaintext buffer, before a Room transaction begins.
 */
class RoomCredentialAggregateStore(
    private val database: AppDatabase,
    private val credentialStore: CredentialStore,
    private val clock: CredentialEpochClock = CredentialEpochClock.SYSTEM,
    /** Test seam invoked inside the Room transaction to prove failure rollback at every stage. */
    private val afterBulkClearStep: suspend (CredentialBulkClearCheckpoint) -> Unit = {},
) : CredentialAggregateMutations, SavedCredentialBulkClear {
    private val records: CredentialRecordDao
        get() = database.credentialRecordDao()

    override suspend fun previewClearAllSavedCredentials(): SavedCredentialClearPreview =
        database.withTransaction {
            val bulk = database.credentialBulkClearDao()
            SavedCredentialClearPreview(
                credentialCount = bulk.countCredentials(),
                keyIdentityCount = bulk.countKeyIdentities(),
            )
        }

    /** Host profiles survive and become prompt-on-connect; unrelated Room aggregates are untouched. */
    override suspend fun clearAllSavedCredentials(): ClearAllSavedCredentialsResult =
        database.withTransaction {
            val bulk = database.credentialBulkClearDao()
            val preview = SavedCredentialClearPreview(
                credentialCount = bulk.countCredentials(),
                keyIdentityCount = bulk.countKeyIdentities(),
            )
            val detached = bulk.detachHostCredentialReferences()
            afterBulkClearStep(CredentialBulkClearCheckpoint.HOST_REFERENCES_DETACHED)

            val credentialsDeleted = bulk.deleteAllCredentials()
            check(credentialsDeleted == preview.credentialCount) {
                "Credential metadata changed during the clear-all transaction."
            }
            afterBulkClearStep(CredentialBulkClearCheckpoint.CREDENTIAL_METADATA_DELETED)

            val identitiesDeleted = bulk.deleteAllKeyIdentities()
            check(identitiesDeleted == preview.keyIdentityCount) {
                "SSH key identities changed during the clear-all transaction."
            }
            afterBulkClearStep(CredentialBulkClearCheckpoint.KEY_IDENTITIES_DELETED)

            val secretsDeleted = bulk.deleteAllUnreferencedSecrets()
            afterBulkClearStep(CredentialBulkClearCheckpoint.SECRET_ENVELOPES_DELETED)
            ClearAllSavedCredentialsResult(
                hostReferencesDetached = detached,
                credentialMetadataDeleted = credentialsDeleted,
                keyIdentitiesDeleted = identitiesDeleted,
                secretEnvelopesDeleted = secretsDeleted,
            )
        }

    override suspend fun saveCredentialAndSecret(
        reference: CredentialSecretReference,
        secret: ByteArray,
        credential: SshCredentialEntity,
    ) {
        val encrypted = credentialStore.encryptAndWipe(reference, secret)
        commitCredentialAndSecret(reference, encrypted, credential)
    }

    override suspend fun saveIdentityAndPrivateKey(
        reference: CredentialSecretReference,
        privateKey: ByteArray,
        identity: SshKeyIdentityEntity,
    ) {
        val encrypted = credentialStore.encryptAndWipe(reference, privateKey)
        commitIdentityAndPrivateKey(reference, encrypted, identity)
    }

    /**
     * Inserts prompt-only metadata or updates metadata without rebinding an existing secret.
     * Secret-bearing inserts and every secret-reference change must use [saveCredentialAndSecret]
     * so the reference and freshly authenticated ciphertext are committed together.
     */
    override suspend fun saveCredentialMetadata(credential: SshCredentialEntity) {
        val candidateKind = validateCredentialMetadata(credential)
        database.withTransaction {
            val existing = records.findCredentialById(credential.id)
            if (existing == null) {
                if (credential.secretId != null) {
                    throw malformed(credential.id, CredentialAggregateMalformedReason.REFERENCE)
                }
                records.insertCredential(credential)
                return@withTransaction
            }

            val existingKind = validateCredentialMetadata(existing)
            val keepsSecretBinding = credential.secretId == existing.secretId &&
                (
                    existing.secretId == null ||
                        (
                            candidateKind == existingKind &&
                                credential.keyIdentityId == existing.keyIdentityId
                            )
                    )
            if (!keepsSecretBinding) {
                throw malformed(credential.id, CredentialAggregateMalformedReason.REFERENCE)
            }
            records.insertOrUpdateCredential(credential, existing)
        }
    }

    /** Updates public identity metadata while preserving the encrypted private-key binding. */
    override suspend fun updateIdentityMetadata(identity: SshKeyIdentityEntity): Boolean {
        validateIdentityMetadata(identity)
        return database.withTransaction {
            val existing = records.findIdentityById(identity.id) ?: return@withTransaction false
            validateIdentityMetadata(existing)
            if (identity.privateSecretId != existing.privateSecretId) {
                throw malformed(identity.id, CredentialAggregateMalformedReason.REFERENCE)
            }
            records.insertOrUpdateIdentity(identity, existing)
            true
        }
    }

    /** Supports migration/import paths that already hold an authenticated ciphertext envelope. */
    suspend fun commitCredentialAndSecret(
        reference: CredentialSecretReference,
        encrypted: EncryptedCredentialRecord,
        credential: SshCredentialEntity,
    ) {
        validateCredentialAggregate(reference, encrypted, credential)
        val now = clock.nowEpochMillis()
        val secret = encrypted.toReadyEntity(now, now)
        database.withTransaction {
            val existing = records.findCredentialById(credential.id)
            existing?.let(::validateCredentialMetadata)
            records.requireExclusiveCredentialSecretOwner(secret.id, credential.id)
            val oldSecretId = existing?.secretId
            records.writeReadyRecord(secret)
            records.insertOrUpdateCredential(credential, existing)
            if (oldSecretId != null && oldSecretId != secret.id) {
                records.deleteSecretIfUnreferenced(oldSecretId)
            }
        }
    }

    /** Supports migration/import paths that already hold an authenticated ciphertext envelope. */
    suspend fun commitIdentityAndPrivateKey(
        reference: CredentialSecretReference,
        encrypted: EncryptedCredentialRecord,
        identity: SshKeyIdentityEntity,
    ) {
        validateIdentityAggregate(reference, encrypted, identity)
        val now = clock.nowEpochMillis()
        val secret = encrypted.toReadyEntity(now, now)
        database.withTransaction {
            val existing = records.findIdentityById(identity.id)
            existing?.let(::validateIdentityMetadata)
            records.requireExclusiveIdentitySecretOwner(secret.id, identity.id)
            val oldSecretId = existing?.privateSecretId
            records.writeReadyRecord(secret)
            records.insertOrUpdateIdentity(identity, existing)
            if (oldSecretId != null && oldSecretId != secret.id) {
                records.deleteSecretIfUnreferenced(oldSecretId)
            }
        }
    }

    /** Clears the nullable credential reference and removes its ciphertext only when now orphaned. */
    override suspend fun clearCredentialSecretAndDeleteIfUnreferenced(
        credentialId: CredentialId,
    ): ClearCredentialSecretResult {
        val now = checkedNow(credentialId.value)
        return database.withTransaction {
            val existing = records.findCredentialById(credentialId.value)
                ?: return@withTransaction ClearCredentialSecretResult(
                    credentialFound = false,
                    referenceCleared = false,
                    secretDeleted = false,
                )
            validateCredentialMetadata(existing)
            val secretId = existing.secretId
                ?: return@withTransaction ClearCredentialSecretResult(
                    credentialFound = true,
                    referenceCleared = false,
                    secretDeleted = false,
                )
            val updated = existing.copy(
                secretId = null,
                updatedAtEpochMillis = maxOf(
                    now,
                    existing.createdAtEpochMillis,
                    existing.updatedAtEpochMillis,
                ),
            )
            if (records.updateCredential(updated) != 1) {
                throw CredentialAggregateException.ConcurrentMutation(credentialId.value)
            }
            ClearCredentialSecretResult(
                credentialFound = true,
                referenceCleared = true,
                secretDeleted = records.deleteSecretIfUnreferenced(secretId),
            )
        }
    }

    /** Deletes one credential row and then its old secret only if no aggregate still references it. */
    override suspend fun deleteCredentialAndUnreferencedSecret(
        credentialId: CredentialId,
    ): DeleteCredentialMetadataResult = database.withTransaction {
        val existing = records.findCredentialById(credentialId.value)
            ?: return@withTransaction DeleteCredentialMetadataResult(false, false)
        validateCredentialMetadata(existing)
        if (records.deleteCredentialById(credentialId.value) != 1) {
            throw CredentialAggregateException.ConcurrentMutation(credentialId.value)
        }
        DeleteCredentialMetadataResult(
            metadataDeleted = true,
            secretDeleted = existing.secretId?.let { records.deleteSecretIfUnreferenced(it) } == true,
        )
    }

    /**
     * Deletes an identity and then its private-key ciphertext. A referencing credential is protected
     * by the database RESTRICT foreign key, which rolls the entire transaction back.
     */
    override suspend fun deleteIdentityAndUnreferencedPrivateSecret(
        identityId: CredentialId,
    ): DeleteCredentialMetadataResult = database.withTransaction {
        val existing = records.findIdentityById(identityId.value)
            ?: return@withTransaction DeleteCredentialMetadataResult(false, false)
        validateIdentityMetadata(existing)
        if (records.deleteIdentityById(identityId.value) != 1) {
            throw CredentialAggregateException.ConcurrentMutation(identityId.value)
        }
        DeleteCredentialMetadataResult(
            metadataDeleted = true,
            secretDeleted = records.deleteSecretIfUnreferenced(existing.privateSecretId),
        )
    }

    /** Never clears metadata implicitly; referenced ciphertext remains protected. */
    suspend fun deleteUnreferencedSecret(secretId: SecretId): Boolean = database.withTransaction {
        records.deleteSecretIfUnreferenced(secretId.value)
    }

    private fun validateCredentialAggregate(
        reference: CredentialSecretReference,
        encrypted: EncryptedCredentialRecord,
        credential: SshCredentialEntity,
    ) {
        val (recordSecretId, recordKind) = encrypted.validateRecordIdentityAndKind()
        val credentialKind = validateCredentialMetadata(credential)
        if (
            credential.id != reference.credentialId.value ||
            recordSecretId != reference.secretId ||
            recordKind != reference.kind ||
            credential.secretId != reference.secretId.value
        ) {
            throw malformed(credential.id, CredentialAggregateMalformedReason.REFERENCE)
        }
        val combinationValid = when (credentialKind) {
            SshCredentialKind.PASSWORD ->
                credential.keyIdentityId == null && reference.kind == CredentialSecretKind.PASSWORD
            SshCredentialKind.KEYBOARD_INTERACTIVE ->
                credential.keyIdentityId == null &&
                    reference.kind == CredentialSecretKind.KEYBOARD_INTERACTIVE
            SshCredentialKind.PRIVATE_KEY ->
                credential.keyIdentityId != null && reference.kind == CredentialSecretKind.KEY_PASSPHRASE
        }
        if (!combinationValid) {
            throw malformed(credential.id, CredentialAggregateMalformedReason.KIND)
        }
    }

    private fun validateIdentityAggregate(
        reference: CredentialSecretReference,
        encrypted: EncryptedCredentialRecord,
        identity: SshKeyIdentityEntity,
    ) {
        val (recordSecretId, recordKind) = encrypted.validateRecordIdentityAndKind()
        validateIdentityMetadata(identity)
        if (
            identity.id != reference.credentialId.value ||
            identity.privateSecretId != reference.secretId.value ||
            recordSecretId != reference.secretId ||
            recordKind != CredentialSecretKind.PRIVATE_KEY ||
            reference.kind != CredentialSecretKind.PRIVATE_KEY
        ) {
            throw malformed(identity.id, CredentialAggregateMalformedReason.REFERENCE)
        }
    }

    private fun checkedNow(recordId: String): Long {
        val now = clock.nowEpochMillis()
        validateTimestamps(recordId, now, now)
        return now
    }

    private fun validateCredentialMetadata(credential: SshCredentialEntity): SshCredentialKind {
        parseCredentialId(credential.id)
        credential.secretId?.let(::parseSecretId)
        credential.keyIdentityId?.let(::parseIdentityId)
        validateTimestamps(credential.id, credential.createdAtEpochMillis, credential.updatedAtEpochMillis)
        val kind = try {
            SshCredentialKind.fromWireCode(credential.kindCode)
        } catch (_: IllegalArgumentException) {
            throw malformed(credential.id, CredentialAggregateMalformedReason.KIND)
        }
        val referencesValid = when (kind) {
            SshCredentialKind.PASSWORD,
            SshCredentialKind.KEYBOARD_INTERACTIVE,
            -> credential.keyIdentityId == null
            SshCredentialKind.PRIVATE_KEY -> credential.keyIdentityId != null
        }
        if (!referencesValid) {
            throw malformed(credential.id, CredentialAggregateMalformedReason.REFERENCE)
        }
        return kind
    }

    private fun validateIdentityMetadata(identity: SshKeyIdentityEntity) {
        parseIdentityId(identity.id)
        parseSecretId(identity.privateSecretId)
        try {
            SshKeyOrigin.fromWireCode(identity.provenanceCode)
        } catch (_: IllegalArgumentException) {
            throw malformed(identity.id, CredentialAggregateMalformedReason.KIND)
        }
        validateTimestamps(identity.id, identity.createdAtEpochMillis, identity.updatedAtEpochMillis)
    }

    private fun parseCredentialId(value: String) {
        try {
            CredentialId.parseCanonical(value)
        } catch (_: IllegalArgumentException) {
            throw malformed(value, CredentialAggregateMalformedReason.CREDENTIAL_ID)
        }
    }

    private fun parseIdentityId(value: String) {
        try {
            CredentialId.parseCanonical(value)
        } catch (_: IllegalArgumentException) {
            throw malformed(value, CredentialAggregateMalformedReason.IDENTITY_ID)
        }
    }

    private fun parseSecretId(value: String) {
        try {
            SecretId.parseCanonical(value)
        } catch (_: IllegalArgumentException) {
            throw malformed(value, CredentialAggregateMalformedReason.SECRET_ID)
        }
    }

    private fun validateTimestamps(recordId: String, createdAt: Long, updatedAt: Long) {
        if (
            createdAt !in 0..MAX_SUPPORTED_EPOCH_MILLIS ||
            updatedAt !in createdAt..MAX_SUPPORTED_EPOCH_MILLIS
        ) {
            throw malformed(recordId, CredentialAggregateMalformedReason.TIMESTAMP)
        }
    }

    private fun malformed(recordId: String, reason: CredentialAggregateMalformedReason) =
        CredentialAggregateException.Malformed(recordId, reason)

    private suspend fun CredentialRecordDao.insertOrUpdateCredential(
        candidate: SshCredentialEntity,
        existing: SshCredentialEntity?,
    ) {
        if (existing == null) {
            insertCredential(candidate)
            return
        }
        val updated = candidate.copy(
            createdAtEpochMillis = existing.createdAtEpochMillis,
            updatedAtEpochMillis = maxOf(
                candidate.updatedAtEpochMillis,
                existing.createdAtEpochMillis,
                existing.updatedAtEpochMillis,
            ),
        )
        if (updateCredential(updated) != 1) {
            throw CredentialAggregateException.ConcurrentMutation(candidate.id)
        }
    }

    private suspend fun CredentialRecordDao.requireExclusiveCredentialSecretOwner(
        secretId: String,
        credentialId: String,
    ) {
        val credentialOwner = findCredentialOwnerOfSecret(secretId)
        val identityOwner = findIdentityOwnerOfSecret(secretId)
        if ((credentialOwner != null && credentialOwner != credentialId) || identityOwner != null) {
            throw malformed(credentialId, CredentialAggregateMalformedReason.REFERENCE)
        }
    }

    private suspend fun CredentialRecordDao.requireExclusiveIdentitySecretOwner(
        secretId: String,
        identityId: String,
    ) {
        val credentialOwner = findCredentialOwnerOfSecret(secretId)
        val identityOwner = findIdentityOwnerOfSecret(secretId)
        if (credentialOwner != null || (identityOwner != null && identityOwner != identityId)) {
            throw malformed(identityId, CredentialAggregateMalformedReason.REFERENCE)
        }
    }

    private suspend fun CredentialRecordDao.insertOrUpdateIdentity(
        candidate: SshKeyIdentityEntity,
        existing: SshKeyIdentityEntity?,
    ) {
        if (existing == null) {
            insertIdentity(candidate)
            return
        }
        val updated = candidate.copy(
            createdAtEpochMillis = existing.createdAtEpochMillis,
            updatedAtEpochMillis = maxOf(
                candidate.updatedAtEpochMillis,
                existing.createdAtEpochMillis,
                existing.updatedAtEpochMillis,
            ),
        )
        if (updateIdentity(updated) != 1) {
            throw CredentialAggregateException.ConcurrentMutation(candidate.id)
        }
    }

    private companion object {
        const val MAX_SUPPORTED_EPOCH_MILLIS = 253_402_300_799_999L
    }
}
