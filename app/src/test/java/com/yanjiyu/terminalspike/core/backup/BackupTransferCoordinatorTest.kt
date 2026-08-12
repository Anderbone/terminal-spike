package com.yanjiyu.terminalspike.core.backup

import com.yanjiyu.terminalspike.core.model.Snippet
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTransferCoordinatorTest {
    @Test
    fun safStyleExportInspectUnlockRoundTripReportsExactSummaryAndBytes() = runTest {
        withCoordinator { coordinator, provider ->
            val output = TrackingOutputStream()
            val passphrase = "portable archive passphrase".toCharArray()

            val exported = coordinator.export(
                mode = BackupMode.STANDARD,
                output = output,
                passphrase = passphrase,
                metadata = metadata(),
            )

            assertTrue(passphrase.all { it == '\u0000' })
            assertFalse(output.closed)
            assertTrue(output.flushed)
            assertEquals(output.size().toLong(), exported.bytesWritten)
            assertEquals(1, exported.content.snippets)
            assertEquals(listOf(BackupMode.STANDARD), provider.requests)

            val bytes = output.toByteArray()
            val inspected = coordinator.inspect(ByteArrayInputStream(bytes))
            assertEquals(BackupMode.STANDARD, inspected.mode)
            assertEquals("42", inspected.appVersion.name)

            val unlockPassphrase = "portable archive passphrase".toCharArray()
            coordinator.unlock(ByteArrayInputStream(bytes), unlockPassphrase).use { prepared ->
                assertEquals(1, prepared.preview.content.snippets)
                assertEquals(0, prepared.preview.incompatibleRecords)
                assertEquals(0, prepared.preview.skippedRecords)
                prepared.takeSnapshot().use { snapshot ->
                    assertEquals("Safe command", snapshot.snippets.single().name)
                }
            }
            assertTrue(unlockPassphrase.all { it == '\u0000' })
        }
    }

    @Test
    fun mismatchedModeFailsBeforeSnapshotAndWipesPassphrase() = runTest {
        withCoordinator { coordinator, provider ->
            val passphrase = "mode mismatch".toCharArray()

            expectThrows<IllegalArgumentException> {
                coordinator.export(
                    BackupMode.FULL,
                    ByteArrayOutputStream(),
                    passphrase,
                    metadata(BackupMode.STANDARD),
                )
            }

            assertTrue(passphrase.all { it == '\u0000' })
            assertTrue(provider.requests.isEmpty())
        }
    }

    @Test
    fun snapshotFailureWipesPassphraseAndWritesNothing() = runTest {
        val directory = Files.createTempDirectory("backup-transfer-failure").toFile()
        try {
            val expected = ExpectedSnapshotException()
            val coordinator = BackupTransferCoordinator(
                snapshots = BackupSnapshotProvider { throw expected },
                archives = BackupArchiveCodec(EncryptedFileBackupStagingFactory(directory)),
                kdfIterations = BackupKdfIterationsProvider { BackupEnvelopeFormat.MIN_KDF_ITERATIONS },
                ioDispatcher = Dispatchers.Unconfined,
            )
            val passphrase = "snapshot failed".toCharArray()
            val output = ByteArrayOutputStream()

            val thrown = expectThrows<ExpectedSnapshotException> {
                coordinator.export(BackupMode.STANDARD, output, passphrase, metadata())
            }

            assertTrue(thrown === expected)
            assertTrue(passphrase.all { it == '\u0000' })
            assertEquals(0, output.size())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun documentContractUsesProductSpecificPortableNames() {
        assertEquals(".terminalspike-backup", BackupDocumentContract.FILE_EXTENSION)
        assertEquals(
            "application/vnd.yanjiyu.terminalspike.backup",
            BackupDocumentContract.MIME_TYPE,
        )
        assertEquals(
            "terminal-spike-1725000000000.terminalspike-backup",
            BackupDocumentContract.suggestedFileName(1_725_000_000_000L),
        )
    }

    private suspend fun withCoordinator(
        block: suspend (BackupTransferCoordinator, RecordingSnapshotProvider) -> Unit,
    ) {
        val directory = Files.createTempDirectory("backup-transfer-test").toFile()
        try {
            val provider = RecordingSnapshotProvider()
            val coordinator = BackupTransferCoordinator(
                snapshots = provider,
                archives = BackupArchiveCodec(EncryptedFileBackupStagingFactory(directory)),
                kdfIterations = BackupKdfIterationsProvider { BackupEnvelopeFormat.MIN_KDF_ITERATIONS },
                ioDispatcher = Dispatchers.Unconfined,
            )
            block(coordinator, provider)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class RecordingSnapshotProvider : BackupSnapshotProvider {
        val requests = mutableListOf<BackupMode>()

        override suspend fun create(mode: BackupMode): BackupPayloadSnapshot {
            requests += mode
            return BackupPayloadSnapshot(
                mode = mode,
                snippets = listOf(
                    Snippet(
                        id = SNIPPET_ID,
                        name = "Safe command",
                        command = "printf safe",
                        createdAtEpochMillis = 1,
                        updatedAtEpochMillis = 2,
                    ),
                ),
            )
        }
    }

    private class TrackingOutputStream : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        var closed = false
            private set
        var flushed = false
            private set

        override fun write(value: Int) = bytes.write(value)

        override fun write(buffer: ByteArray, offset: Int, length: Int) = bytes.write(buffer, offset, length)

        override fun flush() {
            flushed = true
        }

        override fun close() {
            closed = true
        }

        fun size(): Int = bytes.size()

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private fun metadata(mode: BackupMode = BackupMode.STANDARD) = BackupEnvelopeMetadata(
        mode = mode,
        createdAtEpochMillis = 1_725_000_000_000L,
        appVersion = BackupAppVersion(42, "42"),
        payloadSchemaVersion = BackupPayloadFormat.SCHEMA_VERSION,
        compatibility = BackupCompatibility(26, 37, 0),
    )

    private suspend inline fun <reified T : Throwable> expectThrows(noinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.name}, got ${error::class.java.name}", error)
        }
        throw AssertionError("Expected ${T::class.java.name}.")
    }

    private class ExpectedSnapshotException : Exception()

    private companion object {
        val SNIPPET_ID: String = UUID.fromString("60000000-0000-4000-8000-000000000006").toString()
    }
}
