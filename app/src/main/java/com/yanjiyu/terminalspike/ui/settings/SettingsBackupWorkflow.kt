package com.yanjiyu.terminalspike.ui.settings

import android.content.Context
import android.net.Uri
import com.yanjiyu.terminalspike.BuildConfig
import com.yanjiyu.terminalspike.core.backup.BackupAppVersion
import com.yanjiyu.terminalspike.core.backup.BackupCompatibility
import com.yanjiyu.terminalspike.core.backup.BackupContentSummary
import com.yanjiyu.terminalspike.core.backup.BackupDocumentContract
import com.yanjiyu.terminalspike.core.backup.BackupEnvelopeException
import com.yanjiyu.terminalspike.core.backup.BackupEnvelopeFormat
import com.yanjiyu.terminalspike.core.backup.BackupEnvelopeHeader
import com.yanjiyu.terminalspike.core.backup.BackupEnvelopeMetadata
import com.yanjiyu.terminalspike.core.backup.BackupImportCoordinator
import com.yanjiyu.terminalspike.core.backup.BackupImportPlanPreview
import com.yanjiyu.terminalspike.core.backup.BackupImportPreview
import com.yanjiyu.terminalspike.core.backup.BackupImportResult
import com.yanjiyu.terminalspike.core.backup.BackupImportStrategy
import com.yanjiyu.terminalspike.core.backup.BackupMode
import com.yanjiyu.terminalspike.core.backup.BackupPayloadException
import com.yanjiyu.terminalspike.core.backup.BackupPayloadFormat
import com.yanjiyu.terminalspike.core.backup.BackupTransferCoordinator
import com.yanjiyu.terminalspike.core.backup.PreparedBackupImport
import com.yanjiyu.terminalspike.core.backup.PreparedBackupImportPlan
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class BackupWorkflowStep {
    READY,
    EXPORT_SETUP,
    WAITING_FOR_EXPORT_DOCUMENT,
    EXPORTING,
    WAITING_FOR_RESTORE_DOCUMENT,
    INSPECTING,
    PASSPHRASE_REQUIRED,
    UNLOCKING,
    AUTHENTICATED_PREVIEW,
    PLANNING,
    IMPORT_REVIEW,
    APPLYING,
    RECOVERING,
    COMPLETED,
    FAILED,
}

internal enum class BackupCompletionKind {
    EXPORT,
    IMPORT,
    RECOVERY,
}

internal enum class BackupWorkflowError {
    PASSPHRASE_TOO_SHORT,
    PASSPHRASE_TOO_LONG,
    DOCUMENT_UNAVAILABLE,
    DOCUMENT_ACCESS_DENIED,
    INVALID_BACKUP,
    UNSUPPORTED_BACKUP,
    UNLOCK_FAILED,
    DATA_CHANGED,
    RECOVERY_REQUIRED,
    RECOVERY_SNAPSHOT_UNAVAILABLE,
    EXPORT_FAILED,
    IMPORT_FAILED,
    RECOVERY_FAILED,
}

internal data class BackupHeaderUiSummary(
    val mode: BackupMode,
    val createdAtEpochMillis: Long,
    val appVersionName: String,
    val payloadSchemaVersion: Int,
)

internal data class BackupArchiveUiSummary(
    val header: BackupHeaderUiSummary,
    val content: BackupContentSummary,
    val incompatibleRecords: Int,
    val skippedRecords: Int,
    val unresolvedReferences: Int,
)

internal data class BackupExportUiResult(
    val mode: BackupMode,
    val content: BackupContentSummary,
    val bytesWritten: Long,
)

