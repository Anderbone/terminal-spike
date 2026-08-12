package com.yanjiyu.terminalspike.core.data.repository

import androidx.room.withTransaction
import com.yanjiyu.terminalspike.core.data.credential.CredentialEpochClock
import com.yanjiyu.terminalspike.core.data.credential.RoomCredentialAggregateStore
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.db.HostProfileEntity
import com.yanjiyu.terminalspike.core.data.db.SshCredentialEntity
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import com.yanjiyu.terminalspike.core.security.credential.CredentialId
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.CredentialStore
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import java.util.UUID

/** Opaque checkpoints used only to inject failures in transaction-boundary tests. */
internal enum class RoomTerminalToolsMutationCheckpoint {
    CREDENTIAL_COMMITTED,
    HOST_WRITTEN,
    PROMPT_CREDENTIAL_COMMITTED,
    PASSWORD_SECRET_CLEARED,
    HOST_DELETED,
    UNREFERENCED_CREDENTIAL_DELETED,
}

internal sealed class RoomTerminalToolsMutationException(message: String) :
    IllegalStateException(message) {
    class HostMissing(val hostId: String) : RoomTerminalToolsMutationException(
        "Host profile $hostId is missing.",
    )

    class StaleCredentialBinding(
        val hostId: String,
        val expectedCredentialId: String?,
        val actualCredentialId: String?,
    ) : RoomTerminalToolsMutationException(
        "Host profile $hostId changed while its credential was being updated.",
    )

    class MissingCredential(val credentialId: String) : RoomTerminalToolsMutationException(
        "SSH credential $credentialId is missing.",
    )

    class WrongCredentialKind(val credentialId: String) : RoomTerminalToolsMutationException(
        "SSH credential $credentialId is not a password credential.",
    )

    class SharedCredentialWrite(val credentialId: String) : RoomTerminalToolsMutationException(
        "SSH credential $credentialId is shared and cannot be changed in place.",
    )

    class GeneratedCredentialIdConflict(val credentialId: String) :
        RoomTerminalToolsMutationException(
            "Generated SSH credential ID $credentialId already exists.",
        )

    class ConcurrentMutation(val recordId: String) : RoomTerminalToolsMutationException(
        "Record $recordId changed during an explicit update.",
    )
}

/** Outcome of forgetting the password selected by one host. */
internal sealed interface ForgetHostPasswordResult {
    val hostFound: Boolean

    data object HostMissing : ForgetHostPasswordResult {
        override val hostFound: Boolean = false
    }

    data object NoSavedPassword : ForgetHostPasswordResult {
        override val hostFound: Boolean = true
    }

    /** The host's existing credential is now prompt-only. */
    data class RetainedPromptCredential(val credentialId: CredentialId) :
        ForgetHostPasswordResult {
        override val hostFound: Boolean = true
    }

    /** A new prompt-only credential isolates this host from other users of the saved secret. */
    data class SplitPromptCredential(
        val credentialId: CredentialId,
        val sharedCredentialId: CredentialId,
    ) : ForgetHostPasswordResult {
        override val hostFound: Boolean = true
    }
}

/**
 * Transaction boundary for host metadata and its password credential.
 *
 * Password encryption completes before Room opens a transaction. The resulting ciphertext,
 * credential metadata, and final host binding then commit together. The caller's mutable plaintext
 * is consumed and wiped on every exit, including validation and injected transaction failures.
 */
