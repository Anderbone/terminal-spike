package com.yanjiyu.terminalspike.core.backup

import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.db.BackupSnapshotRows
import com.yanjiyu.terminalspike.core.data.credential.toStoredEncryptedCredentialRecord
import com.yanjiyu.terminalspike.core.data.db.EncryptedSecretEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileWithKeys
import com.yanjiyu.terminalspike.core.data.repository.toDomainModel
import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsRepository
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.security.credential.CredentialId
import com.yanjiyu.terminalspike.core.security.credential.CredentialRecordDecryptor
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.CredentialStoreException
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class BackupSnapshotException(message: String) : Exception(message) {
    class ConcurrentSettingsMutation : BackupSnapshotException(
        "Settings changed repeatedly while the backup snapshot was being captured.",
    )
}

data class BackupExportOptions(
    val mode: BackupMode,
    val includeCustomFonts: Boolean = false,
)

fun interface BackupCustomFontSource {
    suspend fun read(fontIds: Set<String>): List<BackupCustomFont>
}

fun interface BackupSnapshotProvider {
    suspend fun create(mode: BackupMode): BackupPayloadSnapshot

    suspend fun create(options: BackupExportOptions): BackupPayloadSnapshot = create(options.mode)
}

/**
 * Produces one validated application-data snapshot without exposing Room ciphertext to callers.
 *
 * Room rows are captured in one DAO transaction. DataStore cannot share that transaction, so the
 * settings value is read immediately before and after it; a concurrent settings mutation retries
 * the capture. Full exports decrypt the exact captured ciphertext through a scoped crypto boundary
 * only long enough to copy it into caller-owned wipeable storage.
 */
class RoomBackupSnapshotSource private constructor(
    private val readRows: suspend () -> BackupSnapshotRows,
    private val readSettings: suspend () -> AppSettings,
    private val credentialDecryptor: CredentialRecordDecryptor,
    private val customFonts: BackupCustomFontSource,
) : BackupSnapshotProvider {
    constructor(
        database: AppDatabase,
        settingsRepository: AppSettingsRepository,
        credentialDecryptor: CredentialRecordDecryptor,
        customFonts: BackupCustomFontSource = BackupCustomFontSource { emptyList() },
    ) : this(
        readRows = { database.backupSnapshotDao().readSnapshot() },
        readSettings = { settingsRepository.settings.first() },
        credentialDecryptor = credentialDecryptor,
        customFonts = customFonts,
    )

    private val exportMutex = Mutex()

    override suspend fun create(mode: BackupMode): BackupPayloadSnapshot =
        create(BackupExportOptions(mode = mode))

    override suspend fun create(options: BackupExportOptions): BackupPayloadSnapshot = exportMutex.withLock {
        val (rows, settings) = captureStableRowsAndSettings()
        buildSnapshot(options, rows, settings)
    }

    private suspend fun captureStableRowsAndSettings(): Pair<BackupSnapshotRows, AppSettings> {
        repeat(MAX_SETTINGS_SNAPSHOT_ATTEMPTS) {
            val before = readSettings()
            val rows = readRows()
            val after = readSettings()
            if (before == after) return rows to after
        }
        throw BackupSnapshotException.ConcurrentSettingsMutation()
    }

    private suspend fun buildSnapshot(
        options: BackupExportOptions,
        rows: BackupSnapshotRows,
        settings: AppSettings,
    ): BackupPayloadSnapshot {
        val credentials = rows.credentials.map { it.toDomainModel() }
        val identities = rows.keyIdentities.map { it.toDomainModel() }
        val credentialRecords = mutableListOf<BackupCredentialRecord>()
        val keyRecords = mutableListOf<BackupSshKeyRecord>()
        val capturedSecrets = rows.encryptedSecrets.associateBy { it.id }
        try {
            credentials.forEach { credential ->
                credentialRecords += BackupCredentialRecord(
                    metadata = credential,
                    portableSecret = if (options.mode == BackupMode.FULL) {
                        credential.authentication.secretReference(credential.id)?.let { reference ->
                            copyPortableSecret(reference, capturedSecrets)
                        }
                    } else {
                        null
                    },
                )
            }
            identities.forEach { identity ->
                keyRecords += BackupSshKeyRecord(
                    metadata = identity,
                    portablePrivateKey = if (options.mode == BackupMode.FULL) {
                        copyPortableSecret(
                            CredentialSecretReference(
                                credentialId = CredentialId.parseCanonical(identity.id),
                                secretId = SecretId.parseCanonical(identity.privateKeySecretReferenceId),
                                kind = CredentialSecretKind.PRIVATE_KEY,
                            ),
                            capturedSecrets,
                        )
                    } else {
                        null
                    },
                )
            }

            val keysByProfile = rows.keyboardKeys.groupBy { it.profileId }
            val terminalProfiles = rows.terminalProfiles.map { it.toDomainModel() }
            val exportedFonts = if (options.includeCustomFonts) {
                customFonts.read(
                    terminalProfiles.asSequence()
                        .map { it.fontId }
                        .filter { it.startsWith(CUSTOM_FONT_ID_PREFIX) }
                        .toSet(),
                )
            } else {
                emptyList()
            }
            return BackupPayloadSnapshot(
                mode = options.mode,
                hostProfiles = rows.hosts.map { it.toDomainModel() },
                credentials = credentialRecords,
                sshKeys = keyRecords,
                knownHosts = rows.knownHosts.map { it.toDomainModel() },
                snippets = rows.snippets.map { it.toDomainModel() },
                terminalProfiles = terminalProfiles,
                terminalThemes = rows.customTerminalThemes.map {
                    it.toDomainModel().toBackupTerminalTheme()
                },
                keyboardProfiles = rows.keyboardProfiles.map { profile ->
                    KeyboardProfileWithKeys(
                        profile = profile,
                        keys = keysByProfile[profile.id].orEmpty(),
                    ).toDomainModel()
                },
                customFonts = exportedFonts,
                globalSettings = settings.toBackupGlobalSettings(),
            )
        } catch (error: Throwable) {
            credentialRecords.forEach { it.portableSecret?.wipe() }
            keyRecords.forEach { it.portablePrivateKey?.wipe() }
            throw error
        }
    }

    private suspend fun copyPortableSecret(
        reference: CredentialSecretReference,
        capturedSecrets: Map<String, EncryptedSecretEntity>,
    ): PortableBackupSecret {
        val encrypted = capturedSecrets[reference.secretId.value]
            ?.toStoredEncryptedCredentialRecord()
            ?.encrypted
            ?: throw CredentialStoreException.Missing(reference.secretId)
        return credentialDecryptor.withEncryptedRecord(reference, encrypted) { scopedSecret ->
            PortableBackupSecret.copyAndWipe(scopedSecret.copyOf())
        }
    }

    internal constructor(
        readRows: suspend () -> BackupSnapshotRows,
        readSettings: suspend () -> AppSettings,
        credentialDecryptor: CredentialRecordDecryptor,
        customFonts: BackupCustomFontSource = BackupCustomFontSource { emptyList() },
        @Suppress("UNUSED_PARAMETER") testOnly: Unit,
    ) : this(readRows, readSettings, credentialDecryptor, customFonts)

    private companion object {
        const val MAX_SETTINGS_SNAPSHOT_ATTEMPTS = 3
        const val CUSTOM_FONT_ID_PREFIX = "custom_"
    }
}

