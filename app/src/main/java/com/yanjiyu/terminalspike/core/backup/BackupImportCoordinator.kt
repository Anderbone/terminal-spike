package com.yanjiyu.terminalspike.core.backup

import androidx.room.withTransaction
import com.yanjiyu.terminalspike.core.data.credential.EncryptedSecretState
import com.yanjiyu.terminalspike.core.data.credential.toReadyEntity
import com.yanjiyu.terminalspike.core.data.credential.toStoredEncryptedCredentialRecord
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.db.BackupImportExactDeletes
import com.yanjiyu.terminalspike.core.data.db.BackupImportRows
import com.yanjiyu.terminalspike.core.data.db.BackupSnapshotRows
import com.yanjiyu.terminalspike.core.data.db.EncryptedSecretEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileWithKeys
import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeDataGate
import com.yanjiyu.terminalspike.core.data.repository.toDomainModel
import com.yanjiyu.terminalspike.core.data.repository.toEntity
import com.yanjiyu.terminalspike.core.data.repository.toRows
import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsRepository
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.security.credential.CredentialId
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.CredentialStore
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@JvmInline
value class BackupImportStateRevision internal constructor(val sha256: String)

sealed class BackupImportException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class StalePreview : BackupImportException(
        "Connections or settings changed after the import preview was created.",
    )

    class SettingsRollbackFailed(
        val applyFailure: Throwable,
        val rollbackFailure: Throwable,
    ) : BackupImportException(
        "Import failed and the previous settings could not be restored automatically.",
        rollbackFailure,
    ) {
        init {
            addSuppressed(applyFailure)
        }
    }

    class PreparedImportAlreadyConsumed : BackupImportException(
        "This import preview has already been applied or closed.",
    )

    class PendingRecoveryExists(cause: Throwable? = null) : BackupImportException(
        "A previous Replace import still has pending recovery data.",
        cause,
    )

    class RecoverySnapshotUnavailable(
        secretIds: List<String>,
    ) : BackupImportException(
        "Replace cannot start because the current credentials cannot be captured for recovery.",
    ) {
        val secretIds: List<String> = secretIds.toList()
    }
}

data class BackupImportPlanPreview(
    val strategy: BackupImportStrategy,
    val conflicts: Int,
    val skippedRecords: Int,
    val incompatibleRecords: Int,
    val externallyResolvedReferences: Int,
    val recordIdRewrites: Int,
    val secretIdRewrites: Int,
    val destructive: Boolean,
)

/** Caller-owned import decision. Applying or closing it wipes any remaining portable secrets. */
class PreparedBackupImportPlan internal constructor(
    internal val plan: BackupImportPlan,
    internal val expectedRevision: BackupImportStateRevision,
    internal val expectedSettings: AppSettings,
    internal val currentRows: BackupSnapshotRows,
) : AutoCloseable {
    private val ownership = AtomicReference(PlanOwnership.OWNED_BY_CALLER)

    val preview = BackupImportPlanPreview(
        strategy = plan.strategy,
        conflicts = plan.conflicts.count {
            it.incomingKind != BackupImportRecordKind.GLOBAL_SETTINGS
        },
        skippedRecords = plan.skippedIncomingRecordIds.size,
        incompatibleRecords = plan.incompatibleIncomingRecords.map { it.recordId }.distinct().size,
        externallyResolvedReferences = plan.externallyResolvedReferences.size,
        recordIdRewrites = plan.recordIdRewrites.size,
        secretIdRewrites = plan.secretIdRewrites.size,
        destructive = plan.requiresRecoverySnapshot,
    )

    internal fun claimForApply() {
        if (!ownership.compareAndSet(PlanOwnership.OWNED_BY_CALLER, PlanOwnership.OWNED_BY_APPLY)) {
            throw BackupImportException.PreparedImportAlreadyConsumed()
        }
    }

    internal fun closeAfterApply() {
        if (ownership.compareAndSet(PlanOwnership.OWNED_BY_APPLY, PlanOwnership.CLOSED)) {
            plan.close()
        }
    }

    override fun close() {
        if (ownership.compareAndSet(PlanOwnership.OWNED_BY_CALLER, PlanOwnership.CLOSED)) {
            plan.close()
        }
    }

    private enum class PlanOwnership {
        OWNED_BY_CALLER,
        OWNED_BY_APPLY,
        CLOSED,
    }
}

data class BackupImportResult(
    val strategy: BackupImportStrategy,
    val hostsApplied: Int,
    val credentialsApplied: Int,
    val sshKeysApplied: Int,
    val knownHostsApplied: Int,
    val snippetsApplied: Int,
    val terminalProfilesApplied: Int,
    val terminalThemesApplied: Int,
    val keyboardProfilesApplied: Int,
    val unavailableSecretPlaceholders: Int,
    val skippedRecords: Int,
    val incompatibleRecords: Int,
    /** False keeps normal authority gated until recovery-marker cleanup is confirmed or resolved. */
    val recoveryMarkerRemoved: Boolean,
    val customFontsApplied: Int = 0,
)

