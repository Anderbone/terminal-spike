package com.yanjiyu.terminalspike.connection

import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SftpTransferTransactionsTest {
    @Test
    fun pendingDownloadCommitsOnlyAfterWriteAndCloseSucceed() {
        val pending = RecordingPendingDownload()

        writePendingDownload(pending) { it.write(byteArrayOf(1, 2, 3)) }

        assertEquals(listOf("close", "commit"), pending.events)
        assertEquals(listOf<Byte>(1, 2, 3), pending.bytes.toByteArray().toList())
    }

    @Test
    fun pendingDownloadAbortsForWriteCloseAndCommitFailures() {
        FailurePoint.entries.forEach { point ->
            val pending = RecordingPendingDownload(point)

            assertThrows(IllegalStateException::class.java) {
                writePendingDownload(pending) { output ->
                    output.write(1)
                    if (point == FailurePoint.MID_WRITE) error("mid-write")
                    if (point == FailurePoint.BEFORE_WRITE) error("before-write")
                }
            }

            assertEquals(1, pending.events.count { it == "abort" })
            if (point != FailurePoint.COMMIT) {
                assertEquals(0, pending.events.count { it == "commit" })
            }
        }
    }

    @Test
    fun destinationCompletesOnceOrAbortsOnce() = runTest {
        val successful = RecordingDestination()
        withDownloadDestination(successful) { }
        assertEquals(listOf("complete"), successful.events)

        val failed = RecordingDestination()
        try {
            withDownloadDestination(failed) { error("mid-transfer") }
        } catch (_: IllegalStateException) {
            // Expected.
        }
        assertEquals(listOf("abort"), failed.events)
    }

    @Test
    fun atomicFileWriteRenamesOnlyAfterSuccessAndCleansEveryFailureStage() {
        listOf("write", "rename").forEach { failure ->
            val events = mutableListOf<String>()
            assertThrows(IllegalStateException::class.java) {
                atomicRemoteFileWrite(
                    temporaryPath = "/.temporary",
                    finalPath = "/final",
                    write = {
                        events += "write:$it"
                        if (failure == "write") error("write")
                    },
                    rename = { from, to ->
                        events += "rename:$from:$to"
                        if (failure == "rename") error("rename")
                    },
                    removeTemporary = { events += "remove:$it" },
                )
            }
            assertEquals("remove:/.temporary", events.last())
            if (failure == "write") assertEquals(0, events.count { it.startsWith("rename:") })
        }

        val success = mutableListOf<String>()
        atomicRemoteFileWrite(
            "/.temporary",
            "/final",
            write = { success += "write" },
            rename = { _, _ -> success += "rename" },
            removeTemporary = { success += "remove" },
        )
        assertEquals(listOf("write", "rename"), success)
    }

    @Test
    fun atomicDirectoryWriteCleansCreatePopulateAndRenameFailures() = runTest {
        listOf("create", "populate", "rename").forEach { failure ->
            val events = mutableListOf<String>()
            try {
                atomicRemoteDirectoryWrite(
                    temporaryPath = "/.temporary",
                    finalPath = "/final",
                    createTemporary = {
                        events += "create"
                        if (failure == "create") error("create")
                    },
                    populate = {
                        events += "populate"
                        if (failure == "populate") error("populate")
                    },
                    rename = { _, _ ->
                        events += "rename"
                        if (failure == "rename") error("rename")
                    },
                    cleanupTemporary = { events += "cleanup" },
                )
            } catch (_: IllegalStateException) {
                // Expected.
            }
            assertEquals("cleanup", events.last())
        }
    }
}

private enum class FailurePoint { BEFORE_WRITE, MID_WRITE, CLOSE, COMMIT }

private class RecordingPendingDownload(
    private val failurePoint: FailurePoint? = null,
) : SftpPendingDownload {
    val bytes = ByteArrayOutputStream()
    val events = mutableListOf<String>()
    override val stream: OutputStream = object : FilterOutputStream(bytes) {
        override fun close() {
            events += "close"
            if (failurePoint == FailurePoint.CLOSE) error("close")
            super.close()
        }
    }

    override fun commit() {
        events += "commit"
        if (failurePoint == FailurePoint.COMMIT) error("commit")
    }

    override fun abort() {
        if (events.lastOrNull() != "abort") events += "abort"
    }
}

private class RecordingDestination : SftpDownloadDestination {
    val events = mutableListOf<String>()
    override fun createDirectory(relativePath: String) = Unit
    override fun openFile(relativePath: String): SftpPendingDownload = error("Not used")
    override fun complete() {
        events += "complete"
    }
    override fun abort() {
        events += "abort"
    }
}
