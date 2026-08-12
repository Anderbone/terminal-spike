package com.yanjiyu.terminalspike.core.data.repository

import com.yanjiyu.terminalspike.core.data.credential.ClearCredentialSecretResult
import com.yanjiyu.terminalspike.core.data.credential.CredentialAggregateMutations
import com.yanjiyu.terminalspike.core.data.credential.DeleteCredentialMetadataResult
import com.yanjiyu.terminalspike.core.data.db.HostProfileDao
import com.yanjiyu.terminalspike.core.data.db.CustomTerminalThemeDao
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileDao
import com.yanjiyu.terminalspike.core.data.db.KnownHostConditionalReplacementResult
import com.yanjiyu.terminalspike.core.data.db.KnownHostDao
import com.yanjiyu.terminalspike.core.data.db.KnownHostSaveResult
import com.yanjiyu.terminalspike.core.data.db.KnownHostVerificationResult
import com.yanjiyu.terminalspike.core.data.db.RecentSessionDao
import com.yanjiyu.terminalspike.core.data.db.SnippetDao
import com.yanjiyu.terminalspike.core.data.db.SshCredentialDao
import com.yanjiyu.terminalspike.core.data.db.SshKeyIdentityDao
import com.yanjiyu.terminalspike.core.data.db.TerminalProfileDao
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.CustomTerminalTheme
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.KnownHost
import com.yanjiyu.terminalspike.core.model.RecentHostActivity
import com.yanjiyu.terminalspike.core.model.RecentSession
import com.yanjiyu.terminalspike.core.model.SessionState
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import com.yanjiyu.terminalspike.core.model.SshKeyIdentity
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.core.security.credential.CredentialId
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Metadata-only read surface plus aggregate-safe mutation paths for SSH authentication choices. */
class SshCredentialRepository(
    private val dao: SshCredentialDao,
    private val mutations: CredentialAggregateMutations,
) {
    fun observeAll(): Flow<List<SshCredential>> = dao.observeAll().mapDomainRows {
        it.toDomainModel()
    }

    suspend fun get(id: String): SshCredential? = dao.findById(id)?.toDomainModel()

    /**
     * Saves prompt-only metadata or edits an existing row without changing its secret binding.
     * Use [saveWithSecret] to add or replace a saved response/passphrase.
     */
    suspend fun saveMetadata(credential: SshCredential) {
        mutations.saveCredentialMetadata(credential.toEntity())
    }

    /** Consumes and wipes [secret] on every exit. Plaintext is never returned by this repository. */
    suspend fun saveWithSecret(credential: SshCredential, secret: ByteArray) {
        try {
            mutations.saveCredentialAndSecret(
                reference = credential.secretReferenceForWrite(),
                secret = secret,
                credential = credential.toEntity(),
            )
        } finally {
            secret.fill(0)
        }
    }

    suspend fun clearSavedSecret(id: String): ClearCredentialSecretResult =
        mutations.clearCredentialSecretAndDeleteIfUnreferenced(CredentialId.parseCanonical(id))

    suspend fun delete(id: String): DeleteCredentialMetadataResult =
        mutations.deleteCredentialAndUnreferencedSecret(CredentialId.parseCanonical(id))
}

/** Metadata-only read surface plus aggregate-safe writes for encrypted SSH key identities. */
class SshKeyIdentityRepository(
    private val dao: SshKeyIdentityDao,
    private val mutations: CredentialAggregateMutations,
) {
    fun observeAll(): Flow<List<SshKeyIdentity>> = dao.observeAll().mapDomainRows {
        it.toDomainModel()
    }

    suspend fun get(id: String): SshKeyIdentity? = dao.findById(id)?.toDomainModel()

    /** Consumes and wipes [privateKey] on every exit. */
    suspend fun saveWithPrivateKey(identity: SshKeyIdentity, privateKey: ByteArray) {
        try {
            mutations.saveIdentityAndPrivateKey(
                reference = CredentialSecretReference(
                    credentialId = CredentialId.parseCanonical(identity.id),
                    secretId = SecretId.parseCanonical(identity.privateKeySecretReferenceId),
                    kind = CredentialSecretKind.PRIVATE_KEY,
                ),
                privateKey = privateKey,
                identity = identity.toEntity(),
            )
        } finally {
            privateKey.fill(0)
        }
    }

    /** Updates public metadata only; the existing private-key reference must remain unchanged. */
    suspend fun updateMetadata(identity: SshKeyIdentity): Boolean =
        mutations.updateIdentityMetadata(identity.toEntity())

    suspend fun delete(id: String): DeleteCredentialMetadataResult =
        mutations.deleteIdentityAndUnreferencedPrivateSecret(CredentialId.parseCanonical(id))
}