private fun SshAuthentication.secretReference(ownerId: String): CredentialSecretReference? {
    val (secretId, kind) = when (this) {
        is SshAuthentication.Password -> secretReferenceId to CredentialSecretKind.PASSWORD
        is SshAuthentication.PrivateKey -> passphraseSecretReferenceId to CredentialSecretKind.KEY_PASSPHRASE
        is SshAuthentication.KeyboardInteractive ->
            reusableResponseSecretReferenceId to CredentialSecretKind.KEYBOARD_INTERACTIVE
    }
    secretId ?: return null
    return CredentialSecretReference(
        credentialId = CredentialId.parseCanonical(ownerId),
        secretId = SecretId.parseCanonical(secretId),
        kind = kind,
    )
}

internal fun AppSettings.toBackupGlobalSettings(): BackupGlobalSettings = BackupGlobalSettings(
    themeMode = when (themeMode) {
        AppSettings.ThemeMode.THEME_MODE_SYSTEM -> BackupThemeMode.SYSTEM
        AppSettings.ThemeMode.THEME_MODE_LIGHT -> BackupThemeMode.LIGHT
        AppSettings.ThemeMode.THEME_MODE_DARK -> BackupThemeMode.DARK
        else -> error("Validated settings contain an unsupported theme mode.")
    },
    dynamicColorEnabled = dynamicColorEnabled,
    accentPreset = accentPreset.ifEmpty { null },
    defaultTerminalProfileId = defaultTerminalProfileId.ifEmpty { null },
    defaultKeyboardProfileId = defaultKeyboardProfileId.ifEmpty { null },
    keepaliveIntervalSeconds = keepaliveIntervalSeconds,
    reconnectEnabled = reconnectEnabled,
    reconnectMaxAttempts = reconnectMaxAttempts,
    backgroundSessionsEnabled = backgroundSessionsEnabled,
    notificationPrivacyEnabled = notificationPrivacyEnabled,
    disconnectNotificationsEnabled = disconnectNotificationsEnabled,
    reconnectNotificationsEnabled = reconnectNotificationsEnabled,
    keepCpuAwake = keepCpuAwake,
    keepScreenOnWhileTerminalVisible = keepScreenOnWhileTerminalVisible,
    appLockMode = when (appLockMode) {
        AppSettings.AppLockMode.APP_LOCK_MODE_OFF -> BackupAppLockMode.OFF
        AppSettings.AppLockMode.APP_LOCK_MODE_IMMEDIATE -> BackupAppLockMode.IMMEDIATE
        AppSettings.AppLockMode.APP_LOCK_MODE_DELAYED -> BackupAppLockMode.DELAYED
        AppSettings.AppLockMode.APP_LOCK_MODE_ON_BACKGROUND -> BackupAppLockMode.ON_BACKGROUND
        else -> error("Validated settings contain an unsupported app-lock mode.")
    },
    appLockDelaySeconds = appLockDelaySeconds,
    screenshotBlockingEnabled = screenshotBlockingEnabled,
    sensitiveClipboardClearSeconds = sensitiveClipboardClearSeconds,
    osc52Policy = when (osc52Policy) {
        AppSettings.Osc52Policy.OSC52_POLICY_DISABLED -> RemoteClipboardMode.DISABLED
        AppSettings.Osc52Policy.OSC52_POLICY_ASK -> RemoteClipboardMode.ASK
        else -> error("Validated settings contain an unsupported OSC 52 policy.")
    },
    multilinePasteConfirmationEnabled = multilinePasteConfirmationEnabled,
    lastBackupMode = when (lastBackupMode) {
        "" -> null
        "standard" -> BackupMode.STANDARD
        "full" -> BackupMode.FULL
        else -> error("Validated settings contain an unsupported backup mode.")
    },
)
