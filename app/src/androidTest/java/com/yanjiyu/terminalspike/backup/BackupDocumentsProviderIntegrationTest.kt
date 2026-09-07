package com.yanjiyu.terminalspike.backup

import android.Manifest
import android.os.Build
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.isCutoverReady
import com.yanjiyu.terminalspike.core.backup.BackupDocumentContract
import com.yanjiyu.terminalspike.core.backup.BackupImportStrategy
import com.yanjiyu.terminalspike.core.backup.BackupMode
import com.yanjiyu.terminalspike.core.data.credential.EncryptedSecretState
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import com.yanjiyu.terminalspike.core.security.credential.CredentialId
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import com.yanjiyu.terminalspike.ui.settings.AndroidSettingsBackupGateway
import com.yanjiyu.terminalspike.ui.settings.BackupDocumentReference
import com.yanjiyu.terminalspike.ui.settings.SettingsBackupWorkflow
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupDocumentsProviderIntegrationTest {
    @Test
    fun exportStandardAndFullArchivesForCleanInstall() = runBlocking {
        assumeCleanInstallStage(CLEAN_INSTALL_EXPORT_STAGE)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as TerminalSpikeApplication
        val container = application.container
        container.authoritativeData.awaitReady()
        check(container.startLegacyMigrationForCutover().await().isCutoverReady())
        assertNull(container.hostProfiles.get(CLEAN_HOST_ID))
        assertNull(container.snippets.get(CLEAN_SNIPPET_ID))

        container.sshCredentials.saveWithSecret(
            SshCredential(
                id = CLEAN_CREDENTIAL_ID,
                displayName = "Clean-install saved password",
                authentication = SshAuthentication.Password(CLEAN_SECRET_ID),
                createdAtEpochMillis = CREATED_AT,
                updatedAtEpochMillis = CREATED_AT,
            ),
            CLEAN_PASSWORD.encodeToByteArray(),
        )
        container.hostProfiles.insert(
            HostProfile(
                id = CLEAN_HOST_ID,
                displayName = "Clean-install host",
                hostname = "backup.invalid",
                port = 22,
                username = "backup-user",
                protocol = ConnectionProtocol.SSH,
                credentialId = CLEAN_CREDENTIAL_ID,
                createdAtEpochMillis = CREATED_AT,
                updatedAtEpochMillis = CREATED_AT,
            ),
        )
        container.snippets.insert(cleanInstallSnippet())
        container.settings.update { settings ->
            settings.notificationPrivacyEnabled = false
        }

        val gateway = AndroidSettingsBackupGateway(
            context = instrumentation.targetContext,
            transfers = container.backupTransfers,
            imports = container.backupImports,
        )
        listOf(
            BackupMode.STANDARD to CLEAN_STANDARD_DOCUMENT_ID,
            BackupMode.FULL to CLEAN_FULL_DOCUMENT_ID,
        ).forEachIndexed { index, (mode, documentId) ->
            val reference = cleanInstallReference(instrumentation.targetContext.filesDir, documentId)
            val passphrase = SettingsBackupWorkflow.newPortableBackupKey()
            try {
                val result = gateway.exportWithOptions(
                    destination = reference,
                    mode = mode,
                    createdAtEpochMillis = CREATED_AT + index + 1,
                    includeCustomFonts = mode == BackupMode.FULL,
                    passphrase = passphrase,
                )
                assertEquals(mode, result.mode)
                assertTrue(result.bytesWritten > 0)
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    @Test
    fun restoreStandardArchiveAfterCleanInstall() = runBlocking {
        assumeCleanInstallStage(CLEAN_INSTALL_RESTORE_STANDARD_STAGE)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as TerminalSpikeApplication
        val container = application.container
        container.authoritativeData.awaitReady()
        check(container.startLegacyMigrationForCutover().await().isCutoverReady())

        // The host runner uninstalls the exporter. These IDs must not exist before the archive is
        // applied, proving this stage did not inherit Room or Keystore state from stage one.
        assertNull(container.hostProfiles.get(CLEAN_HOST_ID))
        assertNull(container.sshCredentials.get(CLEAN_CREDENTIAL_ID))
        assertNull(container.snippets.get(CLEAN_SNIPPET_ID))
        assertNull(container.database.credentialRecordDao().findSecretById(CLEAN_SECRET_ID))

        val gateway = AndroidSettingsBackupGateway(
            context = instrumentation.targetContext,
            transfers = container.backupTransfers,
            imports = container.backupImports,
        )
        restoreCleanInstallArchive(
            gateway = gateway,
            reference = cleanInstallReference(
                instrumentation.targetContext.filesDir,
                CLEAN_STANDARD_DOCUMENT_ID,
                create = false,
            ),
            expectedMode = BackupMode.STANDARD,
        )
        assertRestoredMetadata(container)
        val unavailable = requireNotNull(
            container.database.credentialRecordDao().findSecretById(CLEAN_SECRET_ID),
        )
        assertEquals(EncryptedSecretState.LEGACY_UNAVAILABLE.wireCode, unavailable.stateCode)
        assertEquals("backup_secret_not_exported", unavailable.failureCode)
    }

    @Test
    fun restoreFullArchiveAfterCleanInstall() = runBlocking {
        assumeCleanInstallStage(CLEAN_INSTALL_RESTORE_FULL_STAGE)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as TerminalSpikeApplication
        val container = application.container
        container.authoritativeData.awaitReady()
        check(container.startLegacyMigrationForCutover().await().isCutoverReady())

        assertNull(container.hostProfiles.get(CLEAN_HOST_ID))
        assertNull(container.sshCredentials.get(CLEAN_CREDENTIAL_ID))
        assertNull(container.snippets.get(CLEAN_SNIPPET_ID))
        assertNull(container.database.credentialRecordDao().findSecretById(CLEAN_SECRET_ID))

        val gateway = AndroidSettingsBackupGateway(
            context = instrumentation.targetContext,
            transfers = container.backupTransfers,
            imports = container.backupImports,
        )
        restoreCleanInstallArchive(
            gateway = gateway,
            reference = cleanInstallReference(
                instrumentation.targetContext.filesDir,
                CLEAN_FULL_DOCUMENT_ID,
                create = false,
            ),
            expectedMode = BackupMode.FULL,
        )
        assertRestoredMetadata(container)
        val ready = requireNotNull(
            container.database.credentialRecordDao().findSecretById(CLEAN_SECRET_ID),
        )
        assertEquals(EncryptedSecretState.READY.wireCode, ready.stateCode)
        container.credentialStore.withSecret(cleanCredentialReference()) { restored ->
            assertArrayEquals(CLEAN_PASSWORD.encodeToByteArray(), restored)
        }
    }

    @Test
    fun replaceRestoreAppliesExportedSnippetThroughOpaqueProviderStream() = runBlocking {
        assumeTrue(
            "UiAutomation scoped shell-permission adoption requires API 28.",
            Build.VERSION.SDK_INT >= 28,
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as TerminalSpikeApplication
        val container = application.container
        container.authoritativeData.awaitReady()
        check(container.startLegacyMigrationForCutover().await().isCutoverReady())

        val gateway = AndroidSettingsBackupGateway(
            context = instrumentation.targetContext,
            transfers = container.backupTransfers,
            imports = container.backupImports,
        )
        val resolver = instrumentation.context.contentResolver
        val root = DocumentsContract.buildDocumentUri(
            LocalBackupDocumentsProvider.AUTHORITY,
            LocalBackupDocumentsProvider.ROOT_DOCUMENT_ID,
        )
        val snippetId = UUID.randomUUID().toString()
        val original = Snippet(
            id = snippetId,
            name = "Restore integration original",
            command = "printf 'restored-from-archive\\n'",
            createdAtEpochMillis = CREATED_AT,
            updatedAtEpochMillis = CREATED_AT,
        )
        var documentUri: android.net.Uri? = null

        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MANAGE_DOCUMENTS)
        try {
            container.snippets.insert(original)
            documentUri = requireNotNull(
                DocumentsContract.createDocument(
                    resolver,
                    root,
                    BackupDocumentContract.MIME_TYPE,
                    "provider-restore${BackupDocumentContract.FILE_EXTENSION}",
                ),
            )
            val reference = BackupDocumentReference(documentUri.toString())
            val exportPassphrase = RESTORE_PASSPHRASE.toCharArray()
            try {
                gateway.export(
                    destination = reference,
                    mode = BackupMode.STANDARD,
                    createdAtEpochMillis = CREATED_AT + 1,
                    passphrase = exportPassphrase,
                )
            } finally {
                exportPassphrase.fill('\u0000')
            }

            assertTrue(
                container.snippets.update(
                    original.copy(
                        name = "Mutated after export",
                        command = "printf 'mutation-must-disappear\\n'",
                        updatedAtEpochMillis = CREATED_AT + 2,
                    ),
                ),
            )
            assertEquals("Mutated after export", container.snippets.get(snippetId)?.name)

            val importPassphrase = RESTORE_PASSPHRASE.toCharArray()
            try {
                gateway.unlock(reference, importPassphrase).use { unlocked ->
                    gateway.prepare(unlocked, BackupImportStrategy.REPLACE_CORRESPONDING).use { prepared ->
                        val result = gateway.apply(prepared)
                        assertTrue(result.snippetsApplied >= 1)
                        assertTrue(result.recoveryMarkerRemoved)
                    }
                }
            } finally {
                importPassphrase.fill('\u0000')
            }

            assertEquals(original, container.snippets.get(snippetId))
        } finally {
            container.snippets.delete(snippetId)
            documentUri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun standardAndFullArchivesRoundTripThroughOpaqueProviderStreams() = runBlocking {
        assumeTrue(
            "UiAutomation scoped shell-permission adoption requires API 28.",
            Build.VERSION.SDK_INT >= 28,
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as TerminalSpikeApplication
        val gateway = AndroidSettingsBackupGateway(
            context = instrumentation.targetContext,
            transfers = application.container.backupTransfers,
            imports = application.container.backupImports,
        )
        val resolver = instrumentation.context.contentResolver
        val root = DocumentsContract.buildDocumentUri(
            LocalBackupDocumentsProvider.AUTHORITY,
            LocalBackupDocumentsProvider.ROOT_DOCUMENT_ID,
        )

        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MANAGE_DOCUMENTS)
        try {
            listOf(
                BackupMode.STANDARD to "provider-standard-passphrase",
                BackupMode.FULL to "provider-full-passphrase",
            ).forEachIndexed { index, (mode, passphraseText) ->
                val uri = requireNotNull(
                    DocumentsContract.createDocument(
                        resolver,
                        root,
                        BackupDocumentContract.MIME_TYPE,
                        "provider-$index${BackupDocumentContract.FILE_EXTENSION}",
                    ),
                )
                val reference = BackupDocumentReference(uri.toString())
                val exportPassphrase = passphraseText.toCharArray()
                try {
                    val exported = gateway.exportWithOptions(
                        destination = reference,
                        mode = mode,
                        createdAtEpochMillis = CREATED_AT + index,
                        includeCustomFonts = false,
                        passphrase = exportPassphrase,
                    )
                    assertEquals(mode, exported.mode)
                    assertTrue(exported.bytesWritten > 0L)
                } finally {
                    exportPassphrase.fill('\u0000')
                }

                val header = gateway.inspect(reference)
                assertEquals(mode, header.mode)
                val importPassphrase = passphraseText.toCharArray()
                try {
                    gateway.unlock(reference, importPassphrase).use { unlocked ->
                        assertEquals(mode, unlocked.preview.header.mode)
                        assertTrue(unlocked.preview.content.terminalProfiles >= 1)
                        assertTrue(unlocked.preview.content.keyboardProfiles >= 1)
                    }
                } finally {
                    importPassphrase.fill('\u0000')
                }
                DocumentsContract.deleteDocument(resolver, uri)
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private companion object {
        const val CREATED_AT = 1_725_000_000_000L
        const val RESTORE_PASSPHRASE = "provider-restore-passphrase"
        const val CLEAN_INSTALL_ARGUMENT = "terminalSpikeBackupCleanInstallStage"
        const val CLEAN_INSTALL_EXPORT_STAGE = "export"
        const val CLEAN_INSTALL_RESTORE_STANDARD_STAGE = "restore-standard"
        const val CLEAN_INSTALL_RESTORE_FULL_STAGE = "restore-full"
        const val CLEAN_STANDARD_DOCUMENT_ID = "clean-install-standard.tsbak"
        const val CLEAN_FULL_DOCUMENT_ID = "clean-install-full.tsbak"
        const val CLEAN_HOST_ID = "00000000-0000-4000-8000-000000000101"
        const val CLEAN_CREDENTIAL_ID = "00000000-0000-4000-8000-000000000102"
        const val CLEAN_SECRET_ID = "00000000-0000-4000-8000-000000000103"
        const val CLEAN_SNIPPET_ID = "00000000-0000-4000-8000-000000000104"
        const val CLEAN_PASSWORD = "clean-install-portable-secret-4271"
    }

    private fun assumeCleanInstallStage(expected: String) {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val actual = InstrumentationRegistry.getArguments().getString(CLEAN_INSTALL_ARGUMENT)
        assumeTrue("This destructive clean-install backup stage is host-runner-only.", actual == expected)
    }

    private fun cleanInstallSnippet() = Snippet(
        id = CLEAN_SNIPPET_ID,
        name = "Clean-install snippet",
        command = "printf 'clean-install-restored\\n'",
        createdAtEpochMillis = CREATED_AT,
        updatedAtEpochMillis = CREATED_AT,
    )

    private fun cleanCredentialReference() = CredentialSecretReference(
        credentialId = CredentialId.parseCanonical(CLEAN_CREDENTIAL_ID),
        secretId = SecretId.parseCanonical(CLEAN_SECRET_ID),
        kind = CredentialSecretKind.PASSWORD,
    )

    private fun cleanInstallReference(
        filesDir: File,
        documentId: String,
        create: Boolean = true,
    ): BackupDocumentReference {
        val directory = filesDir.resolve("backup-documents").apply { mkdirs() }
        val archive = directory.resolve(documentId)
        if (create) {
            archive.delete()
            assertTrue(archive.createNewFile())
        } else {
            assertTrue("The host runner did not restore $documentId.", archive.isFile)
            assertTrue("The restored archive is empty.", archive.length() > 0)
        }
        return BackupDocumentReference(
            DocumentsContract.buildDocumentUri(LocalBackupDocumentsProvider.AUTHORITY, documentId)
                .toString(),
        )
    }

    private suspend fun restoreCleanInstallArchive(
        gateway: AndroidSettingsBackupGateway,
        reference: BackupDocumentReference,
        expectedMode: BackupMode,
    ) {
        assertEquals(expectedMode, gateway.inspect(reference).mode)
        val passphrase = SettingsBackupWorkflow.newPortableBackupKey()
        try {
            gateway.unlock(reference, passphrase).use { unlocked ->
                assertEquals(expectedMode, unlocked.preview.header.mode)
                gateway.prepare(unlocked, BackupImportStrategy.REPLACE_CORRESPONDING).use { prepared ->
                    val result = gateway.apply(prepared)
                    assertTrue(result.hostsApplied >= 1)
                    assertTrue(result.credentialsApplied >= 1)
                    assertTrue(result.snippetsApplied >= 1)
                    assertTrue(result.recoveryMarkerRemoved)
                }
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private suspend fun assertRestoredMetadata(container: com.yanjiyu.terminalspike.AppContainer) {
        val host = requireNotNull(container.hostProfiles.get(CLEAN_HOST_ID))
        assertEquals("Clean-install host", host.displayName)
        assertEquals(CLEAN_CREDENTIAL_ID, host.credentialId)
        assertNotNull(container.sshCredentials.get(CLEAN_CREDENTIAL_ID))
        assertEquals(cleanInstallSnippet(), container.snippets.get(CLEAN_SNIPPET_ID))
        assertEquals(false, container.settings.settings.first().notificationPrivacyEnabled)
    }
}