class HostProfileRepository(private val dao: HostProfileDao) {
    fun observeAll(): Flow<List<HostProfile>> = dao.observeAll().mapDomainRows { it.toDomainModel() }

    fun observeFavorites(): Flow<List<HostProfile>> =
        dao.observeFavorites().mapDomainRows { it.toDomainModel() }

    suspend fun get(id: String): HostProfile? = dao.findById(id)?.toDomainModel()

    suspend fun insert(profile: HostProfile) = dao.insert(profile.toEntity())

    suspend fun update(profile: HostProfile): Boolean = dao.update(profile.toEntity()) == 1

    suspend fun delete(id: String): Boolean = dao.deleteById(id) == 1
}

class TerminalProfileRepository(private val dao: TerminalProfileDao) {
    fun observeAll(): Flow<List<TerminalProfile>> = dao.observeAll().mapDomainRows { it.toDomainModel() }

    suspend fun get(id: String): TerminalProfile? = dao.findById(id)?.toDomainModel()

    suspend fun insert(profile: TerminalProfile) = dao.insert(profile.toEntity())

    suspend fun update(profile: TerminalProfile): Boolean = dao.update(profile.toEntity()) == 1
}

class CustomTerminalThemeRepository(private val dao: CustomTerminalThemeDao) {
    fun observeAll(): Flow<List<CustomTerminalTheme>> = dao.observeAll().mapDomainRows {
        it.toDomainModel()
    }

    suspend fun get(id: String): CustomTerminalTheme? = dao.findById(id)?.toDomainModel()

    suspend fun insert(theme: CustomTerminalTheme) = dao.insert(theme.toEntity())

    suspend fun update(theme: CustomTerminalTheme): Boolean = dao.update(theme.toEntity()) == 1

    suspend fun deleteAndResetProfiles(
        id: String,
        fallbackThemeId: String,
        updatedAtEpochMillis: Long,
    ): Boolean = dao.deleteAndResetProfiles(id, fallbackThemeId, updatedAtEpochMillis) == 1
}

class KeyboardProfileRepository(private val dao: KeyboardProfileDao) {
    fun observeAll(): Flow<List<KeyboardProfile>> =
        dao.observeAllWithKeys().mapDomainRows { it.toDomainModel() }

    suspend fun get(id: String): KeyboardProfile? = dao.findWithKeys(id)?.toDomainModel()

    suspend fun insert(profile: KeyboardProfile) {
        val rows = profile.toRows()
        dao.insertWithKeys(rows.profile, rows.keys)
    }

    suspend fun update(profile: KeyboardProfile): Boolean {
        val rows = profile.toRows()
        return dao.updateWithKeys(rows.profile, rows.keys) == 1
    }
}

sealed interface KnownHostCheck {
    data object UnknownEndpoint : KnownHostCheck

    data class Trusted(val knownHost: KnownHost) : KnownHostCheck

    data class Mismatch(val trustedKeys: List<KnownHost>) : KnownHostCheck
}

sealed interface KnownHostSave {
    data class Saved(val knownHost: KnownHost) : KnownHostSave

    data class AlreadyTrusted(val knownHost: KnownHost) : KnownHostSave

    data class Conflict(val trustedKeys: List<KnownHost>) : KnownHostSave
}

sealed interface KnownHostConditionalReplacement {
    data class Replaced(val replacement: KnownHostReplacement) : KnownHostConditionalReplacement

    data class Stale(val trustedKeys: List<KnownHost>) : KnownHostConditionalReplacement
}

data class KnownHostReplacement(
    val removedKeyCount: Int,
    val knownHost: KnownHost,
)

class KnownHostRepository(private val dao: KnownHostDao) {
    fun observeAll(): Flow<List<KnownHost>> = dao.observeAll().mapDomainRows { it.toDomainModel() }

