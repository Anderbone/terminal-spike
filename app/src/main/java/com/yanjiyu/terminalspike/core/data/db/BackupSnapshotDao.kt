package com.yanjiyu.terminalspike.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/** Rows captured in one Room read transaction for portable backup export. */
data class BackupSnapshotRows(
    val terminalProfiles: List<TerminalProfileEntity>,
    val keyboardProfiles: List<KeyboardProfileEntity>,
    val keyboardKeys: List<KeyboardProfileKeyEntity>,
    val keyIdentities: List<SshKeyIdentityEntity>,
    val credentials: List<SshCredentialEntity>,
    val hosts: List<HostProfileEntity>,
    val knownHosts: List<KnownHostEntity>,
    val snippets: List<SnippetEntity>,
    /** Exact immutable envelopes used only by the scoped backup decryptor, never by UI code. */
    val encryptedSecrets: List<EncryptedSecretEntity>,
    val customTerminalThemes: List<CustomTerminalThemeEntity> = emptyList(),
)

/**
 * Backup-only aggregate read surface. Ciphertext remains opaque here and can be opened only by the
 * CredentialRecordDecryptor; capturing it alongside metadata prevents mixed-revision exports.
 */
@Dao
abstract class BackupSnapshotDao {
    @Query("SELECT * FROM terminal_profiles ORDER BY id")
    protected abstract suspend fun terminalProfiles(): List<TerminalProfileEntity>

    @Query("SELECT * FROM custom_terminal_themes ORDER BY id")
    protected abstract suspend fun customTerminalThemes(): List<CustomTerminalThemeEntity>

    @Query("SELECT * FROM keyboard_profiles ORDER BY id")
    protected abstract suspend fun keyboardProfiles(): List<KeyboardProfileEntity>

    @Query("SELECT * FROM keyboard_profile_keys ORDER BY profile_id, position")
    protected abstract suspend fun keyboardKeys(): List<KeyboardProfileKeyEntity>

    @Query("SELECT * FROM ssh_key_identities ORDER BY id")
    protected abstract suspend fun keyIdentities(): List<SshKeyIdentityEntity>

    @Query("SELECT * FROM ssh_credentials ORDER BY id")
    protected abstract suspend fun credentials(): List<SshCredentialEntity>

    @Query("SELECT * FROM host_profiles ORDER BY id")
    protected abstract suspend fun hosts(): List<HostProfileEntity>

    @Query("SELECT * FROM known_hosts ORDER BY id")
    protected abstract suspend fun knownHosts(): List<KnownHostEntity>

    @Query("SELECT * FROM snippets ORDER BY id")
    protected abstract suspend fun snippets(): List<SnippetEntity>

    @Query("SELECT * FROM encrypted_secrets ORDER BY id")
    protected abstract suspend fun encryptedSecrets(): List<EncryptedSecretEntity>

    @Transaction
    open suspend fun readSnapshot(): BackupSnapshotRows = BackupSnapshotRows(
        terminalProfiles = terminalProfiles(),
        customTerminalThemes = customTerminalThemes(),
        keyboardProfiles = keyboardProfiles(),
        keyboardKeys = keyboardKeys(),
        keyIdentities = keyIdentities(),
        credentials = credentials(),
        hosts = hosts(),
        knownHosts = knownHosts(),
        snippets = snippets(),
        encryptedSecrets = encryptedSecrets(),
    )
}