internal data class BackupWorkflowUiState(
    val step: BackupWorkflowStep = BackupWorkflowStep.READY,
    val recoveryAvailable: Boolean = false,
    val selectedStrategy: BackupImportStrategy = BackupImportStrategy.MERGE,
    val header: BackupHeaderUiSummary? = null,
    val archive: BackupArchiveUiSummary? = null,
    val plan: BackupImportPlanPreview? = null,
    val exportResult: BackupExportUiResult? = null,
    val importResult: BackupImportResult? = null,
    val completionKind: BackupCompletionKind? = null,
    val error: BackupWorkflowError? = null,
) {
    val busy: Boolean
        get() = step in setOf(
            BackupWorkflowStep.EXPORTING,
            BackupWorkflowStep.INSPECTING,
            BackupWorkflowStep.UNLOCKING,
            BackupWorkflowStep.PLANNING,
            BackupWorkflowStep.APPLYING,
            BackupWorkflowStep.RECOVERING,
        )
}

internal sealed interface BackupDocumentRequest {
    data class Create(val suggestedFileName: String) : BackupDocumentRequest

    data object Open : BackupDocumentRequest
}

@JvmInline
internal value class BackupDocumentReference(val encodedUri: String)

internal interface SettingsUnlockedBackup : AutoCloseable {
    val preview: BackupArchiveUiSummary
}

internal interface SettingsPreparedBackupImport : AutoCloseable {
    val preview: BackupImportPlanPreview
}

/** Narrow contract used by the state machine and fake-contract JVM tests. */
internal interface SettingsBackupGateway {
    fun hasPendingRecovery(): Boolean

    suspend fun export(
        destination: BackupDocumentReference,
        mode: BackupMode,
        createdAtEpochMillis: Long,
        passphrase: CharArray,
    ): BackupExportUiResult

    suspend fun exportWithOptions(
        destination: BackupDocumentReference,
        mode: BackupMode,
        createdAtEpochMillis: Long,
        includeCustomFonts: Boolean,
        passphrase: CharArray,
    ): BackupExportUiResult = export(destination, mode, createdAtEpochMillis, passphrase)

    suspend fun inspect(source: BackupDocumentReference): BackupHeaderUiSummary

    suspend fun unlock(
        source: BackupDocumentReference,
        passphrase: CharArray,
    ): SettingsUnlockedBackup

    suspend fun prepare(
        unlocked: SettingsUnlockedBackup,
        strategy: BackupImportStrategy,
    ): SettingsPreparedBackupImport

    suspend fun apply(prepared: SettingsPreparedBackupImport): BackupImportResult

    suspend fun recoverPending(): BackupImportResult?
}

