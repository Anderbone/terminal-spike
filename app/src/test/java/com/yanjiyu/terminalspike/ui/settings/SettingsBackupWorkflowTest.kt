package com.yanjiyu.terminalspike.ui.settings

import com.yanjiyu.terminalspike.core.backup.BackupContentSummary
import com.yanjiyu.terminalspike.core.backup.BackupDocumentContract
import com.yanjiyu.terminalspike.core.backup.BackupEnvelopeException
import com.yanjiyu.terminalspike.core.backup.BackupImportPlanPreview
import com.yanjiyu.terminalspike.core.backup.BackupImportResult
import com.yanjiyu.terminalspike.core.backup.BackupImportStrategy
import com.yanjiyu.terminalspike.core.backup.BackupMode
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsBackupWorkflowTest {
    @Test
    fun createDocumentExportUsesExactContractAndAlwaysWipesOwnedPassphrase() = runTest {
        val gateway = FakeGateway()
        val workflow = SettingsBackupWorkflow(
            gateway = gateway,
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            clock = { CREATED_AT },
        )
        val request = async { workflow.documentRequests.first() }
        val passphrase = "standard-passphrase".toCharArray()

        workflow.beginExport()
        workflow.requestExportDocument(
            BackupMode.STANDARD,
            includeCustomFonts = true,
            passphrase = passphrase,
        )

        assertEquals(
            BackupDocumentRequest.Create(BackupDocumentContract.suggestedFileName(CREATED_AT)),
            request.await(),
        )
        workflow.onExportDocumentResult(DOCUMENT)
        advanceUntilIdle()

        assertEquals(
            listOf("export:standard:content://fake/backup:$CREATED_AT"),
            gateway.events,
        )
        assertEquals("standard-passphrase", gateway.exportPassphrase)
        assertTrue(gateway.exportIncludedCustomFonts)
        assertTrue(passphrase.all { it == '\u0000' })
        assertEquals(BackupWorkflowStep.COMPLETED, workflow.state.value.step)
        assertEquals(BackupCompletionKind.EXPORT, workflow.state.value.completionKind)
        assertEquals(4096L, workflow.state.value.exportResult?.bytesWritten)
        workflow.close()
    }

    @Test
    fun authenticatedRestorePreviewsStrategyThenConsumesEveryHandleOnce() = runTest {
        val gateway = FakeGateway()
        val workflow = SettingsBackupWorkflow(
            gateway,
            this,
            StandardTestDispatcher(testScheduler),
        )
        val request = async { workflow.documentRequests.first() }

        workflow.requestRestoreDocument()
        assertEquals(BackupDocumentRequest.Open, request.await())
        workflow.onRestoreDocumentResult(DOCUMENT)
        advanceUntilIdle()
        assertEquals(BackupWorkflowStep.PASSPHRASE_REQUIRED, workflow.state.value.step)
        assertEquals(BackupMode.FULL, workflow.state.value.header?.mode)

        val passphrase = "full restore passphrase".toCharArray()
        workflow.unlockSelectedBackup(passphrase)
        advanceUntilIdle()
        assertTrue(passphrase.all { it == '\u0000' })
        assertEquals(BackupWorkflowStep.AUTHENTICATED_PREVIEW, workflow.state.value.step)
        assertEquals(2, workflow.state.value.archive?.content?.hosts)
        assertEquals(2, workflow.state.value.archive?.content?.terminalThemes)

        workflow.selectImportStrategy(BackupImportStrategy.KEEP_BOTH)
        workflow.prepareImportPreview()
        advanceUntilIdle()
        assertEquals(BackupWorkflowStep.IMPORT_REVIEW, workflow.state.value.step)
        assertEquals(BackupImportStrategy.KEEP_BOTH, workflow.state.value.plan?.strategy)
        assertTrue(gateway.unlocked.closed)

        workflow.applyPreparedImport()
        advanceUntilIdle()

        assertEquals(
            listOf(
                "inspect:content://fake/backup",
                "unlock:content://fake/backup",
                "prepare:keep_both",
                "apply",
            ),
            gateway.events,
        )
        assertTrue(gateway.prepared.closed)
        assertEquals(BackupWorkflowStep.COMPLETED, workflow.state.value.step)
        assertEquals(BackupCompletionKind.IMPORT, workflow.state.value.completionKind)
        assertEquals(2, workflow.state.value.importResult?.hostsApplied)
        assertEquals(2, workflow.state.value.importResult?.terminalThemesApplied)
        workflow.close()
    }

    @Test
    fun wrongPassphraseIsNonOracularRetryableAndWipedWithoutPlanning() = runTest {
        val gateway = FakeGateway().apply { unlockFailure = BackupEnvelopeException.UnlockFailed() }
        val workflow = SettingsBackupWorkflow(
            gateway,
            this,
            StandardTestDispatcher(testScheduler),
        )
        val request = async { workflow.documentRequests.first() }
        workflow.requestRestoreDocument()
        request.await()
        workflow.onRestoreDocumentResult(DOCUMENT)
        advanceUntilIdle()
        val passphrase = "incorrect passphrase".toCharArray()

        workflow.unlockSelectedBackup(passphrase)
        advanceUntilIdle()

        assertTrue(passphrase.all { it == '\u0000' })
        assertEquals(BackupWorkflowStep.PASSPHRASE_REQUIRED, workflow.state.value.step)
        assertEquals(BackupWorkflowError.UNLOCK_FAILED, workflow.state.value.error)
        assertFalse(gateway.events.any { it.startsWith("prepare:") })
        workflow.close()
    }

    @Test
    fun pickerCancellationAndWorkflowCloseWipeStagedExportPassphrases() = runTest {
        val gateway = FakeGateway()
        val workflow = SettingsBackupWorkflow(gateway, this, StandardTestDispatcher(testScheduler))
        val firstRequest = async { workflow.documentRequests.first() }
        val first = "first-passphrase".toCharArray()
        workflow.beginExport()
        workflow.requestExportDocument(BackupMode.STANDARD, first)
        firstRequest.await()

        workflow.onExportDocumentResult(null)
        assertTrue(first.all { it == '\u0000' })
        assertEquals(BackupWorkflowStep.READY, workflow.state.value.step)

        val secondRequest = async { workflow.documentRequests.first() }
        val second = "second-passphrase".toCharArray()
        workflow.beginExport()
        workflow.requestExportDocument(BackupMode.STANDARD, second)
        secondRequest.await()
        workflow.close()
        assertTrue(second.all { it == '\u0000' })
    }

    @Test
    fun pendingRecoveryBlocksNewRestoreAndCanBeAppliedExplicitly() = runTest {
        val gateway = FakeGateway().apply { recoveryPending = true }
        val workflow = SettingsBackupWorkflow(
            gateway,
            this,
            StandardTestDispatcher(testScheduler),
        )

        workflow.requestRestoreDocument()
        assertEquals(BackupWorkflowStep.FAILED, workflow.state.value.step)
        assertEquals(BackupWorkflowError.RECOVERY_REQUIRED, workflow.state.value.error)

        workflow.recoverPendingImport()
        advanceUntilIdle()

        assertEquals(listOf("recover"), gateway.events)
        assertEquals(BackupCompletionKind.RECOVERY, workflow.state.value.completionKind)
        assertFalse(workflow.state.value.recoveryAvailable)
        workflow.close()
    }

    private class FakeGateway : SettingsBackupGateway {
        val events = mutableListOf<String>()
        var exportPassphrase: String? = null
        var exportIncludedCustomFonts = false
        var unlockFailure: Throwable? = null
        var recoveryPending = false
        val unlocked = FakeUnlocked()
        val prepared = FakePrepared()

        override fun hasPendingRecovery(): Boolean = recoveryPending

        override suspend fun export(
            destination: BackupDocumentReference,
            mode: BackupMode,
            createdAtEpochMillis: Long,
            passphrase: CharArray,
        ): BackupExportUiResult {
            return exportWithOptions(destination, mode, createdAtEpochMillis, false, passphrase)
        }

        override suspend fun exportWithOptions(
            destination: BackupDocumentReference,
            mode: BackupMode,
            createdAtEpochMillis: Long,
            includeCustomFonts: Boolean,
            passphrase: CharArray,
        ): BackupExportUiResult {
            events += "export:${mode.name.lowercase()}:${destination.encodedUri}:$createdAtEpochMillis"
            exportPassphrase = passphrase.concatToString()
            exportIncludedCustomFonts = includeCustomFonts
            return BackupExportUiResult(mode, CONTENT, 4096)
        }

        override suspend fun inspect(source: BackupDocumentReference): BackupHeaderUiSummary {
            events += "inspect:${source.encodedUri}"
            return HEADER
        }

        override suspend fun unlock(
            source: BackupDocumentReference,
            passphrase: CharArray,
        ): SettingsUnlockedBackup {
            events += "unlock:${source.encodedUri}"
            unlockFailure?.let { throw it }
            return unlocked
        }

        override suspend fun prepare(
            unlocked: SettingsUnlockedBackup,
            strategy: BackupImportStrategy,
        ): SettingsPreparedBackupImport {
            events += "prepare:${strategy.name.lowercase()}"
            prepared.selectedStrategy = strategy
            return prepared
        }

        override suspend fun apply(prepared: SettingsPreparedBackupImport): BackupImportResult {
            events += "apply"
            return IMPORT_RESULT
        }

        override suspend fun recoverPending(): BackupImportResult {
            events += "recover"
            recoveryPending = false
            return IMPORT_RESULT
        }
    }

    private class FakeUnlocked : SettingsUnlockedBackup {
        var closed = false
        override val preview = BackupArchiveUiSummary(HEADER, CONTENT, 1, 2, 0)

        override fun close() {
            closed = true
        }
    }

    private class FakePrepared : SettingsPreparedBackupImport {
        var selectedStrategy = BackupImportStrategy.MERGE
        var closed = false
        override val preview: BackupImportPlanPreview
            get() = BackupImportPlanPreview(
                strategy = selectedStrategy,
                conflicts = 3,
                skippedRecords = 2,
                incompatibleRecords = 1,
                externallyResolvedReferences = 0,
                recordIdRewrites = 2,
                secretIdRewrites = 1,
                destructive = selectedStrategy == BackupImportStrategy.REPLACE_CORRESPONDING,
            )

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val CREATED_AT = 1_725_000_000_000L
        val DOCUMENT = BackupDocumentReference("content://fake/backup")
        val HEADER = BackupHeaderUiSummary(BackupMode.FULL, CREATED_AT, "42", 1)
        val CONTENT = BackupContentSummary(
            hosts = 2,
            credentials = 1,
            sshKeys = 1,
            knownHosts = 3,
            snippets = 4,
            terminalProfiles = 1,
            terminalThemes = 2,
            keyboardProfiles = 1,
            portableCredentialSecrets = 1,
            portablePrivateKeys = 1,
        )
        val IMPORT_RESULT = BackupImportResult(
            strategy = BackupImportStrategy.KEEP_BOTH,
            hostsApplied = 2,
            credentialsApplied = 1,
            sshKeysApplied = 1,
            knownHostsApplied = 3,
            snippetsApplied = 4,
            terminalProfilesApplied = 1,
            terminalThemesApplied = 2,
            keyboardProfilesApplied = 1,
            unavailableSecretPlaceholders = 0,
            skippedRecords = 2,
            incompatibleRecords = 1,
            recoveryMarkerRemoved = true,
        )
    }
}
