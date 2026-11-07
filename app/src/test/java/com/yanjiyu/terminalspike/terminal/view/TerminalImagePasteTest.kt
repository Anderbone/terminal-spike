package com.yanjiyu.terminalspike.terminal.view

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerminalImagePasteTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun arbitraryTypesKeepTheirExtensionsWithoutTrustingProviderPaths() {
        listOf("photo.heic", "photo.avif", "drawing.svg", "movie.mkv", "paper.pdf", "archive.zip").forEach {
            assertTrue(terminalAttachmentFileName("application/octet-stream", it).endsWith(".${it.substringAfterLast('.')}"))
        }
        assertTrue(terminalAttachmentFileName("unknown/format", null).endsWith(".bin"))
        val unsafe = terminalAttachmentFileName("image/png", "../../x;touch /tmp/pwned")
        assertTrue(unsafe.matches(Regex("[a-f0-9-]{36}\\.png")))
    }

    @Test
    fun moreThanTwentyMixedFilesTransferConcurrentlyButInsertInSelectionOrder() = runBlocking {
        withTimeout(10_000) {
            val firstMayFinish = CompletableDeferred<Unit>()
            val secondFinished = CompletableDeferred<Unit>()
            val released = AtomicInteger()
            val inserted = mutableListOf<String>()
            val batch = async {
                transferTerminalAttachments(
                    (0 until 25).map { index ->
                        TerminalImagePasteSource("unknown/format", { ByteArrayInputStream(byteArrayOf(index.toByte())) },
                            { released.incrementAndGet() }, { "file.pdf" })
                    },
                    transfer = { name, input ->
                        assertTrue(name.endsWith(".pdf"))
                        val index = input.read()
                        if (index == 0) firstMayFinish.await()
                        if (index == 1) secondFinished.complete(Unit)
                        "/cache/$index"
                    },
                    insert = { inserted.add(it) },
                )
            }
            secondFinished.await()
            assertTrue(inserted.isEmpty())
            firstMayFinish.complete(Unit)
            assertEquals(25, batch.await())
            assertEquals((0 until 25).map { "/cache/$it" }, inserted)
            assertEquals(25, released.get())
        }
    }

    @Test
    fun fileLargerThanOneHundredMiBStreamsCompletelyWithoutBufferingTheFile() = runBlocking {
        val size = 100L * 1024 * 1024 + 17
        var remaining = size
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = if (remaining-- > 0) 42 else -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                bytes.fill(42, offset, offset + count)
                remaining -= count
                return count
            }
            override fun close() { closed = true }
        }
        var received = 0L
        assertEquals(1, transferTerminalAttachments(
            listOf(TerminalImagePasteSource("video/mp4", { stream })),
            transfer = { _, input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    for (index in 0 until count) assertEquals(42.toByte(), buffer[index])
                    received += count
                }
                "/cache/video.mp4"
            }, insert = { true },
        ))
        assertEquals(size, received)
        assertTrue(closed)
    }

    @Test
    fun failedFileReleasesItsGrantAndDoesNotDiscardTheOtherFiles() = runBlocking {
        val released = AtomicInteger()
        val inserted = mutableListOf<String>()
        assertEquals(1, transferTerminalAttachments(
            listOf(
                TerminalImagePasteSource("application/pdf", { throw IOException() }, { released.incrementAndGet() }),
                TerminalImagePasteSource("video/mp4", { ByteArrayInputStream(byteArrayOf(7)) }, { released.incrementAndGet() }),
            ),
            transfer = { _, input -> assertEquals(7, input.read()); "/cache/ok" },
            insert = { inserted.add(it) },
        ))
        assertEquals(listOf("/cache/ok"), inserted)
        assertEquals(2, released.get())
    }

    @Test
    fun cancellationReleasesEveryGrantAndClosesOpenedStreams() = runBlocking {
        withTimeout(10_000) {
            val started = CompletableDeferred<Unit>()
            val released = AtomicInteger()
            val closed = AtomicInteger()
            val batch = launch {
                transferTerminalAttachments(
                    List(25) { TerminalImagePasteSource("application/octet-stream", {
                        object : ByteArrayInputStream(byteArrayOf(1)) {
                            override fun close() { closed.incrementAndGet(); super.close() }
                        }
                    }, { released.incrementAndGet() }) },
                    transfer = { _, _ -> started.complete(Unit); awaitCancellation() },
                    insert = { fail("Cancelled files must not be inserted"); false },
                )
            }
            started.await()
            batch.cancelAndJoin()
            assertEquals(25, released.get())
            assertTrue(closed.get() > 0)
        }
    }

    @Test
    fun localFilesKeepExactBytesAndRemovePartialWrites() {
        val directory = temporary.newFolder()
        val name = terminalAttachmentFileName("application/zip", "archive.zip")
        val bytes = byteArrayOf(0, 1, -1, 13, 10)
        val path = storeTerminalAttachment(directory, name, ByteArrayInputStream(bytes))
        assertArrayEquals(bytes, java.io.File(path).readBytes())
        val failedName = terminalAttachmentFileName("application/pdf", "paper.pdf")
        assertThrows(IOException::class.java) {
            storeTerminalAttachment(directory, failedName, object : InputStream() {
                override fun read(): Int = throw IOException("provider failed")
            })
        }
        assertFalse(java.io.File(directory, failedName).exists())
    }
}