fun interface BackupImportEpochClock {
    fun nowEpochMillis(): Long

    companion object {
        val SYSTEM = BackupImportEpochClock(System::currentTimeMillis)
    }
}

internal data class CapturedBackupImportState(
    val rows: BackupSnapshotRows,
    val settings: AppSettings,
    val snapshot: BackupPayloadSnapshot,
    val revision: BackupImportStateRevision,
)

internal interface BackupImportPersistence {
    suspend fun capture(): CapturedBackupImportState

    /** Implementations must commit [rows] in one Room transaction or make no Room changes. */
    suspend fun apply(
        expectedRevision: BackupImportStateRevision,
        expectedSettings: AppSettings,
        rows: BackupImportRows,
        restoredSettings: AppSettings?,
    )
}

/**
 * End-to-end import boundary used after an archive has authenticated successfully.
 *
 * Preview owns the incoming snapshot. Apply is serialized, pre-encrypts every portable secret,
 * persists a device-encrypted recovery marker for Replace, and then performs one stale-checked Room
 * transaction. DataStore is updated before that transaction commits; a DataStore failure therefore
 * rolls Room back immediately, while a later Room/commit failure restores the old settings.
 */
class BackupImportCoordinator internal constructor(
    private val persistence: BackupImportPersistence,
    private val credentials: CredentialStore,
    private val recoverySnapshots: BackupSnapshotProvider,
    private val recoveryMarkers: BackupRecoveryMarkerStore,
    private val authority: AuthoritativeDataGate,
    private val customFonts: BackupCustomFontPersistence = BackupCustomFontPersistence.NONE,
    private val planner: BackupImportPlanner = BackupImportPlanner(),
    private val clock: BackupImportEpochClock = BackupImportEpochClock.SYSTEM,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    internal constructor(
        database: AppDatabase,
        settings: AppSettingsRepository,
        credentials: CredentialStore,
        recoverySnapshots: BackupSnapshotProvider,
        recoveryMarkers: BackupRecoveryMarkerStore,
        authority: AuthoritativeDataGate,
        customFonts: BackupCustomFontPersistence = BackupCustomFontPersistence.NONE,
        planner: BackupImportPlanner = BackupImportPlanner(),
        clock: BackupImportEpochClock = BackupImportEpochClock.SYSTEM,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        persistence = RoomBackupImportPersistence(database, settings),
        credentials = credentials,
        recoverySnapshots = recoverySnapshots,
        recoveryMarkers = recoveryMarkers,
        authority = authority,
        customFonts = customFonts,
        planner = planner,
        clock = clock,
        ioDispatcher = ioDispatcher,
    )

    /** Consumes an authenticated unlock result without exposing a reusable raw snapshot. */
    suspend fun prepare(
        incoming: PreparedBackupImport,
        strategy: BackupImportStrategy,
    ): PreparedBackupImportPlan = prepare(incoming.takeSnapshot(), strategy)

    suspend fun prepare(
        incoming: BackupPayloadSnapshot,
        strategy: BackupImportStrategy,
    ): PreparedBackupImportPlan {
        var prepared: PreparedBackupImportPlan? = null
        try {
            val result = withContext(ioDispatcher) {
                authority.withRead {
                    val current = persistence.capture()
                    try {
                        val plan = planner.plan(current.snapshot, incoming, strategy)
                        PreparedBackupImportPlan(
                            plan = plan,
                            expectedRevision = current.revision,
                            expectedSettings = current.settings,
                            currentRows = current.rows,
                        )
                    } finally {
                        current.snapshot.close()
                    }
                }
            }
            prepared = result
            return result
        } finally {
            if (prepared == null) incoming.close()
        }
    }

    suspend fun apply(prepared: PreparedBackupImportPlan): BackupImportResult =
        withContext(ioDispatcher) {
            authority.withMutation {
                try {
                    prepared.claimForApply()
                    applyClaimed(
                        prepared,
                        createRecoveryMarker = true,
                        exactRecovery = false,
                        onPersistenceApplied = { markCommitted() },
                    ).also { result ->
                        if (
                            !result.recoveryMarkerRemoved ||
                            pendingRecoveryCannotBeRuledOut()
                        ) {
                            requireRecovery()
                        }
                    }
                } catch (error: Throwable) {
                    if (pendingRecoveryCannotBeRuledOut()) requireRecovery()
                    throw error
                }
            }
        }

    fun hasPendingRecovery(): Boolean = recoveryMarkers.hasPendingRecovery()

    /**
     * Re-applies the authenticated pre-Replace snapshot after an interrupted import. The existing
     * marker remains available until recovery commits successfully.
     */
    suspend fun recoverPending(): BackupImportResult? = withContext(ioDispatcher) {
        authority.withMutation {
            try {
                recoverPendingWithoutGate(
                    onPersistenceApplied = { markCommitted() },
                ).also { result ->
                    if (
                        result?.recoveryMarkerRemoved == false ||
                        pendingRecoveryCannotBeRuledOut()
                    ) {
                        requireRecovery()
                    }
                }
            } catch (error: Throwable) {
                if (pendingRecoveryCannotBeRuledOut()) requireRecovery()
                throw error
            }
        }
    }

    /**
     * Startup-only exact recovery. [AuthoritativeDataGate.resolveStartup] already owns the gate,
     * so entering [AuthoritativeDataGate.withMutation] here would deadlock. A non-null return is
     * startup-safe: the exact state committed and its recovery marker is no longer pending.
     */
    internal suspend fun recoverPendingForStartup(): BackupImportResult? =
        withContext(ioDispatcher) {
            val pendingBefore = recoveryMarkers.hasPendingRecovery()
            recoverPendingWithoutGate(onPersistenceApplied = {}).also { result ->
                val recoveryCompleted = result?.let { restored ->
                    restored.recoveryMarkerRemoved && !recoveryMarkers.hasPendingRecovery()
                } == true
                check(
                    (!pendingBefore && result == null) || recoveryCompleted,
                ) { "Pending backup recovery committed but its marker could not be cleared." }
            }
        }

    private suspend fun recoverPendingWithoutGate(
        onPersistenceApplied: () -> Unit,
    ): BackupImportResult? {
        val recovery = recoveryMarkers.read() ?: return null
        val current = try {
            persistence.capture()
        } catch (error: Throwable) {
            recovery.close()
            throw error
        }
        val prepared = try {
            PreparedBackupImportPlan(
                plan = planner.planInternalRecovery(
                    current = current.snapshot,
                    incoming = recovery,
                ),
                expectedRevision = current.revision,
                expectedSettings = current.settings,
                currentRows = current.rows,
            )
        } catch (error: Throwable) {
            recovery.close()
            throw error
        } finally {
            current.snapshot.close()
        }
        prepared.claimForApply()
        val restored = applyClaimed(
            prepared,
            createRecoveryMarker = false,
            exactRecovery = true,
            onPersistenceApplied = onPersistenceApplied,
        )
        val removed = clearRecoveryMarkerAfterCommit()
        return restored.copy(recoveryMarkerRemoved = removed)
    }

    private suspend fun applyClaimed(
        prepared: PreparedBackupImportPlan,
        createRecoveryMarker: Boolean,
        exactRecovery: Boolean,
        onPersistenceApplied: () -> Unit,
    ): BackupImportResult {
        var recoveryWritten = false
        var persistenceApplied = false
        var preparedFonts: PreparedBackupCustomFontImport = PreparedBackupCustomFontImport.NONE
        var fontsPrepared = false
        try {
            if (createRecoveryMarker && recoveryMarkers.hasPendingRecovery()) {
                throw BackupImportException.PendingRecoveryExists()
            }
            if (createRecoveryMarker && prepared.plan.requiresRecoverySnapshot) {
                val unavailableSecretIds = prepared.currentRows.nonRecoverableReferencedSecretIds()
                if (unavailableSecretIds.isNotEmpty()) {
                    throw BackupImportException.RecoverySnapshotUnavailable(unavailableSecretIds)
                }
            }
            val built = BackupImportRowsBuilder(credentials, clock).build(
                plan = prepared.plan,
                currentRows = prepared.currentRows,
            )
            preparedFonts = customFonts.prepare(prepared.plan.snapshotToApply.customFonts)
            fontsPrepared = true
            val restoredSettings = if (prepared.plan.applyGlobalSettings) {
                prepared.plan.snapshotToApply.globalSettings.toRestoredAppSettings(
                    prepared.expectedSettings,
                )
            } else {
                null
            }
            if (createRecoveryMarker && prepared.plan.requiresRecoverySnapshot) {
                val recovery = recoverySnapshots.create(BackupMode.FULL)
                try {
                    recoveryMarkers.writeAndWipe(recovery)
                } catch (error: BackupRecoveryMarkerException.AlreadyPending) {
                    throw BackupImportException.PendingRecoveryExists(error)
                }
                recoveryWritten = true
            }

            val rows = if (exactRecovery) {
                built.rows.copy(
                    exactRecoveryDeletes = exactRecoveryDeletes(
                        current = prepared.currentRows,
                        target = prepared.plan.snapshotToApply,
                    ),
                )
            } else {
                built.rows
            }
            persistence.apply(
                expectedRevision = prepared.expectedRevision,
                expectedSettings = prepared.expectedSettings,
                rows = rows,
                restoredSettings = restoredSettings,
            )
            persistenceApplied = true
            onPersistenceApplied()
            val markerRemoved = !recoveryWritten || clearRecoveryMarkerAfterCommit()
            if (exactRecovery) {
                customFonts.reconcilePending(
                    prepared.plan.snapshotToApply.terminalProfiles.asSequence()
                        .map { it.fontId }
                        .filter { it.startsWith(CUSTOM_FONT_ID_PREFIX) }
                        .toSet(),
                )
            } else if (markerRemoved) {
                // A committed Room graph is already authoritative. A leftover non-secret font
                // journal is safely reconciled on the next cutover and must not misreport the
                // transactional import as failed.
                runCatching(preparedFonts::commit)
            }
            return prepared.plan.toResult(built.unavailableSecretPlaceholders, markerRemoved)
        } catch (error: Throwable) {
            if (fontsPrepared && !persistenceApplied) {
                runCatching(preparedFonts::rollback)
            }
            if (
                recoveryWritten &&
                !persistenceApplied &&
                error !is BackupImportException.SettingsRollbackFailed
            ) {
                withContext(NonCancellable) {
                    runCatching { recoveryMarkers.clear() }
                }
            }
            throw error
        } finally {
            prepared.closeAfterApply()
        }
    }

    private suspend fun clearRecoveryMarkerAfterCommit(): Boolean {
        val removed = withContext(NonCancellable) { clearRecoveryMarker() }
        currentCoroutineContext().ensureActive()
        return removed
    }

    private suspend fun clearRecoveryMarker(): Boolean = try {
        recoveryMarkers.clear()
        !recoveryMarkers.hasPendingRecovery()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    private fun pendingRecoveryCannotBeRuledOut(): Boolean =
        runCatching(recoveryMarkers::hasPendingRecovery).getOrDefault(true)
}

/**
 * A destructive rollback marker can only reproduce referenced READY secrets. Standard-import
 * placeholders deliberately contain no plaintext/ciphertext, so blocking Replace is safer than
 * writing a marker that could not restore the pre-import credential state.
 */
private fun BackupSnapshotRows.nonRecoverableReferencedSecretIds(): List<String> {
    val referencedIds = buildSet {
        credentials.mapNotNullTo(this) { it.secretId }
        keyIdentities.mapTo(this) { it.privateSecretId }
    }
    val rowsById = encryptedSecrets.associateBy { it.id }
    return referencedIds.asSequence()
        .filter { secretId ->
            val row = rowsById[secretId] ?: return@filter true
            runCatching { row.toStoredEncryptedCredentialRecord() }.isFailure
        }
        .sorted()
        .toList()
}

private fun exactRecoveryDeletes(
    current: BackupSnapshotRows,
    target: BackupPayloadSnapshot,
): BackupImportExactDeletes = BackupImportExactDeletes(
    hostIds = current.hosts.mapTo(hashSetOf()) { it.id } - target.hostProfiles.mapTo(hashSetOf()) { it.id },
    credentialIds = current.credentials.mapTo(hashSetOf()) { it.id } -
        target.credentials.mapTo(hashSetOf()) { it.metadata.id },
    identityIds = current.keyIdentities.mapTo(hashSetOf()) { it.id } -
        target.sshKeys.mapTo(hashSetOf()) { it.metadata.id },
    terminalProfileIds = current.terminalProfiles.mapTo(hashSetOf()) { it.id } -
        target.terminalProfiles.mapTo(hashSetOf()) { it.id },
    customTerminalThemeIds = current.customTerminalThemes.mapTo(hashSetOf()) { it.id } -
        target.terminalThemes.mapTo(hashSetOf()) { it.id },
    keyboardProfileIds = current.keyboardProfiles.mapTo(hashSetOf()) { it.id } -
        target.keyboardProfiles.mapTo(hashSetOf()) { it.id },
    knownHostIds = current.knownHosts.mapTo(hashSetOf()) { it.id } -
        target.knownHosts.mapTo(hashSetOf()) { it.id },
    snippetIds = current.snippets.mapTo(hashSetOf()) { it.id } - target.snippets.mapTo(hashSetOf()) { it.id },
    secretIds = current.encryptedSecrets.mapTo(hashSetOf()) { it.id } - target.secretReferenceIdsForApply(),
)

private class RoomBackupImportPersistence(
    private val database: AppDatabase,
    private val settings: AppSettingsRepository,
) : BackupImportPersistence {
    override suspend fun capture(): CapturedBackupImportState {
        repeat(MAX_CAPTURE_ATTEMPTS) {
            val before = settings.settings.first()
            val rows = database.backupSnapshotDao().readSnapshot()
            val after = settings.settings.first()
            if (before == after) return captureState(rows, after)
        }
        throw BackupSnapshotException.ConcurrentSettingsMutation()
    }

    override suspend fun apply(
        expectedRevision: BackupImportStateRevision,
        expectedSettings: AppSettings,
        rows: BackupImportRows,
        restoredSettings: AppSettings?,
    ) {
        var settingsReplacementMayHaveCommitted = false
        try {
            database.withTransaction {
                val currentSettings = settings.settings.first()
                val currentRows = database.backupSnapshotDao().readSnapshot()
                val current = captureState(currentRows, currentSettings)
                try {
                    if (current.revision != expectedRevision) throw BackupImportException.StalePreview()
                } finally {
                    current.snapshot.close()
                }

                database.backupImportDao().applyRows(rows)
                if (restoredSettings != null) {
                    settings.update { builder ->
                        if (builder.build() != expectedSettings) {
                            throw BackupImportException.StalePreview()
                        }
                        builder.clear().mergeFrom(restoredSettings)
                        settingsReplacementMayHaveCommitted = true
                    }
                }
            }
        } catch (applyFailure: Throwable) {
            if (settingsReplacementMayHaveCommitted && restoredSettings != null) {
                val rollbackFailure = runCatching {
                    withContext(NonCancellable) {
                        when (settings.settings.first()) {
                            expectedSettings -> Unit
                            restoredSettings -> settings.update { builder ->
                                if (builder.build() != restoredSettings) {
                                    throw BackupImportException.StalePreview()
                                }
                                builder.clear().mergeFrom(expectedSettings)
                            }
                            else -> throw BackupImportException.StalePreview()
                        }
                    }
                }.exceptionOrNull()
                if (rollbackFailure != null) {
                    throw BackupImportException.SettingsRollbackFailed(
                        applyFailure = applyFailure,
                        rollbackFailure = rollbackFailure,
                    )
                }
            }
            throw applyFailure
        }
    }

    private companion object {
        const val MAX_CAPTURE_ATTEMPTS = 3
    }
}

private fun captureState(
    rows: BackupSnapshotRows,
    settings: AppSettings,
): CapturedBackupImportState {
    val keyboardKeys = rows.keyboardKeys.groupBy { it.profileId }
    val snapshot = BackupPayloadSnapshot(
        mode = BackupMode.STANDARD,
        hostProfiles = rows.hosts.map { it.toDomainModel() },
        credentials = rows.credentials.map { BackupCredentialRecord(it.toDomainModel()) },
        sshKeys = rows.keyIdentities.map { BackupSshKeyRecord(it.toDomainModel()) },
        knownHosts = rows.knownHosts.map { it.toDomainModel() },
        snippets = rows.snippets.map { it.toDomainModel() },
        terminalProfiles = rows.terminalProfiles.map { it.toDomainModel() },
        terminalThemes = rows.customTerminalThemes.map {
            it.toDomainModel().toBackupTerminalTheme()
        },
        keyboardProfiles = rows.keyboardProfiles.map { profile ->
            KeyboardProfileWithKeys(profile, keyboardKeys[profile.id].orEmpty()).toDomainModel()
        },
        globalSettings = settings.toBackupGlobalSettings(),
    )
    return CapturedBackupImportState(
        rows = rows,
        settings = settings,
        snapshot = snapshot,
        revision = BackupImportRevisionFactory.create(snapshot, rows.encryptedSecrets, settings),
    )
}

private object BackupImportRevisionFactory {
    fun create(
        snapshot: BackupPayloadSnapshot,
        secrets: List<EncryptedSecretEntity>,
        settings: AppSettings,
    ): BackupImportStateRevision {
        val digest = MessageDigest.getInstance("SHA-256")
        val roomSnapshot = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            hostProfiles = snapshot.hostProfiles,
            credentials = snapshot.credentials,
            sshKeys = snapshot.sshKeys,
            knownHosts = snapshot.knownHosts,
            snippets = snapshot.snippets,
            terminalProfiles = snapshot.terminalProfiles,
            terminalThemes = snapshot.terminalThemes,
            keyboardProfiles = snapshot.keyboardProfiles,
            // Defaults live in DataStore and may temporarily point at a profile created in a later
            // startup step. Hash the exact protobuf separately without making Room hashing depend
            // on that cross-store reference being exportable.
            globalSettings = snapshot.globalSettings.copy(
                defaultTerminalProfileId = null,
                defaultKeyboardProfileId = null,
            ),
        )
        try {
            BackupPayloadCodec().writeAndWipeSecrets(
                roomSnapshot,
                DigestOutputStream(DiscardingOutputStream, digest),
            )
        } finally {
            roomSnapshot.close()
        }
        digest.field(settings.toByteArray())
        secrets.sortedBy { it.id }.forEach { row ->
            digest.field(row.id)
            digest.field(row.kindCode)
            digest.field(row.envelopeVersion)
            digest.field(row.keyVersion)
            digest.field(row.nonce)
            digest.field(row.ciphertext)
            digest.field(row.stateCode)
            digest.field(row.failureCode)
            digest.field(row.legacyId)
            digest.field(row.createdAtEpochMillis)
            digest.field(row.updatedAtEpochMillis)
        }
        return BackupImportStateRevision(
            digest.digest().joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            },
        )
    }

    private fun MessageDigest.field(value: String?) {
        if (value == null) {
            update(byteArrayOf(0))
        } else {
            update(byteArrayOf(1))
            field(value.encodeToByteArray())
        }
    }

    private fun MessageDigest.field(value: ByteArray?) {
        if (value == null) {
            field(-1)
            return
        }
        field(value.size)
        update(value)
    }

    private fun MessageDigest.field(value: Int) {
        update(
            byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            ),
        )
    }

    private fun MessageDigest.field(value: Long) {
        update(ByteArray(Long.SIZE_BYTES) { offset -> (value ushr (56 - offset * 8)).toByte() })
    }

    private object DiscardingOutputStream : OutputStream() {
        override fun write(value: Int) = Unit

        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    }
}

