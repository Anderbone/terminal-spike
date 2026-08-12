package com.yanjiyu.terminalspike.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * Backup-only write surface. The coordinator owns validation, ordering, and the outer Room
 * transaction; these methods deliberately expose no plaintext credential API.
 */
data class BackupImportRows(
    val terminalProfiles: List<TerminalProfileEntity> = emptyList(),
    val customTerminalThemes: List<CustomTerminalThemeEntity> = emptyList(),
    val keyboardProfiles: List<KeyboardProfileEntity> = emptyList(),
    val keyboardKeys: List<KeyboardProfileKeyEntity> = emptyList(),
    val secrets: List<EncryptedSecretEntity> = emptyList(),
    val identities: List<SshKeyIdentityEntity> = emptyList(),
    val credentials: List<SshCredentialEntity> = emptyList(),
    val hosts: List<HostProfileEntity> = emptyList(),
    val knownHosts: List<KnownHostEntity> = emptyList(),
    val snippets: List<SnippetEntity> = emptyList(),
    val knownHostIdsToDelete: Set<String> = emptySet(),
    val possiblyOrphanedSecretIds: Set<String> = emptySet(),
    /** Populated only when restoring an interrupted destructive import's internal snapshot. */
    val exactRecoveryDeletes: BackupImportExactDeletes = BackupImportExactDeletes(),
)

data class BackupImportExactDeletes(
    val hostIds: Set<String> = emptySet(),
    val credentialIds: Set<String> = emptySet(),
    val identityIds: Set<String> = emptySet(),
    val terminalProfileIds: Set<String> = emptySet(),
    val customTerminalThemeIds: Set<String> = emptySet(),
    val keyboardProfileIds: Set<String> = emptySet(),
    val knownHostIds: Set<String> = emptySet(),
    val snippetIds: Set<String> = emptySet(),
    val secretIds: Set<String> = emptySet(),
)

@Dao
abstract class BackupImportDao {
    @Upsert
    protected abstract suspend fun upsertTerminalProfiles(rows: List<TerminalProfileEntity>)

    @Upsert
    protected abstract suspend fun upsertCustomTerminalThemes(rows: List<CustomTerminalThemeEntity>)

    @Upsert
    protected abstract suspend fun upsertKeyboardProfiles(rows: List<KeyboardProfileEntity>)