internal class RoomTerminalToolsMutations(
    private val database: AppDatabase,
    private val credentialMutations: RoomCredentialAggregateStore,
    private val credentialStore: CredentialStore,
    private val clock: CredentialEpochClock = CredentialEpochClock.SYSTEM,
    private val newCredentialId: () -> CredentialId = CredentialId::random,
    private val afterMutationStep: suspend (RoomTerminalToolsMutationCheckpoint) -> Unit = {},
) {
    private val hosts
        get() = database.hostProfileDao()

    private val credentials
        get() = database.credentialRecordDao()

    /** Creates or updates host metadata without changing the referenced credential's contents. */
    suspend fun upsertHost(profile: HostProfile) {
        val candidate = profile.toEntity()
        database.withTransaction {
            val existing = hosts.findById(candidate.id)
            existing?.toDomainModel()
            candidate.credentialId?.let { loadRequiredValidCredential(it) }
            writeHost(candidate, existing)
            afterMutationStep(RoomTerminalToolsMutationCheckpoint.HOST_WRITTEN)
        }
    }

    /**
     * Atomically changes non-secret authentication metadata and the host binding. A null
     * credential means prompt-password mode. Existing secret-bearing metadata is accepted only
     * when its opaque binding is unchanged; creating or replacing a secret uses the dedicated
     * encrypted aggregate path.
     */
    suspend fun upsertHostWithCredentialMetadata(
        profile: HostProfile,
        expectedCredentialId: String?,
        credential: SshCredential?,
    ) {
        val candidate = profile.toEntity()
        val expected = parseOptionalCredentialId(expectedCredentialId)
        require(candidate.credentialId == credential?.id) {
            "The host and authentication metadata must reference the same credential."
        }
        database.withTransaction {
            val existingHost = hosts.findById(candidate.id)
            existingHost?.toDomainModel()
            requireExpectedCredential(candidate.id, expected?.value, existingHost?.credentialId)
            val oldCredential = existingHost?.credentialId?.let { loadValidCredential(it) }
            credential?.let { metadata ->
                credentialMutations.saveCredentialMetadata(metadata.toEntity())
            }
            writeHost(candidate, existingHost)
            afterMutationStep(RoomTerminalToolsMutationCheckpoint.HOST_WRITTEN)
            if (oldCredential != null && oldCredential.id != credential?.id) {
                deletePasswordCredentialIfUnreferenced(oldCredential)
            }
        }
    }

    /**
     * Creates or updates a host while atomically attaching a freshly encrypted saved password.
     * [profile] must contain the requested final [credentialId]. For updates,
     * [expectedCredentialId] is a compare-and-set guard against overwriting a changed binding.
     */
    suspend fun upsertHostWithPassword(
        profile: HostProfile,
        expectedCredentialId: String?,
        credentialId: String,
        secretId: String,
        password: ByteArray,
    ) {
        try {
            val candidate = profile.toEntity()
            val expected = parseOptionalCredentialId(expectedCredentialId)
            val reference = passwordReference(credentialId, secretId)
            require(candidate.credentialId == reference.credentialId.value) {
                "The host must reference the password credential committed with it."
            }
            val now = checkedNow()
            val encrypted = credentialStore.encryptAndWipe(reference, password)
            database.withTransaction {
                val existingHost = hosts.findById(candidate.id)
                existingHost?.toDomainModel()
                requireExpectedCredential(candidate.id, expected?.value, existingHost?.credentialId)
                val oldCredential = existingHost?.credentialId?.let { loadValidCredential(it) }
                val existingCredential = credentials.findCredentialById(reference.credentialId.value)
                existingCredential?.let(::requirePasswordCredential)
                requireExclusiveHostCredentialWrite(
                    credentialId = reference.credentialId.value,
                    currentHostCredentialId = existingHost?.credentialId,
                )
                val credential = passwordCredential(
                    credentialId = reference.credentialId,
                    secretId = reference.secretId,
                    displayName = candidate.displayName,
                    existing = existingCredential,
                    now = now,
                )
                credentialMutations.commitCredentialAndSecret(reference, encrypted, credential)
                afterMutationStep(RoomTerminalToolsMutationCheckpoint.CREDENTIAL_COMMITTED)
                writeHost(candidate, existingHost)
                afterMutationStep(RoomTerminalToolsMutationCheckpoint.HOST_WRITTEN)
                if (oldCredential != null && oldCredential.id != reference.credentialId.value) {
                    deletePasswordCredentialIfUnreferenced(oldCredential)
                }
            }
        } finally {
            password.fill(0)
        }
    }

    /**
     * Saves a password for an existing host and returns the credential bound at commit time.
     * A shared existing credential is rejected rather than silently changing another host's login.
     */
    suspend fun savePassword(
        hostId: String,
        expectedCredentialId: String?,
        credentialId: String,
        secretId: String,
        password: ByteArray,
    ): CredentialId {
        try {
            val canonicalHostId = parseHostId(hostId)
            val expected = parseOptionalCredentialId(expectedCredentialId)
            val reference = passwordReference(credentialId, secretId)
            val now = checkedNow()
            val encrypted = credentialStore.encryptAndWipe(reference, password)
            database.withTransaction {
                val existingHost = hosts.findById(canonicalHostId)
                    ?: throw RoomTerminalToolsMutationException.HostMissing(canonicalHostId)
                val host = existingHost.toDomainModel()
                requireExpectedCredential(canonicalHostId, expected?.value, existingHost.credentialId)
                val oldCredential = existingHost.credentialId?.let { loadValidCredential(it) }
                val existingCredential = credentials.findCredentialById(reference.credentialId.value)
                existingCredential?.let(::requirePasswordCredential)
                requireExclusiveHostCredentialWrite(
                    credentialId = reference.credentialId.value,
                    currentHostCredentialId = existingHost.credentialId,
                )
                val credential = passwordCredential(
                    credentialId = reference.credentialId,
                    secretId = reference.secretId,
                    displayName = existingCredential?.name ?: host.displayName,
                    existing = existingCredential,
                    now = now,
                )
                credentialMutations.commitCredentialAndSecret(reference, encrypted, credential)
                afterMutationStep(RoomTerminalToolsMutationCheckpoint.CREDENTIAL_COMMITTED)
                val updatedHost = host.copy(
                    credentialId = reference.credentialId.value,
                    updatedAtEpochMillis = maxOf(now, host.updatedAtEpochMillis),
                ).toEntity()
                if (hosts.update(updatedHost) != 1) {
                    throw RoomTerminalToolsMutationException.ConcurrentMutation(canonicalHostId)
                }
                afterMutationStep(RoomTerminalToolsMutationCheckpoint.HOST_WRITTEN)
                if (oldCredential != null && oldCredential.id != reference.credentialId.value) {
                    deletePasswordCredentialIfUnreferenced(oldCredential)
                }
            }
            return reference.credentialId
        } finally {
            password.fill(0)
        }
    }

    /**
     * Removes the saved password for one host without affecting another host that shares it.
     *
     * An exclusively referenced credential remains bound as prompt-only metadata. A shared
     * credential is copied to a new prompt-only row for the target host while the original saved
     * credential and ciphertext remain untouched for every other host.
     */
    suspend fun forgetPassword(hostId: String): ForgetHostPasswordResult {
        val canonicalHostId = parseHostId(hostId)
        return database.withTransaction {
            val existingHost = hosts.findById(canonicalHostId)
                ?: return@withTransaction ForgetHostPasswordResult.HostMissing
            val host = existingHost.toDomainModel()
            val credentialId = existingHost.credentialId
                ?: return@withTransaction ForgetHostPasswordResult.NoSavedPassword
            val credential = loadRequiredValidCredential(credentialId)
            val authentication = credential.toDomainModel().authentication
            if (authentication !is SshAuthentication.Password || authentication.secretReferenceId == null) {
                return@withTransaction ForgetHostPasswordResult.NoSavedPassword
            }
            val referenceCount = hosts.countCredentialReferences(credentialId)
            check(referenceCount > 0) { "A host-bound credential must have at least one reference." }
            if (referenceCount == 1) {
                val result = credentialMutations.clearCredentialSecretAndDeleteIfUnreferenced(
                    CredentialId.parseCanonical(credentialId),
                )
                check(result.credentialFound && result.referenceCleared && result.secretDeleted) {
                    "The saved password could not be cleared atomically."
                }
                afterMutationStep(RoomTerminalToolsMutationCheckpoint.PASSWORD_SECRET_CLEARED)
                return@withTransaction ForgetHostPasswordResult.RetainedPromptCredential(
                    CredentialId.parseCanonical(credentialId),
                )
            }

            val promptCredentialId = newCredentialId()
            if (credentials.findCredentialById(promptCredentialId.value) != null) {
                throw RoomTerminalToolsMutationException.GeneratedCredentialIdConflict(
                    promptCredentialId.value,
                )
            }
            val now = checkedNow()
            val promptCredential = SshCredential(
                id = promptCredentialId.value,
                displayName = credential.name,
                authentication = SshAuthentication.Password(),
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ).toEntity()
            credentialMutations.saveCredentialMetadata(promptCredential)
            afterMutationStep(RoomTerminalToolsMutationCheckpoint.PROMPT_CREDENTIAL_COMMITTED)
            val updatedHost = host.copy(
                credentialId = promptCredentialId.value,
                updatedAtEpochMillis = maxOf(now, host.updatedAtEpochMillis),
            ).toEntity()
            if (hosts.update(updatedHost) != 1) {
                throw RoomTerminalToolsMutationException.ConcurrentMutation(canonicalHostId)
            }
            afterMutationStep(RoomTerminalToolsMutationCheckpoint.HOST_WRITTEN)
            ForgetHostPasswordResult.SplitPromptCredential(
                credentialId = promptCredentialId,
                sharedCredentialId = CredentialId.parseCanonical(credentialId),
            )
        }
    }

    /** Boolean compatibility helper for [TerminalDataPersistence.clearPassword]. */
    suspend fun clearPassword(hostId: String): Boolean = forgetPassword(hostId).hostFound

    /**
     * Deletes one host, then deletes its compatibility-owned password credential and secret only
     * when no other host references that credential. Reusable private-key and
     * keyboard-interactive metadata remain available even when the deleted host was their last
     * reference. Other host rows and shared authentication data are never modified.
     */
    suspend fun deleteHostAndUnreferencedCredential(hostId: String): Boolean {
        val canonicalHostId = parseHostId(hostId)
        return database.withTransaction {
            val existingHost = hosts.findById(canonicalHostId) ?: return@withTransaction false
            existingHost.toDomainModel()
            val credential = existingHost.credentialId?.let { loadValidCredential(it) }
            if (hosts.deleteById(canonicalHostId) != 1) {
                throw RoomTerminalToolsMutationException.ConcurrentMutation(canonicalHostId)
            }
            afterMutationStep(RoomTerminalToolsMutationCheckpoint.HOST_DELETED)
            if (credential != null && deletePasswordCredentialIfUnreferenced(credential)) {
                afterMutationStep(
                    RoomTerminalToolsMutationCheckpoint.UNREFERENCED_CREDENTIAL_DELETED,
                )
            }
            true
        }
    }

    /** Compatibility name for the current legacy-shaped persistence interface. */
    suspend fun deleteHostAndUnreferencedPassword(hostId: String): Boolean =
        deleteHostAndUnreferencedCredential(hostId)

    private suspend fun loadRequiredValidCredential(credentialId: String): SshCredentialEntity =
        loadValidCredential(credentialId)
            ?: throw RoomTerminalToolsMutationException.MissingCredential(credentialId)

    private suspend fun loadValidCredential(credentialId: String): SshCredentialEntity? {
        CredentialId.parseCanonical(credentialId)
        return credentials.findCredentialById(credentialId)?.also { it.toDomainModel() }
    }

    private fun requirePasswordCredential(credential: SshCredentialEntity) {
        val domain = credential.toDomainModel()
        if (domain.authentication !is SshAuthentication.Password) {
            throw RoomTerminalToolsMutationException.WrongCredentialKind(credential.id)
        }
    }

    private suspend fun requireExclusiveHostCredentialWrite(
        credentialId: String,
        currentHostCredentialId: String?,
    ) {
        val allowedReferenceCount = if (currentHostCredentialId == credentialId) 1 else 0
        if (hosts.countCredentialReferences(credentialId) > allowedReferenceCount) {
            throw RoomTerminalToolsMutationException.SharedCredentialWrite(credentialId)
        }
    }

    private suspend fun deletePasswordCredentialIfUnreferenced(
        credential: SshCredentialEntity,
    ): Boolean {
        if (credential.toDomainModel().authentication !is SshAuthentication.Password) return false
        if (hosts.countCredentialReferences(credential.id) != 0) return false
        val result = credentialMutations.deleteCredentialAndUnreferencedSecret(
            CredentialId.parseCanonical(credential.id),
        )
        check(result.metadataDeleted) { "Unreferenced credential metadata disappeared during deletion." }
        return true
    }

    private suspend fun writeHost(candidate: HostProfileEntity, existing: HostProfileEntity?) {
        if (existing == null) {
            hosts.insert(candidate)
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
        if (hosts.update(updated) != 1) {
            throw RoomTerminalToolsMutationException.ConcurrentMutation(candidate.id)
        }
    }

    private fun passwordCredential(
        credentialId: CredentialId,
        secretId: SecretId,
        displayName: String,
        existing: SshCredentialEntity?,
        now: Long,
    ): SshCredentialEntity = SshCredential(
        id = credentialId.value,
        displayName = displayName,
        authentication = SshAuthentication.Password(secretId.value),
        createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
        updatedAtEpochMillis = maxOf(now, existing?.updatedAtEpochMillis ?: now),
    ).toEntity()

    private fun passwordReference(
        credentialId: String,
        secretId: String,
    ) = CredentialSecretReference(
        credentialId = CredentialId.parseCanonical(credentialId),
        secretId = SecretId.parseCanonical(secretId),
        kind = CredentialSecretKind.PASSWORD,
    )

    private fun requireExpectedCredential(
        hostId: String,
        expectedCredentialId: String?,
        actualCredentialId: String?,
    ) {
        if (expectedCredentialId != actualCredentialId) {
            throw RoomTerminalToolsMutationException.StaleCredentialBinding(
                hostId = hostId,
                expectedCredentialId = expectedCredentialId,
                actualCredentialId = actualCredentialId,
            )
        }
    }

    private fun parseOptionalCredentialId(value: String?): CredentialId? =
        value?.let(CredentialId::parseCanonical)

    private fun parseHostId(value: String): String {
        val parsed = runCatching { UUID.fromString(value) }.getOrNull()
        require(value.length == 36 && parsed?.toString() == value) {
            "Host profile ID must be a canonical lowercase UUID."
        }
        return value
    }

    private fun checkedNow(): Long = clock.nowEpochMillis().also { now ->
        require(now in 0..MAX_SUPPORTED_EPOCH_MILLIS) {
            "Timestamp is outside the supported range."
        }
    }

    private companion object {
        const val MAX_SUPPORTED_EPOCH_MILLIS = 253_402_300_799_999L
    }
}
