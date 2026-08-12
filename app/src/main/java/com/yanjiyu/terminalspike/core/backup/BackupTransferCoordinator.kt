package com.yanjiyu.terminalspike.core.backup

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BackupDocumentContract {
    const val FILE_EXTENSION = ".terminalspike-backup"
    const val MIME_TYPE = "application/vnd.yanjiyu.terminalspike.backup"
    const val FALLBACK_MIME_TYPE = "application/octet-stream"

    fun suggestedFileName(createdAtEpochMillis: Long): String {
        require(createdAtEpochMillis >= 0) { "Backup creation time must be non-negative." }
        return "terminal-spike-$createdAtEpochMillis$FILE_EXTENSION"
    }
}

data class BackupContentSummary(
    val hosts: Int,
    val credentials: Int,
    val sshKeys: Int,
    val knownHosts: Int,
    val snippets: Int,
    val terminalProfiles: Int,
    val terminalThemes: Int,
    val keyboardProfiles: Int,
    val portableCredentialSecrets: Int,
    val portablePrivateKeys: Int,
    val customFonts: Int = 0,
)

data class BackupExportResult(
    val header: BackupEnvelopeHeader,
    val content: BackupContentSummary,
    val bytesWritten: Long,
)

data class BackupImportPreview(
    val header: BackupEnvelopeHeader,
    val content: BackupContentSummary,
    val incompatibleRecords: Int,
    val skippedRecords: Int,
    val unresolvedReferences: Int,
)

/** Authenticated import model retained only until the user applies or cancels the preview. */
class PreparedBackupImport internal constructor(
    private val archive: BackupArchiveReadResult,
) : AutoCloseable {
    private val ownershipLock = Any()
    private var ownership = SnapshotOwnership.OWNED

    val preview: BackupImportPreview = BackupImportPreview(
        header = archive.header,
        content = archive.payload.snapshot.contentSummary(),
        incompatibleRecords = archive.payload.compatibility.incompatibleRecords.size,
        skippedRecords = archive.payload.compatibility.skippedUnknownRecords +
            archive.payload.compatibility.skippedUnknownRecordTypes +
            archive.payload.compatibility.skippedUnknownCollections,
        unresolvedReferences = archive.payload.compatibility.unresolvedReferences.size,
    )

    /**
     * Transfers the authenticated snapshot exactly once. After this returns, [close] is a no-op
     * for the snapshot because the receiving import plan is its sole owner.
     */
    internal fun takeSnapshot(): BackupPayloadSnapshot = synchronized(ownershipLock) {
        if (ownership != SnapshotOwnership.OWNED) {
            throw PreparedBackupImportConsumedException()
        }
        ownership = SnapshotOwnership.TRANSFERRED
        archive.payload.snapshot
    }

    override fun close() {
        val closeSnapshot = synchronized(ownershipLock) {
            when (ownership) {
                SnapshotOwnership.OWNED -> {
                    ownership = SnapshotOwnership.CLOSED
                    true
                }

                SnapshotOwnership.TRANSFERRED,
                SnapshotOwnership.CLOSED -> false
            }
        }
        if (closeSnapshot) archive.close()
    }

    private enum class SnapshotOwnership {
        OWNED,
        TRANSFERRED,
        CLOSED,
    }
}

class PreparedBackupImportConsumedException : IllegalStateException(
    "This unlocked backup has already been transferred or closed.",
)

fun interface BackupKdfIterationsProvider {
    fun iterations(): Int
}

/** Process-local calibration cache; no passphrase or derived key is retained. */
class CalibratedBackupKdfIterations(
    private val calibrator: BackupKdfIterationCalibrator = BackupKdfIterationCalibrator(),
) : BackupKdfIterationsProvider {
    @Volatile
    private var cached: Int = 0

    override fun iterations(): Int {
        cached.takeIf { it != 0 }?.let { return it }
        return synchronized(this) {
            cached.takeIf { it != 0 } ?: calibrator.calibrate().also { cached = it }
        }
    }
}

/**
 * IO-dispatched product boundary used by Storage Access Framework launchers.
 *
 * Streams remain caller-owned so a ContentResolver `use` block controls provider lifetime. Export
 * flushes but never closes its stream; import never assumes a file path or seek support.
 */
class BackupTransferCoordinator(
    private val snapshots: BackupSnapshotProvider,
    private val archives: BackupArchiveCodec,
    private val kdfIterations: BackupKdfIterationsProvider = CalibratedBackupKdfIterations(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun export(
        mode: BackupMode,
        output: OutputStream,
        passphrase: CharArray,
        metadata: BackupEnvelopeMetadata,
        includeCustomFonts: Boolean = false,
    ): BackupExportResult = withContext(ioDispatcher) {
        var snapshot: BackupPayloadSnapshot? = null
        try {
            require(metadata.mode == mode) { "Requested and envelope backup modes must match." }
            val calibratedIterations = kdfIterations.iterations()
            snapshot = snapshots.create(
                BackupExportOptions(mode = mode, includeCustomFonts = includeCustomFonts),
            )
            val content = snapshot.contentSummary()
            val counting = CountingBackupOutputStream(output)
            val header = archives.writeAndWipeSecrets(
                snapshot = snapshot,
                output = counting,
                passphrase = passphrase,
                metadata = metadata,
                kdfIterations = calibratedIterations,
            )
            counting.flush()
            BackupExportResult(header, content, counting.count)
        } finally {
            passphrase.fill('\u0000')
            snapshot?.close()
        }
    }

    suspend fun inspect(input: InputStream): BackupEnvelopeHeader = withContext(ioDispatcher) {
        archives.inspect(NoAvailableBackupInputStream(input))
    }

    suspend fun unlock(
        input: InputStream,
        passphrase: CharArray,
    ): PreparedBackupImport = withContext(ioDispatcher) {
        try {
            PreparedBackupImport(
                archives.readAndWipePassphrase(NoAvailableBackupInputStream(input), passphrase),
            )
        } finally {
            passphrase.fill('\u0000')
        }
    }
}

private fun BackupPayloadSnapshot.contentSummary() = BackupContentSummary(
    hosts = hostProfiles.size,
    credentials = credentials.size,
    sshKeys = sshKeys.size,
    knownHosts = knownHosts.size,
    snippets = snippets.size,
    terminalProfiles = terminalProfiles.size,
    terminalThemes = terminalThemes.size,
    keyboardProfiles = keyboardProfiles.size,
    portableCredentialSecrets = credentials.count { it.portableSecret != null },
    portablePrivateKeys = sshKeys.count { it.portablePrivateKey != null },
    customFonts = customFonts.size,
)

private class CountingBackupOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var count: Long = 0
        private set

    override fun write(value: Int) {
        out.write(value)
        count += 1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        count += length
    }
}

/** Defensively proves production code never relies on ContentResolver stream `available()`. */
private class NoAvailableBackupInputStream(input: InputStream) : FilterInputStream(input) {
    override fun available(): Int = 0

    override fun markSupported(): Boolean = false

    override fun mark(readlimit: Int) = Unit

    override fun reset() = throw UnsupportedOperationException("Backup streams are not seekable.")
}