private data class BuiltBackupImportRows(
    val rows: BackupImportRows,
    val unavailableSecretPlaceholders: Int,
)

private class BackupImportRowsBuilder(
    private val credentials: CredentialStore,
    private val clock: BackupImportEpochClock,
) {
    suspend fun build(
        plan: BackupImportPlan,
        currentRows: BackupSnapshotRows,
    ): BuiltBackupImportRows {
        val snapshot = plan.snapshotToApply
        val currentSecrets = currentRows.encryptedSecrets.associateBy { it.id }
        val currentOwners = currentRows.secretOwners()
        val newSecrets = mutableListOf<EncryptedSecretEntity>()
        var unavailable = 0

        suspend fun materialize(
            reference: CredentialSecretReference,
            owner: ImportSecretOwner,
            portable: PortableBackupSecret?,
        ) {
            val currentOwner = currentOwners[reference.secretId.value]
            val currentSecret = currentSecrets[reference.secretId.value]
            if (portable == null && currentOwner == owner && currentSecret != null) {
                require(currentSecret.kindCode == reference.kind.wireCode) {
                    "A preserved Standard-backup secret has a different purpose."
                }
                return
            }
            if (currentOwner != null && currentOwner != owner) {
                throw BackupImportPlanException.IncompatibleSecretOwner(
                    secretId = reference.secretId.value,
                    existingOwnerId = currentOwner.recordId,
                    incomingOwnerId = owner.recordId,
                )
            }

            val now = clock.nowEpochMillis()
            val createdAt = currentSecret?.createdAtEpochMillis ?: now
            if (portable == null) {
                newSecrets += unavailableSecret(reference, owner, createdAt, now)
                unavailable += 1
                return
            }

            var plaintext: ByteArray? = null
            try {
                plaintext = portable.withBytes { bytes -> bytes.copyOf() }
                val encrypted = credentials.encryptAndWipe(reference, plaintext)
                require(
                    encrypted.secretId == reference.secretId.value &&
                        encrypted.kindCode == reference.kind.wireCode,
                ) { "Credential encryption returned a record for a different secret reference." }
                newSecrets += encrypted.toReadyEntity(
                    createdAtEpochMillis = createdAt,
                    updatedAtEpochMillis = maxOf(now, createdAt),
                )
            } finally {
                plaintext?.fill(0)
                portable.wipe()
            }
        }

        snapshot.sshKeys.forEach { record ->
            val reference = CredentialSecretReference(
                credentialId = CredentialId.parseCanonical(record.metadata.id),
                secretId = SecretId.parseCanonical(record.metadata.privateKeySecretReferenceId),
                kind = CredentialSecretKind.PRIVATE_KEY,
            )
            materialize(
                reference,
                ImportSecretOwner(record.metadata.id, BackupImportRecordKind.SSH_KEY, reference.kind),
                record.portablePrivateKey,
            )
        }
        snapshot.credentials.forEach { record ->
            val reference = record.metadata.authentication.secretReference(record.metadata.id)
            if (reference != null) {
                materialize(
                    reference,
                    ImportSecretOwner(
                        record.metadata.id,
                        BackupImportRecordKind.CREDENTIAL,
                        reference.kind,
                    ),
                    record.portableSecret,
                )
            }
        }

        val keyboardRows = snapshot.keyboardProfiles.map { it.toRows() }
        val importedCredentialIds = snapshot.credentials.mapTo(hashSetOf()) { it.metadata.id }
        val importedIdentityIds = snapshot.sshKeys.mapTo(hashSetOf()) { it.metadata.id }
        val orphanCandidates = buildSet {
            currentRows.credentials.filter { it.id in importedCredentialIds }.mapNotNullTo(this) { it.secretId }
            currentRows.keyIdentities.filter { it.id in importedIdentityIds }.mapTo(this) { it.privateSecretId }
        } - snapshot.secretReferenceIdsForApply()

        return BuiltBackupImportRows(
            rows = BackupImportRows(
                terminalProfiles = snapshot.terminalProfiles.map { it.toEntity() },
                customTerminalThemes = snapshot.terminalThemes.map {
                    it.toCustomTerminalTheme().toEntity()
                },
                keyboardProfiles = keyboardRows.map { it.profile },
                keyboardKeys = keyboardRows.flatMap { it.keys },
                secrets = newSecrets,
                identities = snapshot.sshKeys.map { it.metadata.toEntity() },
                credentials = snapshot.credentials.map { it.metadata.toEntity() },
                hosts = snapshot.hostProfiles.map { it.toEntity() },
                knownHosts = snapshot.knownHosts.map { it.toEntity() },
                snippets = snapshot.snippets.map { it.toEntity() },
                knownHostIdsToDelete = plan.knownHostRecordIdsToDelete,
                possiblyOrphanedSecretIds = orphanCandidates,
            ),
            unavailableSecretPlaceholders = unavailable,
        )
    }

    private fun unavailableSecret(
        reference: CredentialSecretReference,
        owner: ImportSecretOwner,
        createdAt: Long,
        updatedAt: Long,
    ) = EncryptedSecretEntity(
        id = reference.secretId.value,
        kindCode = reference.kind.wireCode,
        envelopeVersion = 0,
        keyVersion = 0,
        nonce = null,
        ciphertext = null,
        stateCode = EncryptedSecretState.LEGACY_UNAVAILABLE.wireCode,
        failureCode = OMITTED_SECRET_FAILURE_CODE,
        legacyId = "$OMITTED_SECRET_SOURCE_PREFIX${owner.recordId}",
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = maxOf(createdAt, updatedAt),
    )

    private companion object {
        const val OMITTED_SECRET_FAILURE_CODE = "backup_secret_not_exported"
        const val OMITTED_SECRET_SOURCE_PREFIX = "portable-backup:"
    }
}