    suspend fun getEndpoint(host: String, port: Int): List<KnownHost> =
        dao.findForEndpoint(host, port).map { it.toDomainModel() }

    /** Metadata lookup only. SSH trust decisions must use [verifyAndRecordSeen]. */
    suspend fun get(host: String, port: Int, keyAlgorithm: String): KnownHost? =
        dao.find(host, port, keyAlgorithm)?.toDomainModel()

    /** Import/restore primitive. Interactive first-contact trust uses [trustIfUntrusted]. */
    suspend fun insert(knownHost: KnownHost) = dao.insert(knownHost.toEntity())

    suspend fun update(knownHost: KnownHost): Boolean = dao.update(knownHost.toEntity()) == 1

    suspend fun delete(knownHost: KnownHost): Boolean = dao.delete(knownHost.toEntity()) == 1

    suspend fun deleteEndpoint(host: String, port: Int): Int = dao.deleteEndpoint(host, port)

    suspend fun verifyAndRecordSeen(
        presented: KnownHost,
        seenAtEpochMillis: Long,
    ): KnownHostCheck = when (
        val result = dao.verifyAndRecordSeen(presented.toEntity(), seenAtEpochMillis)
    ) {
        KnownHostVerificationResult.UnknownEndpoint -> KnownHostCheck.UnknownEndpoint
        is KnownHostVerificationResult.Trusted ->
            KnownHostCheck.Trusted(result.knownHost.toDomainModel())
        is KnownHostVerificationResult.Mismatch ->
            KnownHostCheck.Mismatch(result.trustedKeys.map { it.toDomainModel() })
    }

    suspend fun trustIfUntrusted(candidate: KnownHost): KnownHostSave = when (
        val result = dao.trustIfUntrusted(candidate.toEntity())
    ) {
        is KnownHostSaveResult.Saved -> KnownHostSave.Saved(result.knownHost.toDomainModel())
        is KnownHostSaveResult.AlreadyTrusted ->
            KnownHostSave.AlreadyTrusted(result.knownHost.toDomainModel())
        is KnownHostSaveResult.Conflict ->
            KnownHostSave.Conflict(result.trustedKeys.map { it.toDomainModel() })
    }

    suspend fun replaceEndpoint(candidate: KnownHost): KnownHostReplacement {
        val result = dao.replaceEndpoint(candidate.toEntity())
        return KnownHostReplacement(
            removedKeyCount = result.removedKeyCount,
            knownHost = result.knownHost.toDomainModel(),
        )
    }

    suspend fun replaceEndpointIfUnchanged(
        candidate: KnownHost,
        expectedTrustedKeys: List<KnownHost>,
    ): KnownHostConditionalReplacement = when (
        val result = dao.replaceEndpointIfUnchanged(
            candidate = candidate.toEntity(),
            expectedTrustedKeys = expectedTrustedKeys.map { it.toEntity() },
        )
    ) {
        is KnownHostConditionalReplacementResult.Replaced ->
            KnownHostConditionalReplacement.Replaced(
                KnownHostReplacement(
                    removedKeyCount = result.replacement.removedKeyCount,
                    knownHost = result.replacement.knownHost.toDomainModel(),
                ),
            )
        is KnownHostConditionalReplacementResult.Stale ->
            KnownHostConditionalReplacement.Stale(
                result.trustedKeys.map { it.toDomainModel() },
            )
    }
}

class SnippetRepository(private val dao: SnippetDao) {
    fun observeAll(): Flow<List<Snippet>> = dao.observeAll().mapDomainRows { it.toDomainModel() }

    suspend fun get(id: String): Snippet? = dao.findById(id)?.toDomainModel()

    suspend fun insert(snippet: Snippet) = dao.insert(snippet.toEntity())

    suspend fun update(snippet: Snippet): Boolean = dao.update(snippet.toEntity()) == 1

    suspend fun delete(id: String): Boolean = dao.deleteById(id) == 1
}

class RecentSessionRepository(private val dao: RecentSessionDao) {
    fun observeAll(limit: Int = Int.MAX_VALUE): Flow<List<RecentSession>> {
        require(limit > 0) { "Recent-session limit must be positive." }
        return dao.observeRecent(limit).mapDomainRows { it.toDomainModel() }
    }

