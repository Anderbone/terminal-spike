package com.yanjiyu.terminalspike.ui.sftp

import com.yanjiyu.terminalspike.connection.SftpTraversalException
import com.yanjiyu.terminalspike.connection.SftpTraversalLimits
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SftpDocumentTreeUploadSourceTest {
    @Test
    fun streamsDepthFirstAndOpensEachFileOnlyWhenConsumerRequestsIt() = runTest {
        val store = FakeDocumentTreeStore(
            children = mapOf(
                "root" to listOf(directory("dir"), file("file")),
                "dir" to listOf(file("nested")),
            ),
        )
        val source = DocumentTreeUploadSource(store, SftpTraversalLimits())
        val consumed = mutableListOf<String>()

        source.consume { entry ->
            consumed += entry.relativePath
            assertEquals(false, entry.relativePath.substringAfterLast('/') in store.opened)
            entry.open?.invoke()?.use { it.readBytes() }
        }

        assertEquals("Selected folder", source.rootName)
        assertEquals(listOf("dir", "dir/nested", "file"), consumed)
        assertEquals(listOf("nested", "file"), store.opened)
    }

    @Test
    fun rejectsProviderDirectoryCycleByOpaqueDocumentId() {
        val store = FakeDocumentTreeStore(
            children = mapOf("root" to listOf(directory("root", name = "alias"))),
        )

        assertThrows(SftpTraversalException::class.java) {
            runTest {
                DocumentTreeUploadSource(store, SftpTraversalLimits()).consume { }
            }
        }
        assertEquals(emptyList<String>(), store.opened)
    }

    @Test
    fun cancellationStopsTraversalBeforeOpeningAnotherProviderFile() {
        val store = FakeDocumentTreeStore(
            children = mapOf("root" to listOf(file("first"), file("second"))),
        )

        assertThrows(CancellationException::class.java) {
            runTest {
                DocumentTreeUploadSource(store, SftpTraversalLimits()).consume { entry ->
                    entry.open?.invoke()?.use { it.read() }
                    cancel()
                }
            }
        }
        assertEquals(listOf("first"), store.opened)
    }

    @Test
    fun injectedEntryLimitStopsBeforeAnAdditionalFileCanOpen() {
        val store = FakeDocumentTreeStore(
            children = mapOf("root" to listOf(file("one"), file("two"))),
        )

        assertThrows(SftpTraversalException::class.java) {
            runTest {
                DocumentTreeUploadSource(
                    store,
                    SftpTraversalLimits(maxEntries = 2),
                ).consume { entry -> entry.open?.invoke()?.close() }
            }
        }
        assertEquals(listOf("one"), store.opened)
    }
}

private class FakeDocumentTreeStore(
    private val children: Map<String, List<SftpDocumentTreeEntry>>,
) : SftpDocumentTreeStore {
    override val rootId: String = "root"
    override val rootName: String = "Selected folder"
    val opened = mutableListOf<String>()

    override fun children(parentId: String): List<SftpDocumentTreeEntry> =
        children[parentId].orEmpty()

    override fun openInput(documentId: String): InputStream {
        opened += documentId
        return ByteArrayInputStream(documentId.encodeToByteArray())
    }
}

private fun directory(id: String, name: String = id) =
    SftpDocumentTreeEntry(id, name, isDirectory = true)

private fun file(id: String, name: String = id) =
    SftpDocumentTreeEntry(id, name, isDirectory = false)