    @Query("DELETE FROM keyboard_profile_keys WHERE profile_id = :profileId")
    protected abstract suspend fun deleteKeyboardKeys(profileId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertKeyboardKeys(rows: List<KeyboardProfileKeyEntity>)

    @Upsert
    protected abstract suspend fun upsertSecrets(rows: List<EncryptedSecretEntity>)

    @Upsert
    protected abstract suspend fun upsertIdentities(rows: List<SshKeyIdentityEntity>)

    @Upsert
    protected abstract suspend fun upsertCredentials(rows: List<SshCredentialEntity>)

    @Upsert
    protected abstract suspend fun upsertHosts(rows: List<HostProfileEntity>)

    @Upsert
    protected abstract suspend fun upsertKnownHosts(rows: List<KnownHostEntity>)

    @Upsert
    protected abstract suspend fun upsertSnippets(rows: List<SnippetEntity>)

    @Query("DELETE FROM known_hosts WHERE id = :id")
    protected abstract suspend fun deleteKnownHost(id: String): Int

    @Query("DELETE FROM host_profiles WHERE id IN (:ids)")
    protected abstract suspend fun deleteHosts(ids: List<String>): Int

    @Query("DELETE FROM ssh_credentials WHERE id IN (:ids)")
    protected abstract suspend fun deleteCredentials(ids: List<String>): Int

    @Query("DELETE FROM ssh_key_identities WHERE id IN (:ids)")
    protected abstract suspend fun deleteIdentities(ids: List<String>): Int

    @Query("DELETE FROM terminal_profiles WHERE id IN (:ids)")
    protected abstract suspend fun deleteTerminalProfiles(ids: List<String>): Int

    @Query("DELETE FROM custom_terminal_themes WHERE id IN (:ids)")
    protected abstract suspend fun deleteCustomTerminalThemes(ids: List<String>): Int

    @Query("DELETE FROM keyboard_profiles WHERE id IN (:ids)")
    protected abstract suspend fun deleteKeyboardProfiles(ids: List<String>): Int

    @Query("DELETE FROM known_hosts WHERE id IN (:ids)")
    protected abstract suspend fun deleteKnownHosts(ids: List<String>): Int

    @Query("DELETE FROM snippets WHERE id IN (:ids)")
    protected abstract suspend fun deleteSnippets(ids: List<String>): Int

    @Query(
        "DELETE FROM encrypted_secrets WHERE id IN (:ids) " +
            "AND id NOT IN (SELECT secret_id FROM ssh_credentials WHERE secret_id IS NOT NULL) " +
            "AND id NOT IN (SELECT private_secret_id FROM ssh_key_identities)",
    )
    protected abstract suspend fun deleteRecoverySecrets(ids: List<String>): Int

    @Query(
        "DELETE FROM encrypted_secrets WHERE id IN (:ids) " +
            "AND id NOT IN (SELECT secret_id FROM ssh_credentials WHERE secret_id IS NOT NULL) " +
            "AND id NOT IN (SELECT private_secret_id FROM ssh_key_identities)",
    )
    protected abstract suspend fun deleteUnreferencedSecrets(ids: List<String>): Int

    /**
     * Applies a fully validated, ciphertext-only batch. This method is safe to nest in the
     * coordinator's outer transaction, where stale-state verification and DataStore coordination
     * happen before the Room commit becomes visible.
     */
    @Transaction
    open suspend fun applyRows(rows: BackupImportRows) {
        rows.knownHostIdsToDelete.sorted().forEach { deleteKnownHost(it) }
        if (rows.secrets.isNotEmpty()) upsertSecrets(rows.secrets)
        if (rows.customTerminalThemes.isNotEmpty()) {
            upsertCustomTerminalThemes(rows.customTerminalThemes)
        }
        if (rows.terminalProfiles.isNotEmpty()) upsertTerminalProfiles(rows.terminalProfiles)
        if (rows.keyboardProfiles.isNotEmpty()) upsertKeyboardProfiles(rows.keyboardProfiles)
        rows.keyboardProfiles.map { it.id }.sorted().forEach { deleteKeyboardKeys(it) }
        if (rows.keyboardKeys.isNotEmpty()) insertKeyboardKeys(rows.keyboardKeys)
        if (rows.identities.isNotEmpty()) upsertIdentities(rows.identities)
        if (rows.credentials.isNotEmpty()) upsertCredentials(rows.credentials)
        if (rows.hosts.isNotEmpty()) upsertHosts(rows.hosts)

        val exact = rows.exactRecoveryDeletes
        if (exact.hostIds.isNotEmpty()) deleteHosts(exact.hostIds.sorted())
        if (exact.credentialIds.isNotEmpty()) deleteCredentials(exact.credentialIds.sorted())
        if (exact.identityIds.isNotEmpty()) deleteIdentities(exact.identityIds.sorted())
        if (exact.terminalProfileIds.isNotEmpty()) {
            deleteTerminalProfiles(exact.terminalProfileIds.sorted())
        }
        if (exact.customTerminalThemeIds.isNotEmpty()) {
            deleteCustomTerminalThemes(exact.customTerminalThemeIds.sorted())
        }
        if (exact.keyboardProfileIds.isNotEmpty()) {
            deleteKeyboardProfiles(exact.keyboardProfileIds.sorted())
        }
        if (exact.knownHostIds.isNotEmpty()) deleteKnownHosts(exact.knownHostIds.sorted())
        if (exact.snippetIds.isNotEmpty()) deleteSnippets(exact.snippetIds.sorted())

        if (rows.knownHosts.isNotEmpty()) upsertKnownHosts(rows.knownHosts)
        if (rows.snippets.isNotEmpty()) upsertSnippets(rows.snippets)
        if (exact.secretIds.isNotEmpty()) deleteRecoverySecrets(exact.secretIds.sorted())
        if (rows.possiblyOrphanedSecretIds.isNotEmpty()) {
            deleteUnreferencedSecrets(rows.possiblyOrphanedSecretIds.sorted())
        }
    }
}
