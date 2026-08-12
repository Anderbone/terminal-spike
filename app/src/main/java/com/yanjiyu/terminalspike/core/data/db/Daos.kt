package com.yanjiyu.terminalspike.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TerminalProfileDao {
    @Query("SELECT * FROM terminal_profiles ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<TerminalProfileEntity>>

    @Query("SELECT * FROM terminal_profiles WHERE id = :id")
    suspend fun findById(id: String): TerminalProfileEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: TerminalProfileEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(profile: TerminalProfileEntity): Int

    @Query("DELETE FROM terminal_profiles WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

@Dao
abstract class CustomTerminalThemeDao {
    @Query("SELECT * FROM custom_terminal_themes ORDER BY name COLLATE NOCASE, id")
    abstract fun observeAll(): Flow<List<CustomTerminalThemeEntity>>

    @Query("SELECT * FROM custom_terminal_themes WHERE id = :id")
    abstract suspend fun findById(id: String): CustomTerminalThemeEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(theme: CustomTerminalThemeEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun update(theme: CustomTerminalThemeEntity): Int

    @Query(
        "UPDATE terminal_profiles SET theme_id = :fallbackThemeId, " +
            "updated_at_epoch_millis = MAX(updated_at_epoch_millis, :updatedAtEpochMillis) " +
            "WHERE theme_id = :themeId",
    )
    protected abstract suspend fun resetProfileReferences(
        themeId: String,
        fallbackThemeId: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM custom_terminal_themes WHERE id = :id")
    protected abstract suspend fun deleteById(id: String): Int

    @Transaction
    open suspend fun deleteAndResetProfiles(
        id: String,
        fallbackThemeId: String,
        updatedAtEpochMillis: Long,
    ): Int {
        resetProfileReferences(id, fallbackThemeId, updatedAtEpochMillis)
        return deleteById(id)
    }
}

data class KeyboardProfileWithKeys(
    @Embedded
    val profile: KeyboardProfileEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "profile_id",
    )
    val keys: List<KeyboardProfileKeyEntity>,
)

@Dao
abstract class KeyboardProfileDao {
    @Query("SELECT * FROM keyboard_profiles ORDER BY name COLLATE NOCASE, id")
    abstract fun observeAll(): Flow<List<KeyboardProfileEntity>>

    @Transaction
    @Query("SELECT * FROM keyboard_profiles ORDER BY name COLLATE NOCASE, id")
    abstract fun observeAllWithKeys(): Flow<List<KeyboardProfileWithKeys>>

    @Query("SELECT * FROM keyboard_profiles WHERE id = :id")
    abstract suspend fun findById(id: String): KeyboardProfileEntity?

    @Query("SELECT * FROM keyboard_profile_keys WHERE profile_id = :profileId ORDER BY position")
    abstract suspend fun findKeys(profileId: String): List<KeyboardProfileKeyEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(profile: KeyboardProfileEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertKeys(keys: List<KeyboardProfileKeyEntity>)

    @Update(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun update(profile: KeyboardProfileEntity): Int

    @Query("DELETE FROM keyboard_profile_keys WHERE profile_id = :profileId")
    abstract suspend fun deleteKeys(profileId: String): Int

    @Query("DELETE FROM keyboard_profiles WHERE id = :id")
    abstract suspend fun deleteById(id: String): Int

    @Transaction
    @Query("SELECT * FROM keyboard_profiles WHERE id = :id")
    abstract suspend fun findWithKeys(id: String): KeyboardProfileWithKeys?

    @Transaction
    open suspend fun insertWithKeys(
        profile: KeyboardProfileEntity,
        keys: List<KeyboardProfileKeyEntity>,
    ) {
        require(keys.all { it.profileId == profile.id }) {
            "Every keyboard key must reference the inserted profile"
        }
        insert(profile)
        if (keys.isNotEmpty()) insertKeys(keys)
    }

    @Transaction
    open suspend fun updateWithKeys(
        profile: KeyboardProfileEntity,
        keys: List<KeyboardProfileKeyEntity>,
    ): Int {
        require(keys.all { it.profileId == profile.id }) {
            "Every replacement key must reference the updated profile"
        }
        val updated = update(profile)
        if (updated == 0) return 0
        deleteKeys(profile.id)
        if (keys.isNotEmpty()) insertKeys(keys)
        return updated
    }

    @Transaction
    open suspend fun replaceKeys(
        profileId: String,
        keys: List<KeyboardProfileKeyEntity>,
    ) {
        require(keys.all { it.profileId == profileId }) {
            "Every replacement key must reference the requested profile"
        }
        deleteKeys(profileId)
        if (keys.isNotEmpty()) insertKeys(keys)
    }
}

/**
 * The only DAO that reads encrypted payload rows. Application callers keep it behind
 * CredentialStore so ciphertext and decryption state never leak into UI repositories.
 */
@Dao
abstract class CredentialRecordDao {
    @Query("SELECT * FROM encrypted_secrets WHERE id = :id")
    abstract suspend fun findSecretById(id: String): EncryptedSecretEntity?

    @Query("SELECT id FROM encrypted_secrets WHERE state_code = :stateCode ORDER BY id")
    abstract suspend fun findSecretIdsByState(stateCode: String): List<String>

    @Query(
        "SELECT COUNT(*) FROM encrypted_secrets " +
            "WHERE state_code NOT IN (:readyStateCode, :unavailableStateCode)",
    )
    abstract suspend fun countSecretsWithUnknownState(
        readyStateCode: String,
        unavailableStateCode: String,
    ): Int

    @Query("SELECT * FROM ssh_credentials WHERE id = :id")
    abstract suspend fun findCredentialById(id: String): SshCredentialEntity?

    @Query("SELECT * FROM ssh_key_identities WHERE id = :id")
    abstract suspend fun findIdentityById(id: String): SshKeyIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSecret(secret: EncryptedSecretEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun updateSecret(secret: EncryptedSecretEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertIdentity(identity: SshKeyIdentityEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun updateIdentity(identity: SshKeyIdentityEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertCredential(credential: SshCredentialEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun updateCredential(credential: SshCredentialEntity): Int

    @Query("DELETE FROM ssh_key_identities WHERE id = :id")
    abstract suspend fun deleteIdentityById(id: String): Int

    @Query("DELETE FROM ssh_credentials WHERE id = :id")
    abstract suspend fun deleteCredentialById(id: String): Int

    @Query("SELECT COUNT(*) FROM ssh_credentials WHERE secret_id = :secretId")
    abstract suspend fun countCredentialSecretReferences(secretId: String): Int

    @Query("SELECT COUNT(*) FROM ssh_key_identities WHERE private_secret_id = :secretId")
    abstract suspend fun countIdentitySecretReferences(secretId: String): Int

    @Query("SELECT id FROM ssh_credentials WHERE secret_id = :secretId LIMIT 1")
    abstract suspend fun findCredentialOwnerOfSecret(secretId: String): String?

    @Query("SELECT id FROM ssh_key_identities WHERE private_secret_id = :secretId LIMIT 1")
    abstract suspend fun findIdentityOwnerOfSecret(secretId: String): String?

    @Query("DELETE FROM encrypted_secrets WHERE id = :id")
    abstract suspend fun deleteSecretById(id: String): Int

    @Transaction
    open suspend fun insertIdentityWithSecret(
        secret: EncryptedSecretEntity,
        identity: SshKeyIdentityEntity,
    ) {
        require(identity.privateSecretId == secret.id) {
            "The identity must reference the secret inserted in the same transaction"
        }
        insertSecret(secret)
        insertIdentity(identity)
    }

    @Transaction
    open suspend fun insertCredentialWithSecret(
        secret: EncryptedSecretEntity,
        credential: SshCredentialEntity,
    ) {
        require(credential.secretId == secret.id) {
            "The credential must reference the secret inserted in the same transaction"
        }
        insertSecret(secret)
        insertCredential(credential)
    }
}

/** Narrow write surface used only by the one-transaction clear-all credential operation. */
@Dao
interface CredentialBulkClearDao {
    @Query("SELECT COUNT(*) FROM ssh_credentials")
    suspend fun countCredentials(): Int

    @Query("SELECT COUNT(*) FROM ssh_key_identities")
    suspend fun countKeyIdentities(): Int

    @Query("UPDATE host_profiles SET credential_id = NULL WHERE credential_id IS NOT NULL")
    suspend fun detachHostCredentialReferences(): Int

    @Query("DELETE FROM ssh_credentials")
    suspend fun deleteAllCredentials(): Int

    @Query("DELETE FROM ssh_key_identities")
    suspend fun deleteAllKeyIdentities(): Int

    @Query(
        "DELETE FROM encrypted_secrets " +
            "WHERE id NOT IN (SELECT secret_id FROM ssh_credentials WHERE secret_id IS NOT NULL) " +
            "AND id NOT IN (SELECT private_secret_id FROM ssh_key_identities)",
    )
    suspend fun deleteAllUnreferencedSecrets(): Int
}

@Dao
interface SshKeyIdentityDao {
    @Query("SELECT * FROM ssh_key_identities ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<SshKeyIdentityEntity>>

    @Query("SELECT * FROM ssh_key_identities WHERE id = :id")
    suspend fun findById(id: String): SshKeyIdentityEntity?

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(identity: SshKeyIdentityEntity): Int

    @Query("DELETE FROM ssh_key_identities WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

@Dao
interface SshCredentialDao {
    @Query("SELECT * FROM ssh_credentials ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<SshCredentialEntity>>

    @Query("SELECT * FROM ssh_credentials WHERE id = :id")
    suspend fun findById(id: String): SshCredentialEntity?

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(credential: SshCredentialEntity): Int

    @Query("DELETE FROM ssh_credentials WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

@Dao
interface HostProfileDao {
    @Query(
        "SELECT * FROM host_profiles " +
            "ORDER BY is_favorite DESC, display_name COLLATE NOCASE, id",
    )
    fun observeAll(): Flow<List<HostProfileEntity>>

    @Query("SELECT * FROM host_profiles WHERE id = :id")
    suspend fun findById(id: String): HostProfileEntity?

    @Query("SELECT COUNT(*) FROM host_profiles WHERE credential_id = :credentialId")
    suspend fun countCredentialReferences(credentialId: String): Int

    @Query(
        "SELECT * FROM host_profiles WHERE is_favorite = 1 " +
            "ORDER BY display_name COLLATE NOCASE, id",
    )
    fun observeFavorites(): Flow<List<HostProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(host: HostProfileEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(host: HostProfileEntity): Int

    @Query("DELETE FROM host_profiles WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

/**
 * Display-safe aggregate used to rank saved hosts by persisted terminal activity.
 *
 * The projection intentionally contains only the retained host ID and activity timestamp. Endpoint
 * fields and credential references never cross this query boundary.
 */
data class RecentHostActivityRow(
    @ColumnInfo(name = "host_profile_id")
    val hostProfileId: String,
    @ColumnInfo(name = "last_activity_at_epoch_millis")
    val lastActivityAtEpochMillis: Long,
)

/** One transient plaintext join used only to backfill an HMAC token, never exposed to UI/logs. */
data class RecentEndpointIdentityBackfillRow(
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "protocol_code")
    val protocolCode: String,
    @ColumnInfo(name = "hostname")
    val hostname: String,
    @ColumnInfo(name = "port")
    val port: Int,
    @ColumnInfo(name = "username")
    val username: String,
)

data class RecentEndpointIdentityBackfillUpdate(
    val sessionId: String,
    val endpointIdentityToken: String,
) {
    init {
        require(endpointIdentityToken.length == 64 && endpointIdentityToken.all { character ->
            character in '0'..'9' || character in 'a'..'f'
        }) { "Endpoint identity backfill token must be lowercase HMAC-SHA-256." }
    }
}

data class RecentEndpointIdentityResetResult(
    val endedRowsDeleted: Int,
    val activeTokensCleared: Int,
    val activeTokensReissued: Int = 0,
)

@Dao
abstract class RecentSessionDao {
    @Query(
        "SELECT * FROM recent_sessions " +
            "ORDER BY last_activity_at_epoch_millis DESC, id LIMIT :limit",
    )
    abstract fun observeRecent(limit: Int): Flow<List<RecentSessionEntity>>

    @Query(
        "SELECT * FROM recent_sessions WHERE ended_at_epoch_millis IS NOT NULL " +
            "ORDER BY last_activity_at_epoch_millis DESC, id LIMIT :limit",
    )
    abstract fun observeEndedRecent(limit: Int): Flow<List<RecentSessionEntity>>

    @Query(
        "SELECT * FROM recent_sessions WHERE state_code IN (:activeStateCodes) " +
            "ORDER BY last_activity_at_epoch_millis DESC, id",
    )
    abstract fun observeActive(activeStateCodes: List<String>): Flow<List<RecentSessionEntity>>

    /**
     * Observes one row per retained host reference, newest activity first.
     *
     * Both active and ended sessions participate: the maximum persisted activity timestamp across
     * all of a host's history determines its rank. History detached by host deletion is excluded,
     * and equal timestamps are ordered by canonical host ID so a bounded result is deterministic.
     */
    @Query(
        "SELECT host_profile_id, " +
            "MAX(last_activity_at_epoch_millis) AS last_activity_at_epoch_millis " +
            "FROM recent_sessions WHERE host_profile_id IS NOT NULL " +
            "GROUP BY host_profile_id " +
            "ORDER BY last_activity_at_epoch_millis DESC, host_profile_id COLLATE BINARY ASC " +
            "LIMIT :limit",
    )
    abstract fun observeRecentHostActivity(limit: Int): Flow<List<RecentHostActivityRow>>

    @Query(
        "SELECT * FROM recent_sessions WHERE state_code IN (:activeStateCodes) " +
            "ORDER BY last_activity_at_epoch_millis DESC, id LIMIT :limit",
    )
    abstract suspend fun findActive(
        activeStateCodes: List<String>,
        limit: Int,
    ): List<RecentSessionEntity>

    @Query("SELECT * FROM recent_sessions WHERE id = :id")
    abstract suspend fun findById(id: String): RecentSessionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRow(session: RecentSessionEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun updateRow(session: RecentSessionEntity): Int

    @Upsert
    protected abstract suspend fun upsertRow(session: RecentSessionEntity)

    @Query(
        "DELETE FROM recent_sessions " +
            "WHERE endpoint_identity_token = :endpointIdentityToken " +
            "AND ended_at_epoch_millis IS NOT NULL " +
            "AND id != (" +
            "SELECT id FROM recent_sessions " +
            "WHERE endpoint_identity_token = :endpointIdentityToken " +
            "AND ended_at_epoch_millis IS NOT NULL " +
            "ORDER BY last_activity_at_epoch_millis DESC, " +
            "ended_at_epoch_millis DESC, started_at_epoch_millis DESC, id DESC LIMIT 1" +
            ")",
    )
    protected abstract suspend fun deleteOlderEndedForIdentity(endpointIdentityToken: String): Int

    @Transaction
    open suspend fun insert(session: RecentSessionEntity) {
        insertRow(session)
        session.pruneEndedIdentityIfNeeded()
    }

    @Transaction
    open suspend fun update(session: RecentSessionEntity): Int {
        val updated = updateRow(session)
        if (updated == 1) session.pruneEndedIdentityIfNeeded()
        return updated
    }

    @Transaction
    open suspend fun upsert(session: RecentSessionEntity) {
        upsertRow(session)
        session.pruneEndedIdentityIfNeeded()
    }

    @Query(
        "SELECT recent_sessions.id AS session_id, " +
            "recent_sessions.protocol_code AS protocol_code, " +
            "host_profiles.hostname AS hostname, host_profiles.port AS port, " +
            "host_profiles.username AS username " +
            "FROM recent_sessions INNER JOIN host_profiles " +
            "ON recent_sessions.host_profile_id = host_profiles.id " +
            "WHERE recent_sessions.endpoint_identity_token IS NULL " +
            "ORDER BY recent_sessions.last_activity_at_epoch_millis DESC, recent_sessions.id " +
            "LIMIT :limit",
    )
    abstract suspend fun findEndpointIdentityBackfillRows(
        limit: Int,
    ): List<RecentEndpointIdentityBackfillRow>

    /**
     * Counts rows belonging to an already-persisted HMAC generation.
     *
     * Null migration rows do not establish a generation and must survive first key creation.
     */
    @Query(
        "SELECT COUNT(*) FROM recent_sessions " +
            "WHERE endpoint_identity_token IS NOT NULL",
    )
    abstract suspend fun countEndpointIdentityTokens(): Int

    @Query(
        "UPDATE recent_sessions SET endpoint_identity_token = :endpointIdentityToken " +
            "WHERE id = :sessionId AND endpoint_identity_token IS NULL",
    )
    protected abstract suspend fun setEndpointIdentityTokenIfMissing(
        sessionId: String,
        endpointIdentityToken: String,
    ): Int

    @Transaction
    open suspend fun applyEndpointIdentityBackfill(
        updates: List<RecentEndpointIdentityBackfillUpdate>,
    ): Int {
        require(updates.map { it.sessionId }.distinct().size == updates.size) {
            "Endpoint identity backfill session IDs must be unique."
        }
        var changed = 0
        updates.forEach { update ->
            val rowChanged = setEndpointIdentityTokenIfMissing(
                sessionId = update.sessionId,
                endpointIdentityToken = update.endpointIdentityToken,
            )
            check(rowChanged in 0..1) { "Endpoint identity backfill changed an unexpected row count." }
            if (rowChanged == 1) {
                changed += 1
                deleteOlderEndedForIdentity(update.endpointIdentityToken)
            }
        }
        return changed
    }

    @Query("DELETE FROM recent_sessions WHERE ended_at_epoch_millis IS NOT NULL")
    protected abstract suspend fun deleteAllEndedRows(): Int

    @Query(
        "UPDATE recent_sessions SET endpoint_identity_token = NULL " +
            "WHERE ended_at_epoch_millis IS NULL AND endpoint_identity_token IS NOT NULL",
    )
    protected abstract suspend fun clearActiveEndpointIdentityTokens(): Int

    @Query(
        "UPDATE recent_sessions SET endpoint_identity_token = :endpointIdentityToken " +
            "WHERE id = :sessionId AND ended_at_epoch_millis IS NULL",
    )
    protected abstract suspend fun setActiveEndpointIdentityToken(
        sessionId: String,
        endpointIdentityToken: String,
    ): Int

    /**
     * Called after a new device-local HMAC key is established; generations must never mix.
     * A database containing only null migration rows has no old generation to destroy.
     */
    @Transaction
    open suspend fun resetEndpointIdentityGeneration(): RecentEndpointIdentityResetResult {
        if (countEndpointIdentityTokens() == 0) {
            return RecentEndpointIdentityResetResult(
                endedRowsDeleted = 0,
                activeTokensCleared = 0,
            )
        }
        return RecentEndpointIdentityResetResult(
            endedRowsDeleted = deleteAllEndedRows(),
            activeTokensCleared = clearActiveEndpointIdentityTokens(),
        )
    }

    /**
     * Clears an existing old generation and reissues only caller-proved live sessions in one
     * transaction. First key creation is a no-op here so null migration history is preserved for
     * backfill.
     */
    @Transaction
    open suspend fun replaceEndpointIdentityGeneration(
        activeUpdates: List<RecentEndpointIdentityBackfillUpdate>,
    ): RecentEndpointIdentityResetResult {
        require(activeUpdates.map { it.sessionId }.distinct().size == activeUpdates.size) {
            "Active endpoint identity session IDs must be unique."
        }
        if (countEndpointIdentityTokens() == 0) {
            return RecentEndpointIdentityResetResult(
                endedRowsDeleted = 0,
                activeTokensCleared = 0,
            )
        }
        val reset = RecentEndpointIdentityResetResult(
            endedRowsDeleted = deleteAllEndedRows(),
            activeTokensCleared = clearActiveEndpointIdentityTokens(),
        )
        var reissued = 0
        activeUpdates.forEach { update ->
            val changed = setActiveEndpointIdentityToken(
                sessionId = update.sessionId,
                endpointIdentityToken = update.endpointIdentityToken,
            )
            check(changed in 0..1) {
                "Active endpoint identity update changed an unexpected row count."
            }
            reissued += changed
        }
        return reset.copy(activeTokensReissued = reissued)
    }

    @Query(
        "DELETE FROM recent_sessions " +
            "WHERE ended_at_epoch_millis IS NOT NULL AND ended_at_epoch_millis < :cutoffEpochMillis",
    )
    abstract suspend fun deleteEndedBefore(cutoffEpochMillis: Long): Int

    @Query("DELETE FROM recent_sessions WHERE id = :id")
    abstract suspend fun deleteById(id: String): Int

    private suspend fun RecentSessionEntity.pruneEndedIdentityIfNeeded() {
        if (endedAtEpochMillis != null) {
            endpointIdentityToken?.let { deleteOlderEndedForIdentity(it) }
        }
    }
}

/** Result of checking one presented key against every saved key for its host and port. */
sealed interface KnownHostVerificationResult {
    data object UnknownEndpoint : KnownHostVerificationResult

    data class Trusted(val knownHost: KnownHostEntity) : KnownHostVerificationResult

    data class Mismatch(val trustedKeys: List<KnownHostEntity>) : KnownHostVerificationResult
}

/** Result of the compare-and-insert operation used after an explicit trust-and-save choice. */
sealed interface KnownHostSaveResult {
    data class Saved(val knownHost: KnownHostEntity) : KnownHostSaveResult

    data class AlreadyTrusted(val knownHost: KnownHostEntity) : KnownHostSaveResult

    data class Conflict(val trustedKeys: List<KnownHostEntity>) : KnownHostSaveResult
}

data class KnownHostReplacementResult(
    val removedKeyCount: Int,
    val knownHost: KnownHostEntity,
)

sealed interface KnownHostConditionalReplacementResult {
    data class Replaced(val replacement: KnownHostReplacementResult) :
        KnownHostConditionalReplacementResult

    data class Stale(val trustedKeys: List<KnownHostEntity>) :
        KnownHostConditionalReplacementResult
}

@Dao
abstract class KnownHostDao {
    @Query("SELECT * FROM known_hosts ORDER BY host COLLATE NOCASE, port, algorithm_code")
    abstract fun observeAll(): Flow<List<KnownHostEntity>>

    /**
     * Returns the complete endpoint trust set. Trust decisions must never query by algorithm alone:
     * a different algorithm at a known endpoint is a changed host key, not first contact.
     */
    @Query(
        "SELECT * FROM known_hosts WHERE host = :host AND port = :port " +
            "ORDER BY algorithm_code, id",
    )
    abstract suspend fun findForEndpoint(host: String, port: Int): List<KnownHostEntity>

    @Query(
        "SELECT * FROM known_hosts " +
            "WHERE host = :host AND port = :port AND algorithm_code = :algorithmCode",
    )
    abstract suspend fun find(
        host: String,
        port: Int,
        algorithmCode: String,
    ): KnownHostEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(knownHost: KnownHostEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun update(knownHost: KnownHostEntity): Int

    @Delete
    abstract suspend fun delete(knownHost: KnownHostEntity): Int

    @Query("DELETE FROM known_hosts WHERE host = :host AND port = :port")
    protected abstract suspend fun deleteEndpointRows(host: String, port: Int): Int

    /**
     * Checks the whole endpoint and records a successful observation in the same transaction.
     * Full public-key bytes, rather than display fingerprints, are the trust comparison value.
     */
    @Transaction
    open suspend fun verifyAndRecordSeen(
        presented: KnownHostEntity,
        seenAtEpochMillis: Long,
    ): KnownHostVerificationResult {
        requireSupportedTimestamp(seenAtEpochMillis)
        val trustedKeys = findForEndpoint(presented.host, presented.port)
        if (trustedKeys.isEmpty()) return KnownHostVerificationResult.UnknownEndpoint

        val match = trustedKeys.firstOrNull { trusted -> trusted.sameHostKeyAs(presented) }
            ?: return KnownHostVerificationResult.Mismatch(trustedKeys)
        val updated = match.withObservation(seenAtEpochMillis)
        if (updated !== match) {
            check(update(updated) == 1) { "Known-host row disappeared during verification." }
        }
        return KnownHostVerificationResult.Trusted(updated)
    }

    /**
     * Atomically saves a first-contact key only while the complete endpoint is still unknown.
     * Concurrent trust prompts therefore cannot save two different first keys for one endpoint.
     */
    @Transaction
    open suspend fun trustIfUntrusted(candidate: KnownHostEntity): KnownHostSaveResult {
        candidate.requireObservedTrust()
        val trustedKeys = findForEndpoint(candidate.host, candidate.port)
        val match = trustedKeys.firstOrNull { trusted -> trusted.sameHostKeyAs(candidate) }
        if (match != null) {
            val updated = match.withObservation(requireNotNull(candidate.lastSeenAtEpochMillis))
            if (updated !== match) {
                check(update(updated) == 1) { "Known-host row disappeared while saving trust." }
            }
            return KnownHostSaveResult.AlreadyTrusted(updated)
        }
        if (trustedKeys.isNotEmpty()) return KnownHostSaveResult.Conflict(trustedKeys)

        insert(candidate)
        return KnownHostSaveResult.Saved(candidate)
    }

    /** Explicit management-only replacement; a failed insert rolls the endpoint deletion back. */
    @Transaction
    open suspend fun replaceEndpoint(candidate: KnownHostEntity): KnownHostReplacementResult {
        candidate.requireObservedTrust()
        val removed = deleteEndpointRows(candidate.host, candidate.port)
        insert(candidate)
        return KnownHostReplacementResult(removed, candidate)
    }

    /**
     * Replaces an endpoint only while its complete public-key set still matches the snapshot that
     * produced the user's changed-key prompt. Display fingerprints are deliberately not trusted as
     * the comparison value.
     */
    @Transaction
    open suspend fun replaceEndpointIfUnchanged(
        candidate: KnownHostEntity,
        expectedTrustedKeys: List<KnownHostEntity>,
    ): KnownHostConditionalReplacementResult {
        candidate.requireObservedTrust()
        require(expectedTrustedKeys.isNotEmpty()) {
            "Changed-key replacement requires a non-empty expected trust snapshot."
        }
        require(expectedTrustedKeys.all { expected ->
            expected.host == candidate.host && expected.port == candidate.port
        }) {
            "Expected known-host keys must belong to the replacement endpoint."
        }

        val current = findForEndpoint(candidate.host, candidate.port)
        if (!current.sameHostKeySetAs(expectedTrustedKeys)) {
            return KnownHostConditionalReplacementResult.Stale(current)
        }
        val removed = deleteEndpointRows(candidate.host, candidate.port)
        insert(candidate)
        return KnownHostConditionalReplacementResult.Replaced(
            KnownHostReplacementResult(removed, candidate),
        )
    }

    suspend fun deleteEndpoint(host: String, port: Int): Int = deleteEndpointRows(host, port)

    private fun KnownHostEntity.sameHostKeyAs(other: KnownHostEntity): Boolean =
        algorithmCode == other.algorithmCode && publicKey.contentEquals(other.publicKey)

    private fun List<KnownHostEntity>.sameHostKeySetAs(other: List<KnownHostEntity>): Boolean =
        size == other.size && all { key ->
            other.any { expected -> key.sameHostKeyAs(expected) }
        }

    private fun KnownHostEntity.withObservation(seenAtEpochMillis: Long): KnownHostEntity {
        require((firstSeenAtEpochMillis == null) == (lastSeenAtEpochMillis == null)) {
            "Stored known-host observation timestamps are inconsistent."
        }
        val firstSeen = firstSeenAtEpochMillis ?: seenAtEpochMillis
        val lastSeen = maxOf(lastSeenAtEpochMillis ?: seenAtEpochMillis, seenAtEpochMillis)
        if (firstSeen == firstSeenAtEpochMillis && lastSeen == lastSeenAtEpochMillis) return this
        return copy(
            firstSeenAtEpochMillis = firstSeen,
            lastSeenAtEpochMillis = lastSeen,
        )
    }

    private fun KnownHostEntity.requireObservedTrust() {
        val firstSeen = requireNotNull(firstSeenAtEpochMillis) {
            "A newly trusted host key must have a first-seen timestamp."
        }
        val lastSeen = requireNotNull(lastSeenAtEpochMillis) {
            "A newly trusted host key must have a last-seen timestamp."
        }
        requireSupportedTimestamp(firstSeen)
        requireSupportedTimestamp(lastSeen)
        require(lastSeen >= firstSeen) { "Known-host last-seen timestamp precedes first-seen." }
    }

    private fun requireSupportedTimestamp(timestamp: Long) {
        require(timestamp in 0..MAX_SUPPORTED_EPOCH_MILLIS) {
            "Known-host timestamp is outside the supported range."
        }
    }

    private companion object {
        const val MAX_SUPPORTED_EPOCH_MILLIS = 253_402_300_799_999L
    }
}

@Dao
interface SnippetDao {
    @Query(
        "SELECT * FROM snippets " +
            "ORDER BY is_favorite DESC, group_name COLLATE NOCASE, name COLLATE NOCASE, id",
    )
    fun observeAll(): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets WHERE id = :id")
    suspend fun findById(id: String): SnippetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(snippet: SnippetEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(snippet: SnippetEntity): Int

    @Query("DELETE FROM snippets WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

data class LegacyMigrationBatch(
    val terminalProfiles: List<TerminalProfileEntity> = emptyList(),
    val keyboardProfiles: List<KeyboardProfileEntity> = emptyList(),
    val keyboardKeys: List<KeyboardProfileKeyEntity> = emptyList(),
    val secrets: List<EncryptedSecretEntity> = emptyList(),
    val keyIdentities: List<SshKeyIdentityEntity> = emptyList(),
    val credentials: List<SshCredentialEntity> = emptyList(),
    val hosts: List<HostProfileEntity> = emptyList(),
    val knownHosts: List<KnownHostEntity> = emptyList(),
    val snippets: List<SnippetEntity> = emptyList(),
    val completion: LegacyMigrationStateEntity,
)

enum class LegacyMigrationInsertResult {
    INSERTED,
    ALREADY_APPLIED,
}

class LegacyMigrationSourceConflictException(val sourceCode: String) : IllegalStateException(
    "Migration source '$sourceCode' was already recorded with different input or incomplete state.",
)

@Dao
abstract class LegacyMigrationDao {
    @Query("SELECT * FROM legacy_migration_state WHERE source_code = :sourceCode")
    abstract suspend fun findState(sourceCode: String): LegacyMigrationStateEntity?

    @Query("SELECT * FROM legacy_migration_state ORDER BY source_code")
    abstract fun observeStates(): Flow<List<LegacyMigrationStateEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertState(state: LegacyMigrationStateEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun updateState(state: LegacyMigrationStateEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertTerminalProfiles(rows: List<TerminalProfileEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertKeyboardProfiles(rows: List<KeyboardProfileEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertKeyboardKeys(rows: List<KeyboardProfileKeyEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSecrets(rows: List<EncryptedSecretEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertKeyIdentities(rows: List<SshKeyIdentityEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCredentials(rows: List<SshCredentialEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertHosts(rows: List<HostProfileEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertKnownHosts(rows: List<KnownHostEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSnippets(rows: List<SnippetEntity>)

    /**
     * Uses INSERT(ABORT) for every row and records completion only after all rows succeed. A cold
     * restart with any authoritative terminal marker is an idempotent no-op. This intentionally
     * ignores retained or subsequently appearing legacy input once Room owns the source.
     */
    @Transaction
    open suspend fun insertBatch(batch: LegacyMigrationBatch): LegacyMigrationInsertResult {
        val completion = batch.completion
        require(completion.isAuthoritativeTerminal()) {
            "A migration batch must end with a valid authoritative terminal marker."
        }
        val existingState = findState(completion.sourceCode)
        existingState?.let { existing ->
            if (existing.isAuthoritativeTerminal()) {
                return LegacyMigrationInsertResult.ALREADY_APPLIED
            }
            if (!existing.isRetryableAttempt()) {
                throw LegacyMigrationSourceConflictException(completion.sourceCode)
            }
        }

        if (batch.terminalProfiles.isNotEmpty()) insertTerminalProfiles(batch.terminalProfiles)
        if (batch.keyboardProfiles.isNotEmpty()) insertKeyboardProfiles(batch.keyboardProfiles)
        if (batch.keyboardKeys.isNotEmpty()) insertKeyboardKeys(batch.keyboardKeys)
        if (batch.secrets.isNotEmpty()) insertSecrets(batch.secrets)
        if (batch.keyIdentities.isNotEmpty()) insertKeyIdentities(batch.keyIdentities)
        if (batch.credentials.isNotEmpty()) insertCredentials(batch.credentials)
        if (batch.hosts.isNotEmpty()) insertHosts(batch.hosts)
        if (batch.knownHosts.isNotEmpty()) insertKnownHosts(batch.knownHosts)
        if (batch.snippets.isNotEmpty()) insertSnippets(batch.snippets)
        if (existingState == null) {
            insertState(completion)
        } else {
            check(updateState(completion) == 1) {
                "Blocked migration marker disappeared during retry."
            }
        }
        return LegacyMigrationInsertResult.INSERTED
    }
}
