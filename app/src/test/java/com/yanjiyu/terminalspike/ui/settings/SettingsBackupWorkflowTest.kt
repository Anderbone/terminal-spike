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
    fun createDocumentExportUsesOneCompletePassphraseFreeContract() = runTest {
        val gateway = FakeGateway()
        val workflow = SettingsBackupWorkflow(
            gateway = gateway,
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            clock = { CREATED_AT },
        )
        val request = async { workflow.documentRequests.first() }
        workflow.beginExport()

        assertEquals(
            BackupDocumentRequest.Create(BackupDocumentContract.suggestedFileName(CREATED_AT)),
            request.await(),
        )
        workflow.onExportDocumentResult(DOCUMENT)
        advanceUntilIdle()

        assertEquals(
            listOf("export:full:content://fake/backup:$CREATED_AT"),
            gateway.events,
        )
        assertFalse(gateway.exportPassphrase.isNullOrEmpty())
        assertTrue(gateway.exportIncludedCustomFonts)
        assertEquals(BackupWorkflowStep.COMPLETED, workflow.state.value.step)
        assertEquals(BackupCompletionKind.EXPORT, workflow.state.value.completionKind)
        assertEquals(4096L, workflow.state.value.exportResult?.bytesWritten)
        workflow.close()
    }

    @Test
    fun restoreAutomaticallyUnlocksAndPreparesCompleteReplacement() = runTest {
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
        assertEquals(BackupWorkflowStep.IMPORT_REVIEW, workflow.state.value.step)
        assertEquals(BackupImportStrategy.REPLACE_CORRESPONDING, workflow.state.value.plan?.strategy)
        assertTrue(gateway.unlocked.closed)

        workflow.applyPreparedImport()
        advanceUntilIdle()

        assertEquals(
            listOf(
                "inspect:content://fake/backup",
                "unlock:content://fake/backup",
                "prepare:replace_corresponding",
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
    fun unreadableBackupFailsWithoutPromptingOrPlanning() = runTest {
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
        assertEquals(BackupWorkflowStep.FAILED, workflow.state.value.step)
        assertEquals(BackupWorkflowError.UNLOCK_FAILED, workflow.state.value.error)
        assertFalse(gateway.events.any { it.startsWith("prepare:") })
        workflow.close()
    }

    @Test
    fun pickerCancellationAndWorkflowCloseResetAutomaticExports() = runTest {
        val gateway = FakeGateway()
        val workflow = SettingsBackupWorkflow(gateway, this, StandardTestDispatcher(testScheduler))
        val firstRequest = async { workflow.documentRequests.first() }
        workflow.beginExport()
        firstRequest.await()

        workflow.onExportDocumentResult(null)
        assertEquals(BackupWorkflowStep.READY, workflow.state.value.step)

        val secondRequest = async { workflow.documentRequests.first() }
        workflow.beginExport()
        secondRequest.await()
        workflow.close()
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