/** ContentResolver adapter. It streams provider handles and never asks for a filesystem path. */
internal class AndroidSettingsBackupGateway(
    context: Context,
    private val transfers: BackupTransferCoordinator,
    private val imports: BackupImportCoordinator,
) : SettingsBackupGateway {
    private val applicationContext = context.applicationContext
    private val resolver = applicationContext.contentResolver

    override fun hasPendingRecovery(): Boolean = imports.hasPendingRecovery()

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
        val uri = Uri.parse(destination.encodedUri)
        val output = resolver.openOutputStream(uri, "w")
            ?: throw IOException("The selected backup document could not be opened for writing.")
        val result = output.use { stream ->
            transfers.export(
                mode = mode,
                output = stream,
                passphrase = passphrase,
                metadata = metadata(mode, createdAtEpochMillis),
                includeCustomFonts = includeCustomFonts,
            )
        }
        return BackupExportUiResult(mode, result.content, result.bytesWritten)
    }

    override suspend fun inspect(source: BackupDocumentReference): BackupHeaderUiSummary {
        val uri = Uri.parse(source.encodedUri)
        val input = resolver.openInputStream(uri)
            ?: throw IOException("The selected backup document could not be opened.")
        return input.use { stream -> transfers.inspect(stream) }.toUiSummary()
    }

    override suspend fun unlock(
        source: BackupDocumentReference,
        passphrase: CharArray,
    ): SettingsUnlockedBackup {
        val uri = Uri.parse(source.encodedUri)
        val input = resolver.openInputStream(uri)
            ?: throw IOException("The selected backup document could not be opened.")
        return CoreSettingsUnlockedBackup(input.use { transfers.unlock(it, passphrase) })
    }

    override suspend fun prepare(
        unlocked: SettingsUnlockedBackup,
        strategy: BackupImportStrategy,
    ): SettingsPreparedBackupImport {
        require(unlocked is CoreSettingsUnlockedBackup) { "Unlocked backup came from another gateway." }
        return CoreSettingsPreparedBackupImport(imports.prepare(unlocked.value, strategy))
    }

    override suspend fun apply(prepared: SettingsPreparedBackupImport): BackupImportResult {
        require(prepared is CoreSettingsPreparedBackupImport) { "Import plan came from another gateway." }
        return imports.apply(prepared.value)
    }

    override suspend fun recoverPending(): BackupImportResult? = imports.recoverPending()

    private fun metadata(mode: BackupMode, createdAtEpochMillis: Long) = BackupEnvelopeMetadata(
        mode = mode,
        createdAtEpochMillis = createdAtEpochMillis,
        appVersion = BackupAppVersion(BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME),
        payloadSchemaVersion = BackupPayloadFormat.SCHEMA_VERSION,
        compatibility = BackupCompatibility(
            minimumSdk = applicationContext.applicationInfo.minSdkVersion,
            targetSdk = applicationContext.applicationInfo.targetSdkVersion,
            capabilityBits = 0,
        ),
    )

    private class CoreSettingsUnlockedBackup(
        val value: PreparedBackupImport,
    ) : SettingsUnlockedBackup {
        override val preview: BackupArchiveUiSummary = value.preview.toUiSummary()

        override fun close() = value.close()
    }

    private class CoreSettingsPreparedBackupImport(
        val value: PreparedBackupImportPlan,
    ) : SettingsPreparedBackupImport {
        override val preview: BackupImportPlanPreview = value.preview

        override fun close() = value.close()
    }
}

/**
 * Lifecycle-bounded, one-shot backup workflow. Mutable passphrases transfer into this class and are
 * wiped on success, failure, picker cancellation, replacement, and [close].
 */