private data class ImportSecretOwner(
    val recordId: String,
    val recordKind: BackupImportRecordKind,
    val secretKind: CredentialSecretKind,
)

private fun BackupSnapshotRows.secretOwners(): Map<String, ImportSecretOwner> = buildMap {
    credentials.forEach { credential ->
        val secretId = credential.secretId ?: return@forEach
        val kind = when (credential.kindCode) {
            "password" -> CredentialSecretKind.PASSWORD
            "private_key" -> CredentialSecretKind.KEY_PASSPHRASE
            "keyboard_interactive" -> CredentialSecretKind.KEYBOARD_INTERACTIVE
            else -> return@forEach
        }
        put(
            secretId,
            ImportSecretOwner(credential.id, BackupImportRecordKind.CREDENTIAL, kind),
        )
    }
    keyIdentities.forEach { identity ->
        put(
            identity.privateSecretId,
            ImportSecretOwner(
                identity.id,
                BackupImportRecordKind.SSH_KEY,
                CredentialSecretKind.PRIVATE_KEY,
            ),
        )
    }
}

private fun BackupPayloadSnapshot.secretReferenceIdsForApply(): Set<String> = buildSet {
    credentials.mapNotNullTo(this) { it.secretReferenceId }
    sshKeys.mapTo(this) { it.metadata.privateKeySecretReferenceId }
}

