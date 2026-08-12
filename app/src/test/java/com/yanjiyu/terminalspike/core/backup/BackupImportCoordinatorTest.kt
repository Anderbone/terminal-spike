package com.yanjiyu.terminalspike.core.backup

import com.yanjiyu.terminalspike.core.data.db.BackupImportRows
import com.yanjiyu.terminalspike.core.data.db.BackupSnapshotRows
import com.yanjiyu.terminalspike.core.data.db.EncryptedSecretEntity
import com.yanjiyu.terminalspike.core.data.db.SshCredentialEntity
import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeDataGate
import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeDataState
import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.CredentialStore
import com.yanjiyu.terminalspike.core.security.credential.EncryptedCredentialRecord
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupImportCoordinatorTest {
    @Test
    fun unlockedBackupTransfersOnceAndClosingWrapperDoesNotWipeThePreparedPlan() = runTest {
        val portable = PortableBackupSecret.copyAndWipe("transfer-once".encodeToByteArray())
        val unlocked = unlockedImport(credentialSnapshot(BackupMode.FULL, portable))
        val persistence = RecordingPersistence(emptyState())
        val coordinator = coordinator(persistence, RecordingEncryptingStore())

        val prepared = coordinator.prepare(unlocked, BackupImportStrategy.MERGE)
        unlocked.close()
        val result = coordinator.apply(prepared)

        assertEquals("ready", requireNotNull(persistence.appliedRows).secrets.single().stateCode)
        assertEquals(1, result.credentialsApplied)
        assertTrue(portable.isWiped)
        expectThrows<PreparedBackupImportConsumedException> {
            coordinator.prepare(unlocked, BackupImportStrategy.MERGE)
        }
    }

    @Test
    fun fullImportPreEncryptsAndWipesPlaintextBeforePersistence() = runTest {
        val portable = PortableBackupSecret.copyAndWipe("portable-password".encodeToByteArray())
        val incoming = credentialSnapshot(BackupMode.FULL, portable)
        val persistence = RecordingPersistence(emptyState())
        val credentialStore = RecordingEncryptingStore()
        val coordinator = coordinator(persistence, credentialStore)

        val prepared = coordinator.prepare(incoming, BackupImportStrategy.MERGE)
        val result = coordinator.apply(prepared)

        val written = requireNotNull(persistence.appliedRows).secrets.single()
        assertEquals("ready", written.stateCode)
        assertFalse(written.ciphertext!!.containsSubsequence("portable-password".encodeToByteArray()))
        assertTrue(portable.isWiped)
        assertTrue(credentialStore.borrowedPlaintexts.single().all { it == 0.toByte() })
        assertEquals(0, result.unavailableSecretPlaceholders)
        assertEquals(1, result.credentialsApplied)
    }

    @Test
    fun standardImportPreservesAnExistingSecretForTheSameOwner() = runTest {
        val existingCredential = credentialEntity()
        val existingSecret = readySecret()
        val current = credentialSnapshot(BackupMode.STANDARD, portable = null)
        val persistence = RecordingPersistence(
            state(
                snapshot = current,
                rows = emptyRows().copy(
                    credentials = listOf(existingCredential),
                    encryptedSecrets = listOf(existingSecret),
                ),
            ),
        )
        val coordinator = coordinator(persistence, RecordingEncryptingStore())
        val incoming = credentialSnapshot(BackupMode.STANDARD, portable = null)

        val result = coordinator.apply(
            coordinator.prepare(incoming, BackupImportStrategy.REPLACE_CORRESPONDING),
        )

        val rows = requireNotNull(persistence.appliedRows)
        assertTrue(rows.secrets.isEmpty())
        assertEquals(PASSWORD_SECRET_ID, rows.credentials.single().secretId)
        assertEquals(0, result.unavailableSecretPlaceholders)
    }

    @Test
    fun standardImportCreatesAnExplicitUnavailablePlaceholderForANewSecret() = runTest {
        val persistence = RecordingPersistence(emptyState())
        val coordinator = coordinator(persistence, RecordingEncryptingStore())

        val result = coordinator.apply(
            coordinator.prepare(
                credentialSnapshot(BackupMode.STANDARD, portable = null),
                BackupImportStrategy.MERGE,
            ),
        )

        val placeholder = requireNotNull(persistence.appliedRows).secrets.single()
        assertEquals("legacy_unavailable", placeholder.stateCode)
        assertEquals("backup_secret_not_exported", placeholder.failureCode)
        assertNull(placeholder.nonce)
        assertNull(placeholder.ciphertext)
        assertEquals(1, result.unavailableSecretPlaceholders)
    }

    @Test
    fun customFontFilesCommitWithRoomAndRollbackWhenRoomRejectsThePreview() = runTest {
        val successfulFonts = RecordingCustomFonts()
        val successful = BackupImportCoordinator(
            persistence = RecordingPersistence(emptyState()),
            credentials = RecordingEncryptingStore(),
            recoverySnapshots = BackupSnapshotProvider { BackupPayloadSnapshot(BackupMode.FULL) },
            recoveryMarkers = RecordingRecoveryMarkers(),
            authority = readyAuthority(),
            customFonts = successfulFonts,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val success = successful.apply(
            successful.prepare(fontSnapshot(), BackupImportStrategy.MERGE),
        )

        assertEquals(1, success.customFontsApplied)
        assertEquals(1, successfulFonts.commits)
        assertEquals(0, successfulFonts.rollbacks)

        val rejectedPersistence = RecordingPersistence(emptyState()).apply { staleOnApply = true }
        val rejectedFonts = RecordingCustomFonts()
        val rejected = BackupImportCoordinator(
            persistence = rejectedPersistence,
            credentials = RecordingEncryptingStore(),
            recoverySnapshots = BackupSnapshotProvider { BackupPayloadSnapshot(BackupMode.FULL) },
            recoveryMarkers = RecordingRecoveryMarkers(),
            authority = readyAuthority(),
            customFonts = rejectedFonts,
            ioDispatcher = Dispatchers.Unconfined,
        )

        expectThrows<BackupImportException.StalePreview> {
            rejected.apply(rejected.prepare(fontSnapshot(), BackupImportStrategy.MERGE))
        }
        assertEquals(0, rejectedFonts.commits)
        assertEquals(1, rejectedFonts.rollbacks)
    }

    @Test
    fun stalePreviewRejectsCiphertextOnlyBatchAndStillWipesPortableSecret() = runTest {
        val portable = PortableBackupSecret.copyAndWipe("do-not-persist".encodeToByteArray())
        val persistence = RecordingPersistence(emptyState()).apply { staleOnApply = true }
        val coordinator = coordinator(persistence, RecordingEncryptingStore())
        val prepared = coordinator.prepare(
            credentialSnapshot(BackupMode.FULL, portable),
            BackupImportStrategy.MERGE,
        )

        expectThrows<BackupImportException.StalePreview> { coordinator.apply(prepared) }

        assertNull(persistence.appliedRows)
        assertTrue(portable.isWiped)
    }

    @Test
    fun cancelledPrepareWaitingForStartupDoesNotCaptureAndWipesIncomingSecret() = runTest {
        val authority = AuthoritativeDataGate()
        val portable = PortableBackupSecret.copyAndWipe("cancelled-preview".encodeToByteArray())
        val persistence = RecordingPersistence(emptyState())
        val coordinator = coordinator(persistence, RecordingEncryptingStore(), authority)

        val preparing = async {
            coordinator.prepare(
                credentialSnapshot(BackupMode.FULL, portable),
                BackupImportStrategy.MERGE,
            )
        }
        runCurrent()

        assertEquals(0, persistence.captureCalls)
        preparing.cancelAndJoin()

        assertTrue(portable.isWiped)
        assertEquals(AuthoritativeDataState.Checking, authority.state.value)
    }

    @Test
    fun cancellationBeforePersistenceCommitDoesNotPublishGeneration() = runTest {
        val authority = readyAuthority()
        val applyStarted = CompletableDeferred<Unit>()
        val continueApply = CompletableDeferred<Unit>()
        val persistence = RecordingPersistence(emptyState()).apply {
            this.applyStarted = applyStarted
            this.continueApply = continueApply
        }
        val portable = PortableBackupSecret.copyAndWipe("cancel-before-commit".encodeToByteArray())
        val coordinator = coordinator(persistence, RecordingEncryptingStore(), authority)
        val prepared = coordinator.prepare(
            credentialSnapshot(BackupMode.FULL, portable),
            BackupImportStrategy.MERGE,
        )

        val applying = async { coordinator.apply(prepared) }
        applyStarted.await()
        applying.cancelAndJoin()

        assertNull(persistence.appliedRows)
        assertTrue(portable.isWiped)
        assertEquals(AuthoritativeDataState.Ready(0), authority.state.value)
    }

    @Test
    fun sharedAuthoritySerializesApplyAgainstAnotherCoordinatorCapture() = runTest {
        val authority = readyAuthority()
        val applyStarted = CompletableDeferred<Unit>()
        val continueApply = CompletableDeferred<Unit>()
        val applyingPersistence = RecordingPersistence(emptyState()).apply {
            this.applyStarted = applyStarted
            this.continueApply = continueApply
        }
        val readingPersistence = RecordingPersistence(emptyState())
        val applyingCoordinator = coordinator(
            applyingPersistence,
            RecordingEncryptingStore(),
            authority,
        )
        val readingCoordinator = coordinator(
            readingPersistence,
            RecordingEncryptingStore(),
            authority,
        )
        val prepared = applyingCoordinator.prepare(
            BackupPayloadSnapshot(BackupMode.STANDARD),
            BackupImportStrategy.MERGE,
        )

        val applying = async { applyingCoordinator.apply(prepared) }
        applyStarted.await()
        val preparing = async {
            readingCoordinator.prepare(
                BackupPayloadSnapshot(BackupMode.STANDARD),
                BackupImportStrategy.MERGE,
            )
        }
        runCurrent()

        assertEquals(0, readingPersistence.captureCalls)
        continueApply.complete(Unit)
        applying.await()
        preparing.await().close()

        assertEquals(1, readingPersistence.captureCalls)
        assertEquals(AuthoritativeDataState.Ready(1), authority.state.value)
    }

    @Test
    fun replaceClearsNullOptionalSettingsAndRestoresMixedNullsExactly() = runTest {
        val allNullPersistence = RecordingPersistence(emptyState())
        val allNullCoordinator = coordinator(allNullPersistence, RecordingEncryptingStore())

        allNullCoordinator.apply(
            allNullCoordinator.prepare(
                BackupPayloadSnapshot(
                    mode = BackupMode.STANDARD,
                    globalSettings = BackupGlobalSettings(),
                ),
                BackupImportStrategy.REPLACE_CORRESPONDING,
            ),
        )

        assertOptionalSettings(
            requireNotNull(allNullPersistence.appliedSettings),
            accent = "",
            terminalProfileId = "",
            keyboardProfileId = "",
            lastBackupMode = "",
        )

        val mixedPersistence = RecordingPersistence(emptyState())
        val mixedCoordinator = coordinator(mixedPersistence, RecordingEncryptingStore())
        mixedCoordinator.apply(
            mixedCoordinator.prepare(
                BackupPayloadSnapshot(
                    mode = BackupMode.STANDARD,
                    globalSettings = BackupGlobalSettings(
                        accentPreset = "violet",
                        defaultTerminalProfileId = null,
                        defaultKeyboardProfileId = null,
                        lastBackupMode = BackupMode.FULL,
                    ),
                ),
                BackupImportStrategy.REPLACE_CORRESPONDING,
            ),
        )

        assertOptionalSettings(
            requireNotNull(mixedPersistence.appliedSettings),
            accent = "violet",
            terminalProfileId = "",
            keyboardProfileId = "",
            lastBackupMode = "full",
        )
    }

    @Test
    fun replaceWritesAndClearsRecoveryMarkerAroundTheTransactionalApply() = runTest {
        val events = mutableListOf<String>()
        val persistence = RecordingPersistence(emptyState(), events)
        val markers = RecordingRecoveryMarkers(events)
        val coordinator = BackupImportCoordinator(
            persistence = persistence,
            credentials = RecordingEncryptingStore(),
            recoverySnapshots = BackupSnapshotProvider {
                events += "capture-recovery"
                BackupPayloadSnapshot(BackupMode.FULL)
            },
            recoveryMarkers = markers,
            authority = readyAuthority(),
            clock = BackupImportEpochClock { 10 },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val incoming = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            globalSettings = BackupGlobalSettings(accentPreset = "violet"),
        )

        val result = coordinator.apply(
            coordinator.prepare(incoming, BackupImportStrategy.REPLACE_CORRESPONDING),
        )

        assertEquals(listOf("capture-recovery", "write-recovery", "apply", "clear-recovery"), events)
        assertTrue(result.recoveryMarkerRemoved)
        assertFalse(markers.pending)
    }

    @Test
    fun committedReplacePublishesGenerationBeforeFailedMarkerCleanupAndFailsClosed() = runTest {
        val authority = readyAuthority()
        var generationDuringClear: Long? = null
        val markers = RecordingRecoveryMarkers().apply {
            failClear = true
            beforeClear = {
                generationDuringClear =
                    (authority.state.value as AuthoritativeDataState.Ready).generation
            }
        }
        val coordinator = BackupImportCoordinator(
            persistence = RecordingPersistence(emptyState()),
            credentials = RecordingEncryptingStore(),
            recoverySnapshots = BackupSnapshotProvider { BackupPayloadSnapshot(BackupMode.FULL) },
            recoveryMarkers = markers,
            authority = authority,
            clock = BackupImportEpochClock { 10 },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = coordinator.apply(
            coordinator.prepare(
                BackupPayloadSnapshot(BackupMode.STANDARD),
                BackupImportStrategy.REPLACE_CORRESPONDING,
            ),
        )

        assertEquals(1L, generationDuringClear)
        assertFalse(result.recoveryMarkerRemoved)
        assertTrue(markers.pending)
        assertEquals(AuthoritativeDataState.RecoveryRequired, authority.state.value)
    }

    @Test
    fun cancellationDuringPostCommitCleanupStillClearsMarkerAndKeepsPublishedGeneration() = runTest {
        val authority = readyAuthority()
        val clearStarted = CompletableDeferred<Unit>()
        val continueClear = CompletableDeferred<Unit>()
        var generationDuringClear: Long? = null
        val markers = RecordingRecoveryMarkers().apply {
            this.clearStarted = clearStarted
            this.continueClear = continueClear
            beforeClear = {
                generationDuringClear =
                    (authority.state.value as AuthoritativeDataState.Ready).generation
            }
        }
        val coordinator = BackupImportCoordinator(
            persistence = RecordingPersistence(emptyState()),
            credentials = RecordingEncryptingStore(),
            recoverySnapshots = BackupSnapshotProvider { BackupPayloadSnapshot(BackupMode.FULL) },
            recoveryMarkers = markers,
            authority = authority,
            clock = BackupImportEpochClock { 10 },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val prepared = coordinator.prepare(
            BackupPayloadSnapshot(BackupMode.STANDARD),
            BackupImportStrategy.REPLACE_CORRESPONDING,
        )

        val applying = async { coordinator.apply(prepared) }
        clearStarted.await()
        applying.cancel()
        continueClear.complete(Unit)
        applying.join()

        assertEquals(1L, generationDuringClear)
        assertFalse(markers.pending)
        assertEquals(AuthoritativeDataState.Ready(1), authority.state.value)
    }

    @Test
    fun pendingRecoveryCanBeAppliedWithoutOverwritingItsOnlyMarker() = runTest {
        val events = mutableListOf<String>()
        val portable = PortableBackupSecret.copyAndWipe("recover-me".encodeToByteArray())
        val markers = RecordingRecoveryMarkers(
            events = events,
            recovery = credentialSnapshot(BackupMode.FULL, portable),
        ).apply { pending = true }
        val persistence = RecordingPersistence(emptyState(), events)
        val authority = readyAuthority()
        val coordinator = BackupImportCoordinator(
            persistence = persistence,
            credentials = RecordingEncryptingStore(),
            recoverySnapshots = BackupSnapshotProvider {
                error("Recovery must not replace its only marker before committing")
            },
            recoveryMarkers = markers,
            authority = authority,
            clock = BackupImportEpochClock { 10 },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = requireNotNull(coordinator.recoverPending())

        assertEquals(listOf("read-recovery", "apply", "clear-recovery"), events)
        assertEquals("ready", requireNotNull(persistence.appliedRows).secrets.single().stateCode)
        assertTrue(portable.isWiped)
        assertTrue(result.recoveryMarkerRemoved)
        assertFalse(coordinator.hasPendingRecovery())
        assertEquals(AuthoritativeDataState.Ready(1), authority.state.value)
    }

    @Test
    fun exactRecoveryClearsNullOptionalSettings() = runTest {
        val markers = RecordingRecoveryMarkers(
            recovery = BackupPayloadSnapshot(
                mode = BackupMode.FULL,
                globalSettings = BackupGlobalSettings(),
            ),
        ).apply { pending = true }
        val persistence = RecordingPersistence(emptyState())
        val coordinator = BackupImportCoordinator(
            persistence = persistence,
            credentials = RecordingEncryptingStore(),
            recoverySnapshots = BackupSnapshotProvider { error("not used") },
            recoveryMarkers = markers,
            authority = readyAuthority(),
            clock = BackupImportEpochClock { 10 },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = requireNotNull(coordinator.recoverPending())

        assertTrue(result.recoveryMarkerRemoved)
        assertOptionalSettings(
            requireNotNull(persistence.appliedSettings),
            accent = "",
            terminalProfileId = "",
            keyboardProfileId = "",
            lastBackupMode = "",
        )
    }

    @Test
    fun startupRecoveryDoesNotReenterGateAndFailsWhenMarkerCleanupFails() = runTest {
        val authority = AuthoritativeDataGate()
        val markers = RecordingRecoveryMarkers(
            recovery = BackupPayloadSnapshot(BackupMode.FULL),
        ).apply {
            pending = true
            failClear = true
        }
        val persistence = RecordingPersistence(emptyState())
        val coordinator = BackupImportCoordinator(
            persistence = persistence,
            credentials = RecordingEncryptingStore(),
            recoverySnapshots = BackupSnapshotProvider { error("not used") },
            recoveryMarkers = markers,
            authority = authority,
            clock = BackupImportEpochClock { 10 },
            ioDispatcher = Dispatchers.Unconfined,
        )

        authority.resolveStartup(
            hasPendingRecovery = coordinator::hasPendingRecovery,
            recoverPending = {
                coordinator.recoverPendingForStartup()?.recoveryMarkerRemoved == true
            },
        )

        assertTrue(persistence.appliedRows != null)
        assertTrue(markers.pending)
        assertTrue(authority.state.value is AuthoritativeDataState.RecoveryFailed)
    }

    @Test
    fun destructiveReplaceRefusesToOverwriteAnExistingRecoveryMarker() = runTest {
        val events = mutableListOf<String>()
        val markers = RecordingRecoveryMarkers(events).apply { pending = true }
        val persistence = RecordingPersistence(emptyState(), events)
        val portable = PortableBackupSecret.copyAndWipe("blocked-replace".encodeToByteArray())
        var recoveryCaptures = 0
        val coordinator = BackupImportCoordinator(
            persistence = persistence,
            credentials = RecordingEncryptingStore(),
            recoverySnapshots = BackupSnapshotProvider {
                recoveryCaptures += 1
                BackupPayloadSnapshot(BackupMode.FULL)
            },
            recoveryMarkers = markers,
            authority = readyAuthority(),
            clock = BackupImportEpochClock { 10 },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val prepared = coordinator.prepare(
            credentialSnapshot(BackupMode.FULL, portable),
            BackupImportStrategy.REPLACE_CORRESPONDING,
        )

        expectThrows<BackupImportException.PendingRecoveryExists> {
            coordinator.apply(prepared)
        }

        assertTrue(markers.pending)
        assertEquals(0, recoveryCaptures)
        assertNull(persistence.appliedRows)
        assertTrue(events.isEmpty())
        assertTrue(portable.isWiped)
    }

    @Test
    fun atomicMarkerWriteRaceIsTypedAndDoesNotClearTheWinningMarker() = runTest {
        val markers = RecordingRecoveryMarkers().apply { rejectNextWriteAsAlreadyPending = true }
        val persistence = RecordingPersistence(emptyState())
        var recoveryCaptures = 0
        val coordinator = BackupImportCoordinator(
            persistence = persistence,
            credentials = RecordingEncryptingStore(),
            recoverySnapshots = BackupSnapshotProvider {
                recoveryCaptures += 1
                BackupPayloadSnapshot(BackupMode.FULL)
            },
            recoveryMarkers = markers,
            authority = readyAuthority(),
            clock = BackupImportEpochClock { 10 },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val prepared = coordinator.prepare(
            BackupPayloadSnapshot(BackupMode.STANDARD),
            BackupImportStrategy.REPLACE_CORRESPONDING,
        )

        val error = expectThrows<BackupImportException.PendingRecoveryExists> {
            coordinator.apply(prepared)
        }

        assertTrue(error.cause is BackupRecoveryMarkerException.AlreadyPending)
        assertEquals(1, recoveryCaptures)
        assertTrue(markers.pending)
        assertNull(persistence.appliedRows)
    }

    @Test
    fun failedPendingRecoveryKeepsTheEarlierMarker() = runTest {
        val events = mutableListOf<String>()
        val markers = RecordingRecoveryMarkers(
            events = events,
            recovery = BackupPayloadSnapshot(BackupMode.FULL),
        ).apply { pending = true }
        val persistence = RecordingPersistence(emptyState(), events).apply { staleOnApply = true }
        val authority = readyAuthority()
        val coordinator = BackupImportCoordinator(
            persistence = persistence,
            credentials = RecordingEncryptingStore(),
            recoverySnapshots = BackupSnapshotProvider {
                error("Pending recovery must never be overwritten")
            },
            recoveryMarkers = markers,
            authority = authority,
            clock = BackupImportEpochClock { 10 },
            ioDispatcher = Dispatchers.Unconfined,
        )

        expectThrows<BackupImportException.StalePreview> { coordinator.recoverPending() }

        assertTrue(markers.pending)
        assertEquals(listOf("read-recovery", "apply"), events)
        assertEquals(AuthoritativeDataState.RecoveryRequired, authority.state.value)
    }

    @Test
    fun pendingRecoveryRestoresCustomFontProfileAndItsHostWithoutExternalProjection() = runTest {
        val customProfileId = "50000000-0000-4000-8000-000000000005"
        val customHostId = "60000000-0000-4000-8000-000000000006"
        val events = mutableListOf<String>()
        val markers = RecordingRecoveryMarkers(
            events = events,
            recovery = BackupPayloadSnapshot(
                mode = BackupMode.FULL,
                hostProfiles = listOf(recoveryHost(customHostId, customProfileId)),
                terminalProfiles = listOf(recoveryTerminal(customProfileId)),
                globalSettings = BackupGlobalSettings(defaultTerminalProfileId = customProfileId),
            ),
        ).apply { pending = true }
        val persistence = RecordingPersistence(emptyState(), events)
        val coordinator = BackupImportCoordinator(
            persistence = persistence,
            credentials = RecordingEncryptingStore(),
            recoverySnapshots = BackupSnapshotProvider { error("not used") },
            recoveryMarkers = markers,
            authority = readyAuthority(),
            clock = BackupImportEpochClock { 10 },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = requireNotNull(coordinator.recoverPending())

        val rows = requireNotNull(persistence.appliedRows)
        assertEquals(customProfileId, rows.terminalProfiles.single().id)
        assertEquals(customHostId, rows.hosts.single().id)
        assertEquals(1, result.terminalProfilesApplied)
        assertEquals(1, result.hostsApplied)
        assertEquals(0, result.incompatibleRecords)
        assertEquals(listOf("read-recovery", "apply", "clear-recovery"), events)
    }

    @Test
    fun replaceIsTypedBlockedWhenCurrentSecretCannotBeCapturedForRecovery() = runTest {
        val placeholder = readySecret().copy(
            envelopeVersion = 0,
            keyVersion = 0,
            nonce = null,
            ciphertext = null,
            stateCode = "legacy_unavailable",
            failureCode = "backup_secret_not_exported",
            legacyId = "portable-backup:$CREDENTIAL_ID",
        )
        val persistence = RecordingPersistence(
            state(
                snapshot = credentialSnapshot(BackupMode.STANDARD, portable = null),
                rows = emptyRows().copy(
                    credentials = listOf(credentialEntity()),
                    encryptedSecrets = listOf(placeholder),
                ),
            ),
        )
        val markers = RecordingRecoveryMarkers()
        var recoveryCaptures = 0
        val coordinator = BackupImportCoordinator(
            persistence = persistence,
            credentials = RecordingEncryptingStore(),
            recoverySnapshots = BackupSnapshotProvider {
                recoveryCaptures += 1
                error("A non-representable recovery snapshot must not be requested")
            },
            recoveryMarkers = markers,
            authority = readyAuthority(),
            clock = BackupImportEpochClock { 10 },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val prepared = coordinator.prepare(
            BackupPayloadSnapshot(BackupMode.STANDARD),
            BackupImportStrategy.REPLACE_CORRESPONDING,
        )

        val error = expectThrows<BackupImportException.RecoverySnapshotUnavailable> {
            coordinator.apply(prepared)
        }

        assertEquals(listOf(PASSWORD_SECRET_ID), error.secretIds)
        assertEquals(0, recoveryCaptures)
        assertFalse(markers.pending)
        assertNull(persistence.appliedRows)
    }

    private suspend fun coordinator(
        persistence: RecordingPersistence,
        credentials: CredentialStore,
    ) = coordinator(persistence, credentials, readyAuthority())

    private fun coordinator(
        persistence: RecordingPersistence,
        credentials: CredentialStore,
        authority: AuthoritativeDataGate,
    ) = BackupImportCoordinator(
        persistence = persistence,
        credentials = credentials,
        recoverySnapshots = BackupSnapshotProvider { BackupPayloadSnapshot(BackupMode.FULL) },
        recoveryMarkers = RecordingRecoveryMarkers(),
        authority = authority,
        clock = BackupImportEpochClock { 10 },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private suspend fun readyAuthority() = AuthoritativeDataGate().also { authority ->
        authority.resolveStartup(
            hasPendingRecovery = { false },
            recoverPending = { false },
        )
    }

    private fun assertOptionalSettings(
        actual: AppSettings,
        accent: String,
        terminalProfileId: String,
        keyboardProfileId: String,
        lastBackupMode: String,
    ) {
        assertEquals(accent, actual.accentPreset)
        assertEquals(terminalProfileId, actual.defaultTerminalProfileId)
        assertEquals(keyboardProfileId, actual.defaultKeyboardProfileId)
        assertEquals(lastBackupMode, actual.lastBackupMode)
    }

    private fun emptyState() = state(BackupPayloadSnapshot(BackupMode.STANDARD), emptyRows())

    private fun state(
        snapshot: BackupPayloadSnapshot,
        rows: BackupSnapshotRows,
    ) = CapturedBackupImportState(
        rows = rows,
        settings = settings(),
        snapshot = snapshot,
        revision = BackupImportStateRevision("revision-a"),
    )

    private fun emptyRows() = BackupSnapshotRows(
        terminalProfiles = emptyList(),
        keyboardProfiles = emptyList(),
        keyboardKeys = emptyList(),
        keyIdentities = emptyList(),
        credentials = emptyList(),
        hosts = emptyList(),
        knownHosts = emptyList(),
        snippets = emptyList(),
        encryptedSecrets = emptyList(),
    )

    private fun credentialSnapshot(
        mode: BackupMode,
        portable: PortableBackupSecret?,
    ) = BackupPayloadSnapshot(
        mode = mode,
        credentials = listOf(
            BackupCredentialRecord(
                metadata = SshCredential(
                    id = CREDENTIAL_ID,
                    displayName = "Saved password",
                    authentication = SshAuthentication.Password(PASSWORD_SECRET_ID),
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 2,
                ),
                portableSecret = portable,
            ),
        ),
    )

    private fun fontSnapshot(): BackupPayloadSnapshot {
        val bytes = ByteArray(1_024) { index -> (index * 13).toByte() }
        val fontId = "custom_" + MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        return BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            terminalProfiles = listOf(
                TerminalProfile(
                    id = DEFAULT_TERMINAL_ID,
                    name = "Portable terminal",
                    themeId = "midnight",
                    fontId = fontId,
                    fontSizeSp = 14f,
                    lineHeightMultiplier = 1f,
                    letterSpacingEm = 0f,
                    cursorStyle = CursorStyle.BLOCK,
                    scrollbackLines = 10_000,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 2,
                ),
            ),
            customFonts = listOf(BackupCustomFont.copyOf(fontId, "Portable Mono", bytes)),
        )
    }

    private fun unlockedImport(snapshot: BackupPayloadSnapshot) = PreparedBackupImport(
        BackupArchiveReadResult(
            header = BackupEnvelopeHeader(
                envelopeSchemaVersion = BackupEnvelopeFormat.ENVELOPE_SCHEMA_VERSION,
                mode = snapshot.mode,
                createdAtEpochMillis = 1,
                appVersion = BackupAppVersion(1, "test"),
                payloadSchemaVersion = BackupPayloadFormat.SCHEMA_VERSION,
                kdfIterations = BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
                salt = ByteArray(BackupEnvelopeFormat.KDF_SALT_BYTES),
                nonce = ByteArray(BackupEnvelopeFormat.GCM_NONCE_BYTES),
                compatibility = BackupCompatibility(26, 35, 0),
                unknownOptionalFields = emptyList(),
            ),
            payload = BackupPayloadReadResult(snapshot, BackupPayloadCompatibilityReport()),
        ),
    )

    private fun credentialEntity() = SshCredentialEntity(
        id = CREDENTIAL_ID,
        name = "Saved password",
        kindCode = "password",
        secretId = PASSWORD_SECRET_ID,
        keyIdentityId = null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun readySecret() = EncryptedSecretEntity(
        id = PASSWORD_SECRET_ID,
        kindCode = "password",
        envelopeVersion = 2,
        keyVersion = 2,
        nonce = ByteArray(12) { 1 },
        ciphertext = ByteArray(17) { 2 },
        stateCode = "ready",
        failureCode = null,
        legacyId = null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun recoveryTerminal(id: String) = TerminalProfile(
        id = id,
        name = "Custom terminal",
        themeId = "custom_theme_reference",
        fontId = "custom_0123456789abcdef",
        fontSizeSp = 14f,
        lineHeightMultiplier = 1f,
        letterSpacingEm = 0f,
        cursorStyle = CursorStyle.BLOCK,
        scrollbackLines = 10_000,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun recoveryHost(id: String, terminalProfileId: String) = HostProfile(
        id = id,
        displayName = "Custom host",
        hostname = "custom.example",
        port = 22,
        username = "operator",
        protocol = ConnectionProtocol.SSH,
        credentialId = null,
        terminalProfileId = terminalProfileId,
        keyboardProfileId = null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun settings() = AppSettings.newBuilder()
        .setSchemaRevision(1)
        .setThemeMode(AppSettings.ThemeMode.THEME_MODE_SYSTEM)
        .setAccentPreset("mint")
        .setDefaultTerminalProfileId(DEFAULT_TERMINAL_ID)
        .setDefaultKeyboardProfileId(DEFAULT_KEYBOARD_ID)
        .setKeepaliveIntervalSeconds(30)
        .setReconnectMaxAttempts(5)
        .setNotificationPrivacyEnabled(true)
        .setAppLockMode(AppSettings.AppLockMode.APP_LOCK_MODE_OFF)
        .setOsc52Policy(AppSettings.Osc52Policy.OSC52_POLICY_DISABLED)
        .setMultilinePasteConfirmationEnabled(true)
        .setLastBackupMode("standard")
        .build()

    private class RecordingPersistence(
        private val captured: CapturedBackupImportState,
        private val events: MutableList<String>? = null,
    ) : BackupImportPersistence {
        var staleOnApply = false
        var captureCalls = 0
        var applyStarted: CompletableDeferred<Unit>? = null
        var continueApply: CompletableDeferred<Unit>? = null
        var appliedRows: BackupImportRows? = null
        var appliedSettings: AppSettings? = null

        override suspend fun capture(): CapturedBackupImportState {
            captureCalls += 1
            return captured
        }

        override suspend fun apply(
            expectedRevision: BackupImportStateRevision,
            expectedSettings: AppSettings,
            rows: BackupImportRows,
            restoredSettings: AppSettings?,
        ) {
            events?.add("apply")
            applyStarted?.complete(Unit)
            continueApply?.await()
            if (staleOnApply) throw BackupImportException.StalePreview()
            assertEquals(captured.revision, expectedRevision)
            assertEquals(captured.settings, expectedSettings)
            appliedRows = rows
            appliedSettings = restoredSettings
        }
    }

    private class RecordingEncryptingStore : CredentialStore {
        val references = mutableListOf<CredentialSecretReference>()
        val borrowedPlaintexts = mutableListOf<ByteArray>()

        override suspend fun encryptAndWipe(
            reference: CredentialSecretReference,
            secret: ByteArray,
        ): EncryptedCredentialRecord = try {
            references += reference
            borrowedPlaintexts += secret
            EncryptedCredentialRecord(
                secretId = reference.secretId.value,
                kindCode = reference.kind.wireCode,
                envelopeVersion = 2,
                keyVersion = 2,
                nonce = ByteArray(12) { 7 },
                ciphertext = MessageDigest.getInstance("SHA-256").digest(secret),
            )
        } finally {
            secret.fill(0)
        }

        override suspend fun saveAndWipe(reference: CredentialSecretReference, secret: ByteArray) =
            error("not used")

        override suspend fun <T> withSecret(
            reference: CredentialSecretReference,
            use: suspend (ByteArray) -> T,
        ): T = error("not used")

        override suspend fun delete(secretId: SecretId): Boolean = error("not used")
    }

    private class RecordingCustomFonts : BackupCustomFontPersistence {
        var commits = 0
        var rollbacks = 0

        override fun prepare(fonts: List<BackupCustomFont>): PreparedBackupCustomFontImport {
            assertEquals(1, fonts.size)
            return object : PreparedBackupCustomFontImport {
                override fun commit() {
                    commits += 1
                }

                override fun rollback() {
                    rollbacks += 1
                }
            }
        }

        override fun reconcilePending(referencedFontIds: Set<String>) = Unit
    }

    private class RecordingRecoveryMarkers(
        private val events: MutableList<String>? = null,
        private val recovery: BackupPayloadSnapshot? = null,
    ) : BackupRecoveryMarkerStore {
        var pending = false
        var rejectNextWriteAsAlreadyPending = false
        var failClear = false
        var beforeClear: (() -> Unit)? = null
        var clearStarted: CompletableDeferred<Unit>? = null
        var continueClear: CompletableDeferred<Unit>? = null

        override suspend fun writeAndWipe(snapshot: BackupPayloadSnapshot) {
            try {
                if (pending || rejectNextWriteAsAlreadyPending) {
                    rejectNextWriteAsAlreadyPending = false
                    pending = true
                    throw BackupRecoveryMarkerException.AlreadyPending()
                }
                events?.add("write-recovery")
                pending = true
            } finally {
                snapshot.close()
            }
        }

        override suspend fun read(): BackupPayloadSnapshot? {
            events?.add("read-recovery")
            return recovery
        }

        override suspend fun clear() {
            events?.add("clear-recovery")
            beforeClear?.invoke()
            clearStarted?.complete(Unit)
            continueClear?.await()
            if (failClear) throw IOException("Injected marker cleanup failure")
            pending = false
        }

        override fun hasPendingRecovery(): Boolean = pending
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return (0..size - candidate.size).any { start ->
            candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
        }
    }

    private suspend inline fun <reified T : Throwable> expectThrows(noinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.name}, got ${error::class.java.name}", error)
        }
        throw AssertionError("Expected ${T::class.java.name}.")
    }

    private companion object {
        const val CREDENTIAL_ID = "10000000-0000-4000-8000-000000000001"
        const val PASSWORD_SECRET_ID = "20000000-0000-4000-8000-000000000002"
        const val DEFAULT_TERMINAL_ID = "30000000-0000-4000-8000-000000000003"
        const val DEFAULT_KEYBOARD_ID = "40000000-0000-4000-8000-000000000004"
    }
}