    fun observeActive(): Flow<List<RecentSession>> = dao.observeActive(ACTIVE_STATE_CODES)
        .mapDomainRows { it.toDomainModel() }

    fun observeEnded(limit: Int = DEFAULT_RECENT_LIMIT): Flow<List<RecentSession>> {
        require(limit > 0) { "Recent-session limit must be positive." }
        return dao.observeEndedRecent(limit).mapDomainRows { it.toDomainModel() }
    }

    /**
     * Observes a bounded, deterministic ranking of retained hosts by their newest persisted
     * activity across both active and ended sessions. Deleted-host history is not returned.
     */
    fun observeRecentHostActivity(
        limit: Int = DEFAULT_RECENT_HOST_LIMIT,
    ): Flow<List<RecentHostActivity>> {
        require(limit > 0) { "Recent-host activity limit must be positive." }
        return dao.observeRecentHostActivity(limit).mapDomainRows { row ->
            RecentHostActivity(
                hostProfileId = row.hostProfileId,
                lastActivityAtEpochMillis = row.lastActivityAtEpochMillis,
            )
        }
    }

    suspend fun get(id: String): RecentSession? = dao.findById(id)?.toDomainModel()

    suspend fun insert(session: RecentSession) = dao.insert(session.toEntity())

    suspend fun update(session: RecentSession): Boolean = dao.update(session.toEntity()) == 1

    suspend fun upsert(session: RecentSession) = dao.upsert(session.toEntity())

    suspend fun delete(id: String): Boolean = dao.deleteById(id) == 1

    suspend fun deleteEndedBefore(cutoffEpochMillis: Long): Int = dao.deleteEndedBefore(cutoffEpochMillis)

    /**
     * A Room row cannot prove that a process-owned socket survived process death. Reconcile only a
     * bounded batch on startup; a later startup will continue if legacy/corrupt data exceeded it.
     */
    suspend fun reconcileStaleActive(
        nowEpochMillis: Long,
        limit: Int = MAX_STALE_RECONCILIATION_ROWS,
    ): Int {
        require(nowEpochMillis >= 0) { "Reconciliation time must not be negative." }
        require(limit > 0) { "Stale-session reconciliation limit must be positive." }
        val stale = dao.findActive(ACTIVE_STATE_CODES, limit)
        stale.forEach { row ->
            val session = row.toDomainModel()
            dao.upsert(
                session.copy(
                    state = SessionState.DISCONNECTED,
                    endedAtEpochMillis = maxOf(nowEpochMillis, session.lastActivityAtEpochMillis),
                ).toEntity(),
            )
        }
        return stale.size
    }

    private companion object {
        const val DEFAULT_RECENT_LIMIT = 8
        const val DEFAULT_RECENT_HOST_LIMIT = 64
        const val MAX_STALE_RECONCILIATION_ROWS = 64
        val ACTIVE_STATE_CODES = SessionState.entries.filter(SessionState::isActive).map { it.wireCode }
    }
}

private fun SshCredential.secretReferenceForWrite(): CredentialSecretReference {
    val (secretReferenceId, secretKind) = when (val authentication = authentication) {
        is SshAuthentication.Password ->
            authentication.secretReferenceId to CredentialSecretKind.PASSWORD
        is SshAuthentication.PrivateKey ->
            authentication.passphraseSecretReferenceId to CredentialSecretKind.KEY_PASSPHRASE
        is SshAuthentication.KeyboardInteractive ->
            authentication.reusableResponseSecretReferenceId to
                CredentialSecretKind.KEYBOARD_INTERACTIVE
    }
    val requiredSecretId = secretReferenceId ?: throw InvalidRepositoryInputException(
        recordType = RepositoryRecordType.SSH_CREDENTIAL,
        recordKey = id,
        cause = IllegalArgumentException(
            "A saved ${authentication.kind.wireCode} secret requires an opaque secret reference ID.",
        ),
    )
    return CredentialSecretReference(
        credentialId = CredentialId.parseCanonical(id),
        secretId = SecretId.parseCanonical(requiredSecretId),
        kind = secretKind,
    )
}

private fun <Entity, Domain> Flow<List<Entity>>.mapDomainRows(
    mapper: (Entity) -> Domain,
): Flow<List<Domain>> = map { rows -> rows.map(mapper) }