private fun SshAuthentication.secretReference(ownerId: String): CredentialSecretReference? {
    val secretAndKind = when (this) {
        is SshAuthentication.Password -> secretReferenceId?.let { it to CredentialSecretKind.PASSWORD }
        is SshAuthentication.PrivateKey -> passphraseSecretReferenceId?.let {
            it to CredentialSecretKind.KEY_PASSPHRASE
        }
        is SshAuthentication.KeyboardInteractive -> reusableResponseSecretReferenceId?.let {
            it to CredentialSecretKind.KEYBOARD_INTERACTIVE
        }
    } ?: return null
    return CredentialSecretReference(
        credentialId = CredentialId.parseCanonical(ownerId),
        secretId = SecretId.parseCanonical(secretAndKind.first),
        kind = secretAndKind.second,
    )
}

private fun BackupGlobalSettings.toRestoredAppSettings(base: AppSettings): AppSettings = base.toBuilder()
    .setThemeMode(
        when (themeMode) {
            BackupThemeMode.SYSTEM -> AppSettings.ThemeMode.THEME_MODE_SYSTEM
            BackupThemeMode.LIGHT -> AppSettings.ThemeMode.THEME_MODE_LIGHT
            BackupThemeMode.DARK -> AppSettings.ThemeMode.THEME_MODE_DARK
        },
    )
    .setDynamicColorEnabled(dynamicColorEnabled)
    .clearAccentPreset()
    .clearDefaultTerminalProfileId()
    .clearDefaultKeyboardProfileId()
    .clearLastBackupMode()
    .apply {
        this@toRestoredAppSettings.accentPreset?.let(::setAccentPreset)
    }
    .apply {
        this@toRestoredAppSettings.defaultTerminalProfileId?.let(::setDefaultTerminalProfileId)
    }
    .apply {
        this@toRestoredAppSettings.defaultKeyboardProfileId?.let(::setDefaultKeyboardProfileId)
    }
    .setKeepaliveIntervalSeconds(keepaliveIntervalSeconds)
    .setReconnectEnabled(reconnectEnabled)
    .setReconnectMaxAttempts(reconnectMaxAttempts)
    .setBackgroundSessionsEnabled(backgroundSessionsEnabled)
    .setNotificationPrivacyEnabled(notificationPrivacyEnabled)
    .setDisconnectNotificationsEnabled(disconnectNotificationsEnabled)
    .setReconnectNotificationsEnabled(reconnectNotificationsEnabled)
    .setKeepCpuAwake(keepCpuAwake)
    .setKeepScreenOnWhileTerminalVisible(keepScreenOnWhileTerminalVisible)
    .setAppLockMode(
        when (appLockMode) {
            BackupAppLockMode.OFF -> AppSettings.AppLockMode.APP_LOCK_MODE_OFF
            BackupAppLockMode.IMMEDIATE -> AppSettings.AppLockMode.APP_LOCK_MODE_IMMEDIATE
            BackupAppLockMode.DELAYED -> AppSettings.AppLockMode.APP_LOCK_MODE_DELAYED
            BackupAppLockMode.ON_BACKGROUND -> AppSettings.AppLockMode.APP_LOCK_MODE_ON_BACKGROUND
        },
    )
    .setAppLockDelaySeconds(appLockDelaySeconds)
    .setScreenshotBlockingEnabled(screenshotBlockingEnabled)
    .setSensitiveClipboardClearSeconds(sensitiveClipboardClearSeconds)
    .setOsc52Policy(
        when (osc52Policy) {
            RemoteClipboardMode.DISABLED -> AppSettings.Osc52Policy.OSC52_POLICY_DISABLED
            RemoteClipboardMode.ASK -> AppSettings.Osc52Policy.OSC52_POLICY_ASK
        },
    )
    .setMultilinePasteConfirmationEnabled(multilinePasteConfirmationEnabled)
    .setTmuxSessionSelectorDisabled(tmuxSessionSelectorDisabled)
    .apply {
        this@toRestoredAppSettings.lastBackupMode?.let { mode ->
            setLastBackupMode(mode.name.lowercase())
        }
    }
    .build()

private fun BackupImportPlan.toResult(
    unavailableSecretPlaceholders: Int,
    recoveryMarkerRemoved: Boolean,
) = BackupImportResult(
    strategy = strategy,
    hostsApplied = snapshotToApply.hostProfiles.size,
    credentialsApplied = snapshotToApply.credentials.size,
    sshKeysApplied = snapshotToApply.sshKeys.size,
    knownHostsApplied = snapshotToApply.knownHosts.size,
    snippetsApplied = snapshotToApply.snippets.size,
    terminalProfilesApplied = snapshotToApply.terminalProfiles.size,
    terminalThemesApplied = snapshotToApply.terminalThemes.size,
    keyboardProfilesApplied = snapshotToApply.keyboardProfiles.size,
    unavailableSecretPlaceholders = unavailableSecretPlaceholders,
    skippedRecords = skippedIncomingRecordIds.size,
    incompatibleRecords = incompatibleIncomingRecords.map { it.recordId }.distinct().size,
    recoveryMarkerRemoved = recoveryMarkerRemoved,
    customFontsApplied = snapshotToApply.customFonts.size,
)

private const val CUSTOM_FONT_ID_PREFIX = "custom_"
