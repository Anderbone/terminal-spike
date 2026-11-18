package com.yanjiyu.terminalspike.core.backup

import com.yanjiyu.terminalspike.core.data.db.BackupImportRows
import com.yanjiyu.terminalspike.core.data.db.BackupSnapshotRows
import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeDataGate
import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.CredentialStore
import com.yanjiyu.terminalspike.core.security.credential.EncryptedCredentialRecord
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupImportCompatibilityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun newerGlobalRecordPreservesCurrentSettingsAndRestoresCompatibleRecords() = runTest {
        verifyImport(WireMutation.NEWER_SCHEMA, expectedIncompatible = 1, expectedSkipped = 0)
    }

    @Test
    fun unknownRequiredGlobalFieldPreservesCurrentSettingsAndRestoresCompatibleRecords() = runTest {
        verifyImport(WireMutation.REQUIRED_FIELD, expectedIncompatible = 1, expectedSkipped = 0)
    }

    @Test
    fun unknownGlobalRecordTypePreservesCurrentSettingsAndReportsTheSkippedRecord() = runTest {
        verifyImport(WireMutation.OPTIONAL_RECORD_TYPE, expectedIncompatible = 0, expectedSkipped = 1)
    }

    @Test
    fun compatibleGlobalRecordStillReplacesSettings() = runTest {
        verifyImport(WireMutation.NONE, expectedIncompatible = 0, expectedSkipped = 0)
    }

    @Test
    fun incompatibleSnippetRemainsReportedThroughPlanningAndApply() = runTest {
        verifyImport(WireMutation.SNIPPET_SCHEMA, expectedIncompatible = 1, expectedSkipped = 0)
    }

    private suspend fun verifyImport(
        mutation: WireMutation,
        expectedIncompatible: Int,
        expectedSkipped: Int,
    ) {
        val current = AppSettings.newBuilder()
            .setSchemaRevision(1)
            .setThemeMode(AppSettings.ThemeMode.THEME_MODE_DARK)
            .setAccentPreset("mint")
            .setAppLockMode(AppSettings.AppLockMode.APP_LOCK_MODE_IMMEDIATE)
            .setScreenshotBlockingEnabled(true)
            .setOsc52Policy(AppSettings.Osc52Policy.OSC52_POLICY_DISABLED)
            .build()
        val incoming = BackupPayloadSnapshot(
            mode = BackupMode.FULL,
            snippets = listOf(
                Snippet(
                    id = SNIPPET_ID,
                    name = "Compatible snippet",
                    command = "pwd",
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                ),
            ),
            globalSettings = BackupGlobalSettings(accentPreset = "violet"),
        )
        val payload = ByteArrayOutputStream().also {
            BackupPayloadCodec().writeAndWipeSecrets(incoming, it)
        }.toByteArray().mutate(mutation)
        val encrypted = ByteArrayOutputStream()
        BackupEnvelopeCrypto().encryptAndWipePassphrase(
            ByteArrayInputStream(payload), payload.size.toLong(), encrypted, PASSPHRASE.toCharArray(),
            BackupEnvelopeMetadata(BackupMode.FULL, 1, BackupAppVersion(1, "test"), 1, BackupCompatibility(26, 37, 0)),
            BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
        )
        val archive = BackupArchiveCodec(EncryptedFileBackupStagingFactory(temporaryFolder.root))
            .readAndWipePassphrase(ByteArrayInputStream(encrypted.toByteArray()), PASSPHRASE.toCharArray())
        PreparedBackupImport(archive).use { unlocked ->
            assertEquals(expectedIncompatible, unlocked.preview.incompatibleRecords)
            assertEquals(expectedSkipped, unlocked.preview.skippedRecords)
            val persistence = RecordingPersistence(current)
            val authority = AuthoritativeDataGate().also { it.resolveStartup({ false }, { false }) }
            val coordinator = BackupImportCoordinator(
                persistence = persistence,
                credentials = UnusedCredentials,
                recoverySnapshots = BackupSnapshotProvider { BackupPayloadSnapshot(BackupMode.FULL) },
                recoveryMarkers = MemoryMarker(),
                authority = authority,
                ioDispatcher = Dispatchers.Unconfined,
            )
            coordinator.prepare(unlocked, BackupImportStrategy.REPLACE_CORRESPONDING).use { prepared ->
                val result = coordinator.apply(prepared)
                val globalSupported = mutation == WireMutation.NONE || mutation == WireMutation.SNIPPET_SCHEMA
                if (globalSupported) {
                    assertEquals("violet", persistence.settings.accentPreset)
                } else {
                    assertEquals("An omitted record must not reset existing security settings", current, persistence.settings)
                }
                assertNotNull(persistence.rows)
                assertEquals(if (mutation == WireMutation.SNIPPET_SCHEMA) 0 else 1, result.snippetsApplied)
                assertEquals(expectedIncompatible, prepared.preview.incompatibleRecords)
                assertEquals(expectedIncompatible, result.incompatibleRecords)
                assertEquals(expectedSkipped, prepared.preview.skippedRecords)
                assertEquals(expectedSkipped, result.skippedRecords)
            }
        }
    }

    private enum class WireMutation { NONE, NEWER_SCHEMA, REQUIRED_FIELD, OPTIONAL_RECORD_TYPE, SNIPPET_SCHEMA }

    private fun ByteArray.mutate(mutation: WireMutation): ByteArray {
        if (mutation == WireMutation.NONE) return this
        val target = if (mutation == WireMutation.SNIPPET_SCHEMA) {
            BackupPayloadWire.COLLECTION_SNIPPETS
        } else {
            BackupPayloadWire.COLLECTION_GLOBAL_SETTINGS
        }
        var collection = BackupPayloadWire.MAGIC_V1.size
        while (collectionIdAt(collection) != target) {
            collection += 6 + u32At(collection + 2)
        }
        val record = collection + 6 + 4
        when (mutation) {
            WireMutation.NEWER_SCHEMA, WireMutation.SNIPPET_SCHEMA -> writeU32(record + 6 + 6, 2)
            WireMutation.OPTIONAL_RECORD_TYPE -> this[record + 1] = 2
            WireMutation.REQUIRED_FIELD -> {
                // Global settings is the final collection in this font-free fixture.
                check(collection + 6 + u32At(collection + 2) == size)
                return (this + byteArrayOf(0x80.toByte(), 1, 0, 0, 0, 1, 42)).also {
                    it.writeU32(collection + 2, u32At(collection + 2) + 7L)
                    it.writeU32(record + 2, u32At(record + 2) + 7L)
                }
            }
            WireMutation.NONE -> Unit
        }
        return this
    }

    private fun ByteArray.collectionIdAt(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private fun ByteArray.u32At(offset: Int): Int = (0..3).fold(0) { value, index ->
        (value shl 8) or (this[offset + index].toInt() and 0xff)
    }

    private class RecordingPersistence(initial: AppSettings) : BackupImportPersistence {
        var settings = initial
        var rows: BackupImportRows? = null

        override suspend fun capture() = CapturedBackupImportState(
            rows = BackupSnapshotRows(
                terminalProfiles = emptyList(),
                keyboardProfiles = emptyList(),
                keyboardKeys = emptyList(),
                keyIdentities = emptyList(),
                credentials = emptyList(),
                hosts = emptyList(),
                knownHosts = emptyList(),
                snippets = emptyList(),
                encryptedSecrets = emptyList(),
            ),
            settings = settings,
            snapshot = BackupPayloadSnapshot(BackupMode.STANDARD, globalSettings = settings.toBackupGlobalSettings()),
            revision = BackupImportStateRevision("test"),
        )

        override suspend fun apply(
            expectedRevision: BackupImportStateRevision,
            expectedSettings: AppSettings,
            rows: BackupImportRows,
            restoredSettings: AppSettings?,
        ) {
            assertEquals(settings, expectedSettings)
            this.rows = rows
            if (restoredSettings != null) settings = restoredSettings
        }
    }

    private class MemoryMarker : BackupRecoveryMarkerStore {
        private var pending = false
        override fun hasPendingRecovery() = pending
        override suspend fun writeAndWipe(snapshot: BackupPayloadSnapshot) {
            snapshot.close()
            pending = true
        }
        override suspend fun clear() { pending = false }
        override suspend fun read(): BackupPayloadSnapshot? = error("Recovery is not requested")
    }

    private object UnusedCredentials : CredentialStore {
        override suspend fun encryptAndWipe(reference: CredentialSecretReference, secret: ByteArray): EncryptedCredentialRecord = error("No secrets")
        override suspend fun saveAndWipe(reference: CredentialSecretReference, secret: ByteArray) = error("No secrets")
        override suspend fun <T> withSecret(reference: CredentialSecretReference, use: suspend (ByteArray) -> T): T = error("No secrets")
        override suspend fun delete(secretId: SecretId): Boolean = error("No secrets")
    }

    private companion object {
        const val PASSPHRASE = "test archive passphrase"
        const val SNIPPET_ID = "30000000-0000-4000-8000-000000000003"
    }
}