internal class SettingsBackupWorkflow(
    private val gateway: SettingsBackupGateway,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val operationMutex = Mutex()
    private val ownershipLock = Any()
    private val closed = AtomicBoolean(false)
    private val requests = Channel<BackupDocumentRequest>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(
        BackupWorkflowUiState(recoveryAvailable = safeHasPendingRecovery()),
    )

    val state: StateFlow<BackupWorkflowUiState> = _state.asStateFlow()
    val documentRequests = requests.receiveAsFlow()

    private var pendingExport: PendingExport? = null
    private var pendingImportDocument: BackupDocumentReference? = null
    private var unlockedImport: SettingsUnlockedBackup? = null
    private var preparedImport: SettingsPreparedBackupImport? = null

    fun beginExport() {
        if (!canStartNewFlow()) return
        cancelPendingExport()
        releaseOwnedImport()
        _state.value = readyState().copy(step = BackupWorkflowStep.EXPORT_SETUP)
        requestExportDocument(
            mode = BackupMode.FULL,
            includeCustomFonts = true,
            passphrase = portableBackupKey(),
        )
    }

    /** Takes ownership of [passphrase]. */
    fun requestExportDocument(mode: BackupMode, passphrase: CharArray) =
        requestExportDocument(mode, includeCustomFonts = false, passphrase = passphrase)

    /** Takes ownership of [passphrase]; imported fonts remain excluded unless explicitly opted in. */
    fun requestExportDocument(
        mode: BackupMode,
        includeCustomFonts: Boolean,
        passphrase: CharArray,
    ) {
        if (closed.get() || _state.value.step != BackupWorkflowStep.EXPORT_SETUP) {
            passphrase.fill('\u0000')
            return
        }
        val minimum = minimumExportPassphraseCharacters(mode)
        if (passphrase.size < minimum) {
            passphrase.fill('\u0000')
            _state.value = _state.value.copy(error = BackupWorkflowError.PASSPHRASE_TOO_SHORT)
            return
        }
        if (passphrase.size > BackupEnvelopeFormat.MAX_PASSPHRASE_CHARS) {
            passphrase.fill('\u0000')
            _state.value = _state.value.copy(error = BackupWorkflowError.PASSPHRASE_TOO_LONG)
            return
        }
        val createdAt = clock()
        synchronized(ownershipLock) {
            pendingExport?.wipe()
            pendingExport = PendingExport(mode, includeCustomFonts, createdAt, passphrase)
        }
        _state.value = readyState().copy(step = BackupWorkflowStep.WAITING_FOR_EXPORT_DOCUMENT)
        if (
            requests.trySend(
                BackupDocumentRequest.Create(BackupDocumentContract.suggestedFileName(createdAt)),
            ).isFailure
        ) {
            cancelPendingExport()
            fail(BackupWorkflowError.DOCUMENT_UNAVAILABLE)
        }
    }

    fun onExportDocumentResult(destination: BackupDocumentReference?) {
        val export = synchronized(ownershipLock) {
            pendingExport.also { pendingExport = null }
        } ?: return
        if (destination == null) {
            export.wipe()
            _state.value = readyState()
            return
        }
        scope.launch(ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                operationMutex.withLock {
                    _state.value = readyState().copy(step = BackupWorkflowStep.EXPORTING)
                    val result = gateway.exportWithOptions(
                        destination = destination,
                        mode = export.mode,
                        createdAtEpochMillis = export.createdAtEpochMillis,
                        includeCustomFonts = export.includeCustomFonts,
                        passphrase = export.passphrase,
                    )
                    _state.value = readyState().copy(
                        step = BackupWorkflowStep.COMPLETED,
                        completionKind = BackupCompletionKind.EXPORT,
                        exportResult = result,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                fail(error.toWorkflowError(BackupWorkflowError.EXPORT_FAILED))
            } finally {
                export.wipe()
            }
        }
    }

    fun requestRestoreDocument() {
        if (!canStartNewFlow()) return
        cancelPendingExport()
        releaseOwnedImport()
        if (safeHasPendingRecovery()) {
            fail(BackupWorkflowError.RECOVERY_REQUIRED)
            return
        }
        _state.value = readyState().copy(step = BackupWorkflowStep.WAITING_FOR_RESTORE_DOCUMENT)
        if (requests.trySend(BackupDocumentRequest.Open).isFailure) {
            fail(BackupWorkflowError.DOCUMENT_UNAVAILABLE)
        }
    }

    fun onRestoreDocumentResult(source: BackupDocumentReference?) {
        if (source == null) {
            _state.value = readyState()
            return
        }
        scope.launch(ioDispatcher) {
            try {
                operationMutex.withLock {
                    _state.value = readyState().copy(step = BackupWorkflowStep.INSPECTING)
                    val header = gateway.inspect(source)
                    synchronized(ownershipLock) { pendingImportDocument = source }
                    _state.value = readyState().copy(
                        step = BackupWorkflowStep.PASSPHRASE_REQUIRED,
                        header = header,
                    )
                }
                unlockSelectedBackup(portableBackupKey())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                fail(error.toWorkflowError(BackupWorkflowError.INVALID_BACKUP))
            }
        }
    }

    /** Takes ownership of [passphrase]. A failed unlock keeps the selected document retryable. */
    fun unlockSelectedBackup(passphrase: CharArray) {
        val source = synchronized(ownershipLock) { pendingImportDocument }
        val header = _state.value.header
        if (closed.get() || source == null || header == null) {
            passphrase.fill('\u0000')
            return
        }
        if (passphrase.isEmpty()) {
            passphrase.fill('\u0000')
            _state.value = _state.value.copy(error = BackupWorkflowError.PASSPHRASE_TOO_SHORT)
            return
        }
        if (passphrase.size > BackupEnvelopeFormat.MAX_PASSPHRASE_CHARS) {
            passphrase.fill('\u0000')
            _state.value = _state.value.copy(error = BackupWorkflowError.PASSPHRASE_TOO_LONG)
            return
        }
        scope.launch(ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                operationMutex.withLock {
                    _state.value = _state.value.copy(
                        step = BackupWorkflowStep.UNLOCKING,
                        error = null,
                    )
                    val unlocked = gateway.unlock(source, passphrase)
                    synchronized(ownershipLock) {
                        pendingImportDocument = null
                        unlockedImport?.close()
                        unlockedImport = unlocked
                    }
                    _state.value = readyState().copy(
                        step = BackupWorkflowStep.AUTHENTICATED_PREVIEW,
                        header = unlocked.preview.header,
                        archive = unlocked.preview,
                        selectedStrategy = BackupImportStrategy.REPLACE_CORRESPONDING,
                    )
                }
                prepareImportPreview()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                fail(error.toWorkflowError(BackupWorkflowError.UNLOCK_FAILED))
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun selectImportStrategy(strategy: BackupImportStrategy) {
        val current = _state.value
        if (current.step != BackupWorkflowStep.AUTHENTICATED_PREVIEW) return
        _state.value = current.copy(selectedStrategy = strategy, error = null)
    }

    fun prepareImportPreview() {
        val current = _state.value
        if (current.step != BackupWorkflowStep.AUTHENTICATED_PREVIEW) return
        val unlocked = synchronized(ownershipLock) {
            unlockedImport.also { unlockedImport = null }
        } ?: return
        scope.launch(ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                operationMutex.withLock {
                    _state.value = current.copy(step = BackupWorkflowStep.PLANNING, error = null)
                    val prepared = gateway.prepare(unlocked, current.selectedStrategy)
                    synchronized(ownershipLock) {
                        preparedImport?.close()
                        preparedImport = prepared
                    }
                    _state.value = current.copy(
                        step = BackupWorkflowStep.IMPORT_REVIEW,
                        plan = prepared.preview,
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                fail(error.toWorkflowError(BackupWorkflowError.IMPORT_FAILED))
            } finally {
                unlocked.close()
            }
        }
    }

    fun applyPreparedImport() {
        val current = _state.value
        if (current.step != BackupWorkflowStep.IMPORT_REVIEW) return
        val prepared = synchronized(ownershipLock) {
            preparedImport.also { preparedImport = null }
        } ?: return
        scope.launch(ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                operationMutex.withLock {
                    _state.value = current.copy(step = BackupWorkflowStep.APPLYING, error = null)
                    val result = gateway.apply(prepared)
                    _state.value = readyState().copy(
                        step = BackupWorkflowStep.COMPLETED,
                        completionKind = BackupCompletionKind.IMPORT,
                        importResult = result,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                fail(error.toWorkflowError(BackupWorkflowError.IMPORT_FAILED))
            } finally {
                prepared.close()
            }
        }
    }

    fun recoverPendingImport() {
        if (_state.value.busy || !safeHasPendingRecovery()) return
        releaseOwnedImport()
        scope.launch(ioDispatcher) {
            try {
                operationMutex.withLock {
                    _state.value = readyState().copy(
                        step = BackupWorkflowStep.RECOVERING,
                        recoveryAvailable = true,
                    )
                    val result = gateway.recoverPending()
                    if (result == null) {
                        _state.value = readyState()
                    } else {
                        _state.value = readyState().copy(
                            step = BackupWorkflowStep.COMPLETED,
                            completionKind = BackupCompletionKind.RECOVERY,
                            importResult = result,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                fail(error.toWorkflowError(BackupWorkflowError.RECOVERY_FAILED))
            }
        }
    }

    fun dismiss() {
        if (_state.value.busy) return
        cancelPendingExport()
        releaseOwnedImport()
        synchronized(ownershipLock) { pendingImportDocument = null }
        _state.value = readyState()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        requests.close()
        cancelPendingExport()
        releaseOwnedImport()
        synchronized(ownershipLock) { pendingImportDocument = null }
    }

    private fun canStartNewFlow(): Boolean = !closed.get() && !_state.value.busy

    private fun readyState() = BackupWorkflowUiState(
        recoveryAvailable = safeHasPendingRecovery(),
    )

    private fun safeHasPendingRecovery(): Boolean = runCatching(gateway::hasPendingRecovery).getOrDefault(false)

    private fun fail(error: BackupWorkflowError) {
        _state.value = BackupWorkflowUiState(
            step = BackupWorkflowStep.FAILED,
            recoveryAvailable = safeHasPendingRecovery(),
            error = error,
        )
    }

    private fun cancelPendingExport() {
        synchronized(ownershipLock) {
            pendingExport?.wipe()
            pendingExport = null
        }
    }

    private fun releaseOwnedImport() {
        synchronized(ownershipLock) {
            unlockedImport?.close()
            unlockedImport = null
            preparedImport?.close()
            preparedImport = null
        }
    }

    private class PendingExport(
        val mode: BackupMode,
        val includeCustomFonts: Boolean,
        val createdAtEpochMillis: Long,
        val passphrase: CharArray,
    ) {
        fun wipe() = passphrase.fill('\u0000')
    }

    companion object {
        const val STANDARD_MIN_PASSPHRASE_CHARACTERS = 8
        const val FULL_MIN_PASSPHRASE_CHARACTERS = 12

        fun minimumExportPassphraseCharacters(mode: BackupMode): Int = when (mode) {
            BackupMode.STANDARD -> STANDARD_MIN_PASSPHRASE_CHARACTERS
            BackupMode.FULL -> FULL_MIN_PASSPHRASE_CHARACTERS
        }

        private fun portableBackupKey(): CharArray =
            "terminal-spike-portable-backup-v1".toCharArray()
    }
}

private fun BackupEnvelopeHeader.toUiSummary() = BackupHeaderUiSummary(
    mode = mode,
    createdAtEpochMillis = createdAtEpochMillis,
    appVersionName = appVersion.name,
    payloadSchemaVersion = payloadSchemaVersion,
)

private fun BackupImportPreview.toUiSummary() = BackupArchiveUiSummary(
    header = header.toUiSummary(),
    content = content,
    incompatibleRecords = incompatibleRecords,
    skippedRecords = skippedRecords,
    unresolvedReferences = unresolvedReferences,
)

private fun Throwable.toWorkflowError(fallback: BackupWorkflowError): BackupWorkflowError = when (this) {
    is SecurityException -> BackupWorkflowError.DOCUMENT_ACCESS_DENIED
    is IOException -> BackupWorkflowError.DOCUMENT_UNAVAILABLE
    is BackupEnvelopeException.UnlockFailed -> BackupWorkflowError.UNLOCK_FAILED
    is BackupEnvelopeException.UnsupportedVersion,
    is BackupEnvelopeException.UnsupportedFormat -> BackupWorkflowError.UNSUPPORTED_BACKUP
    is BackupEnvelopeException.Truncated,
    is BackupEnvelopeException.Malformed,
    is BackupEnvelopeException.LimitExceeded -> BackupWorkflowError.INVALID_BACKUP
    is BackupPayloadException.UnsupportedVersion,
    is BackupPayloadException.UnsupportedRequiredCollection -> BackupWorkflowError.UNSUPPORTED_BACKUP
    is BackupPayloadException.Truncated,
    is BackupPayloadException.Malformed,
    is BackupPayloadException.LimitExceeded -> BackupWorkflowError.INVALID_BACKUP
    is com.yanjiyu.terminalspike.core.backup.BackupImportException.StalePreview ->
        BackupWorkflowError.DATA_CHANGED
    is com.yanjiyu.terminalspike.core.backup.BackupImportException.PendingRecoveryExists ->
        BackupWorkflowError.RECOVERY_REQUIRED
    is com.yanjiyu.terminalspike.core.backup.BackupImportException.RecoverySnapshotUnavailable ->
        BackupWorkflowError.RECOVERY_SNAPSHOT_UNAVAILABLE
    else -> fallback
}
