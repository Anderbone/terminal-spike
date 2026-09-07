package com.yanjiyu.terminalspike.ui.sftp

import com.yanjiyu.terminalspike.connection.writePendingDownload
import com.yanjiyu.terminalspike.connection.withDownloadDestination
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SftpLocalDestinationTest {
    @Test
    fun documentFileAppearsUnderFinalNameOnlyAfterExplicitCommit() {
        val store = FakeDocumentStore().apply { addExisting("root", "report.txt") }
        val destination = DocumentTreeDownloadDestination(store)
        val pending = destination.openFile("report.txt")

        pending.stream.write(byteArrayOf(1, 2, 3))
        pending.stream.close()

        assertFalse("report (1).txt" in store.childNames("root"))
        assertTrue(store.childNames("root").single { it != "report.txt" }.startsWith(".terminal-spike-"))

        pending.commit()
        destination.complete()

        assertEquals(setOf("report.txt", "report (1).txt"), store.childNames("root"))
        assertEquals(byteArrayOf(1, 2, 3).toList(), store.bytesNamed("report (1).txt").toList())
    }

    @Test
    fun documentAbortRemovesOnlyOperationOwnedTreeAndIsIdempotent() {
        val store = FakeDocumentStore().apply { addExisting("root", "keep.txt") }
        val destination = DocumentTreeDownloadDestination(store)
        destination.createDirectory("folder")
        val pending = destination.openFile("folder/partial.txt")
        pending.stream.write(1)

        destination.abort()
        destination.abort()

        assertEquals(setOf("keep.txt"), store.childNames("root"))
        assertEquals(1, store.deleted.count { it.startsWith("created-") && it.endsWith("-folder") })
    }

    @Test
    fun documentRenameFailureDeletesTemporaryAndNeverPublishesFinalName() {
        val store = FakeDocumentStore(renameSucceeds = false)
        val destination = DocumentTreeDownloadDestination(store)
        val pending = destination.openFile("result.txt")
        pending.stream.write(1)
        pending.stream.close()

        assertThrows(IllegalStateException::class.java) { pending.commit() }
        destination.abort()

        assertEquals(emptySet<String>(), store.childNames("root"))
    }

    @Test
    fun mediaStoreCloseAndFileCommitDoNotPublishBeforeDestinationCompletes() {
        val store = FakePendingMediaStore()
        val destination = PhoneDownloadsDestination(store, "Download")
        val pending = destination.openFile("folder/result.txt")

        pending.stream.write(7)
        pending.stream.close()
        pending.commit()

        assertFalse(store.items.values.single().published)
        destination.complete()
        assertTrue(store.items.values.single().published)
    }

    @Test
    fun mediaStorePublishFailureAbortsEveryPendingRow() = runTest {
        val store = FakePendingMediaStore(publishSucceeds = false)
        val destination = PhoneDownloadsDestination(store, "Download")

        try {
            withDownloadDestination(destination) {
                writePendingDownload(destination.openFile("one.txt")) { it.write(1) }
                writePendingDownload(destination.openFile("two.txt")) { it.write(2) }
            }
        } catch (_: IllegalStateException) {
            // Expected.
        }

        assertEquals(emptyMap<String, FakeMediaItem>(), store.items)
        assertEquals(2, store.deleted.size)
    }
}

private class FakeDocumentStore(
    private val renameSucceeds: Boolean = true,
) : SftpDocumentStore {
    override val root: String = "root"
    private var nextId = 0
    private val documents = mutableMapOf(
        root to FakeDocument(parent = "", name = "root", directory = true),
    )
    val deleted = mutableListOf<String>()

    fun addExisting(parent: String, name: String) {
        documents["existing-$name"] = FakeDocument(parent, name, directory = false)
    }

    override fun childNames(parent: String): Set<String> = documents.values
        .filter { it.parent == parent }
        .mapTo(mutableSetOf(), FakeDocument::name)

    override fun create(parent: String, mimeType: String, displayName: String): String {
        check(parent in documents)
        val id = "created-${nextId++}-$displayName"
        documents[id] = FakeDocument(
            parent = parent,
            name = displayName,
            directory = mimeType == "vnd.android.document/directory",
        )
        return id
    }

    override fun openOutput(document: String): OutputStream = documents.getValue(document).bytes

    override fun rename(document: String, displayName: String): String? {
        if (!renameSucceeds) return null
        documents.getValue(document).name = displayName
        return document
    }

    override fun delete(document: String) {
        if (document !in documents) return
        documents.keys.filter { child -> documents[child]?.parent == document }.toList().forEach(::delete)
        documents.remove(document)
        deleted += document
    }

    fun bytesNamed(name: String): ByteArray = documents.values.single { it.name == name }.bytes.toByteArray()
}

private data class FakeDocument(
    val parent: String,
    var name: String,
    val directory: Boolean,
    val bytes: ByteArrayOutputStream = ByteArrayOutputStream(),
)

private class FakePendingMediaStore(
    private val publishSucceeds: Boolean = true,
) : SftpPendingMediaStore {
    var nextId = 0
    val items = mutableMapOf<String, FakeMediaItem>()
    val deleted = mutableListOf<String>()

    override fun insert(displayName: String, mimeType: String, relativePath: String): String {
        val id = "item-${nextId++}"
        items[id] = FakeMediaItem(displayName, mimeType, relativePath)
        return id
    }

    override fun openOutput(item: String): OutputStream = items.getValue(item).bytes

    override fun publish(item: String): Boolean {
        if (!publishSucceeds) return false
        items.getValue(item).published = true
        return true
    }

    override fun delete(item: String) {
        items.remove(item)
        deleted += item
    }
}

private data class FakeMediaItem(
    val displayName: String,
    val mimeType: String,
    val relativePath: String,
    val bytes: ByteArrayOutputStream = ByteArrayOutputStream(),
    var published: Boolean = false,
)
