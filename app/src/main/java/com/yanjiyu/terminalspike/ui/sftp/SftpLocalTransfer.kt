package com.yanjiyu.terminalspike.ui.sftp

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import com.yanjiyu.terminalspike.connection.SftpDownloadDestination
import com.yanjiyu.terminalspike.connection.SftpPendingDownload
import com.yanjiyu.terminalspike.connection.SftpTraversalLimits
import com.yanjiyu.terminalspike.connection.SftpTreeNode
import com.yanjiyu.terminalspike.connection.SftpUploadEntry
import com.yanjiyu.terminalspike.connection.SftpUploadSource
import com.yanjiyu.terminalspike.connection.walkSftpTree
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import java.util.UUID

internal interface SftpDocumentStore {
    val root: String
    fun childNames(parent: String): Set<String>
    fun create(parent: String, mimeType: String, displayName: String): String
    fun openOutput(document: String): OutputStream
    fun rename(document: String, displayName: String): String?
    fun delete(document: String)
}

internal interface SftpPendingMediaStore {
    fun insert(displayName: String, mimeType: String, relativePath: String): String
    fun openOutput(item: String): OutputStream
    fun publish(item: String): Boolean
    fun delete(item: String)
}

internal data class SftpDocumentTreeEntry(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
)

internal interface SftpDocumentTreeStore {
    val rootId: String
    val rootName: String
    fun children(parentId: String): List<SftpDocumentTreeEntry>
    fun openInput(documentId: String): InputStream
}

internal class DocumentTreeDownloadDestination(
    private val store: SftpDocumentStore,
) : SftpDownloadDestination {
    constructor(resolver: ContentResolver, treeUri: Uri) : this(
        AndroidSftpDocumentStore(resolver, treeUri),
    )

    private val directories = mutableMapOf("" to store.root)
    private val ownedDocuments = mutableListOf<String>()
    private val pendingFiles = mutableListOf<DocumentPendingDownload>()
    private var finished = false

    override fun createDirectory(relativePath: String) {
        val parentPath = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        val parent = requireNotNull(directories[parentPath]) { "Cannot create $relativePath." }
        check(!finished) { "This download destination is already finished." }
        directories[relativePath] = createUniqueDocument(
            parent = parent,
            mimeType = DIRECTORY_MIME_TYPE,
            displayName = name,
        ).also(ownedDocuments::add)
    }

    override fun openFile(relativePath: String): SftpPendingDownload {
        check(!finished) { "This download destination is already finished." }
        val parentPath = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        val parent = requireNotNull(directories[parentPath]) { "Cannot create $relativePath." }
        val finalName = uniqueName(name, store.childNames(parent))
        val temporaryName = ".terminal-spike-${UUID.randomUUID()}.part"
        val document = store.create(parent, mimeType(name), temporaryName)
        ownedDocuments += document
        val output = try {
            store.openOutput(document)
        } catch (error: Throwable) {
            runCatching { store.delete(document) }
            ownedDocuments.remove(document)
            throw error
        }
        return DocumentPendingDownload(document, finalName, output).also(pendingFiles::add)
    }

    override fun complete() {
        check(!finished) { "This download destination is already finished." }
        check(pendingFiles.all(DocumentPendingDownload::isCommitted)) {
            "A downloaded file was not committed."
        }
        finished = true
        pendingFiles.clear()
        ownedDocuments.clear()
    }

    override fun abort() {
        if (finished) return
        finished = true
        pendingFiles.asReversed().forEach(DocumentPendingDownload::abort)
        ownedDocuments.asReversed().forEach { document ->
            runCatching { store.delete(document) }
        }
        pendingFiles.clear()
        ownedDocuments.clear()
        directories.keys.filter(String::isNotEmpty).forEach(directories::remove)
    }

    private fun createUniqueDocument(parent: String, mimeType: String, displayName: String): String {
        val names = store.childNames(parent)
        val uniqueName = uniqueName(displayName, names)
        return store.create(parent, mimeType, uniqueName)
    }

    private inner class DocumentPendingDownload(
        initialDocument: String,
        private val finalName: String,
        override val stream: OutputStream,
    ) : SftpPendingDownload {
        private var document = initialDocument
        private var state = PendingState.OPEN
        val isCommitted: Boolean
            get() = state == PendingState.COMMITTED

        override fun commit() {
            check(state == PendingState.OPEN) { "The downloaded file is already finished." }
            val renamed = store.rename(document, finalName)
            if (renamed == null) {
                abort()
                error("Cannot publish $finalName in the selected folder.")
            }
            val index = ownedDocuments.indexOf(document)
            if (index >= 0) ownedDocuments[index] = renamed
            document = renamed
            state = PendingState.COMMITTED
        }

        override fun abort() {
            if (state == PendingState.ABORTED) return
            state = PendingState.ABORTED
            runCatching { stream.close() }
            runCatching { store.delete(document) }
            ownedDocuments.remove(document)
        }
    }

    private companion object {
        const val DIRECTORY_MIME_TYPE = "vnd.android.document/directory"
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
internal class PhoneDownloadsDestination(
    private val store: SftpPendingMediaStore,
    private val downloadsDirectory: String,
) : SftpDownloadDestination {
    constructor(resolver: ContentResolver) : this(
        AndroidSftpPendingMediaStore(resolver),
        Environment.DIRECTORY_DOWNLOADS,
    ) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Choose a phone folder for this Android version."
        }
    }
    private val pendingFiles = mutableListOf<PhonePendingDownload>()
    private var finished = false

    override fun createDirectory(relativePath: String) = Unit

    override fun openFile(relativePath: String): SftpPendingDownload {
        check(!finished) { "This download destination is already finished." }
        val parentPath = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        val relativePath = listOf(downloadsDirectory, parentPath)
            .filter(String::isNotBlank)
            .joinToString("/", postfix = "/")
        val item = store.insert(name, mimeType(name), relativePath)
        val output = try {
            store.openOutput(item)
        } catch (error: Throwable) {
            runCatching { store.delete(item) }
            throw error
        }
        return PhonePendingDownload(item, output).also(pendingFiles::add)
    }

    override fun complete() {
        check(!finished) { "This download destination is already finished." }
        check(pendingFiles.all(PhonePendingDownload::isCommitted)) {
            "A downloaded file was not committed."
        }
        pendingFiles.forEach { pending ->
            check(store.publish(pending.item)) { "Cannot publish a downloaded file in Downloads." }
        }
        finished = true
        pendingFiles.clear()
    }

    override fun abort() {
        if (finished) return
        finished = true
        pendingFiles.asReversed().forEach(PhonePendingDownload::abort)
        pendingFiles.clear()
    }

    private inner class PhonePendingDownload(
        val item: String,
        override val stream: OutputStream,
    ) : SftpPendingDownload {
        private var state = PendingState.OPEN
        val isCommitted: Boolean
            get() = state == PendingState.COMMITTED

        override fun commit() {
            check(state == PendingState.OPEN) { "The downloaded file is already finished." }
            state = PendingState.COMMITTED
        }

        override fun abort() {
            if (state == PendingState.ABORTED) return
            state = PendingState.ABORTED
            runCatching { stream.close() }
            runCatching { store.delete(item) }
        }
    }
}

private class AndroidSftpDocumentStore(
    private val resolver: ContentResolver,
    treeUri: Uri,
) : SftpDocumentStore {
    override val root: String = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    ).toString()

    override fun childNames(parent: String): Set<String> {
        val parentUri = Uri.parse(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentUri,
            DocumentsContract.getDocumentId(parentUri),
        )
        return resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }.orEmpty()
    }

    override fun create(parent: String, mimeType: String, displayName: String): String =
        requireNotNull(
            DocumentsContract.createDocument(resolver, Uri.parse(parent), mimeType, displayName),
        ) { "Cannot create $displayName." }.toString()

    override fun openOutput(document: String): OutputStream = requireNotNull(
        resolver.openOutputStream(Uri.parse(document), "w"),
    ) { "Cannot write the selected destination." }

    override fun rename(document: String, displayName: String): String? =
        DocumentsContract.renameDocument(resolver, Uri.parse(document), displayName)?.toString()

    override fun delete(document: String) {
        DocumentsContract.deleteDocument(resolver, Uri.parse(document))
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private class AndroidSftpPendingMediaStore(
    private val resolver: ContentResolver,
) : SftpPendingMediaStore {
    override fun insert(displayName: String, mimeType: String, relativePath: String): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return requireNotNull(
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
        ) { "Cannot create $displayName in Downloads." }.toString()
    }

    override fun openOutput(item: String): OutputStream = requireNotNull(
        resolver.openOutputStream(Uri.parse(item), "w"),
    ) { "Cannot write a file in Downloads." }

    override fun publish(item: String): Boolean = resolver.update(
        Uri.parse(item),
        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
        null,
        null,
    ) == 1

    override fun delete(item: String) {
        resolver.delete(Uri.parse(item), null, null)
    }
}

private enum class PendingState { OPEN, COMMITTED, ABORTED }

internal fun readUploadFiles(
    resolver: ContentResolver,
    uris: List<Uri>,
): List<Pair<String, () -> InputStream>> = uris.map { uri ->
    val name = queryDisplayName(resolver, uri) ?: "upload"
    name to { requireNotNull(resolver.openInputStream(uri)) { "Cannot read $name." } }
}

internal fun readUploadFolder(
    resolver: ContentResolver,
    treeUri: Uri,
    limits: SftpTraversalLimits = SftpTraversalLimits(),
): SftpUploadSource {
    return DocumentTreeUploadSource(AndroidSftpDocumentTreeStore(resolver, treeUri), limits)
}

internal class DocumentTreeUploadSource(
    private val store: SftpDocumentTreeStore,
    private val limits: SftpTraversalLimits,
) : SftpUploadSource {
    override val rootName: String = store.rootName

    override suspend fun consume(consumer: suspend (SftpUploadEntry) -> Unit) {
        val root = LocalDocumentNode(store.rootId, "", isDirectory = true)
        walkSftpTree(
            root = root.toTreeNode(),
            limits = limits,
            children = ::children,
            onEnter = { node, _ ->
                if (node.relativePath.isNotEmpty()) {
                    consumer(
                        SftpUploadEntry(
                            relativePath = node.relativePath,
                            isDirectory = node.isDirectory,
                            open = if (node.isDirectory) null else {
                                {
                                    store.openInput(node.id)
                                }
                            },
                        ),
                    )
                }
            },
        )
    }

    private fun children(parent: LocalDocumentNode): List<SftpTreeNode<LocalDocumentNode>> {
        return store.children(parent.id).map { child ->
            val path = if (parent.relativePath.isEmpty()) {
                child.name
            } else {
                "${parent.relativePath}/${child.name}"
            }
            LocalDocumentNode(
                id = child.id,
                relativePath = path,
                isDirectory = child.isDirectory,
            ).toTreeNode()
        }
    }
}

private data class LocalDocumentNode(
    val id: String,
    val relativePath: String,
    val isDirectory: Boolean,
) {
    fun toTreeNode(): SftpTreeNode<LocalDocumentNode> = SftpTreeNode(
        value = this,
        isDirectory = isDirectory,
        directoryIdentity = id.takeIf { isDirectory },
    )
}

private class AndroidSftpDocumentTreeStore(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : SftpDocumentTreeStore {
    private val rootDocument = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    override val rootId: String = DocumentsContract.getDocumentId(rootDocument)
    override val rootName: String = queryDisplayName(resolver, rootDocument) ?: "folder"

    override fun children(parentId: String): List<SftpDocumentTreeEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        return resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SftpDocumentTreeEntry(
                            id = cursor.getString(idColumn),
                            name = cursor.getString(nameColumn),
                            isDirectory = cursor.getString(typeColumn) ==
                                DocumentsContract.Document.MIME_TYPE_DIR,
                        ),
                    )
                }
            }
        } ?: error("Cannot read the selected phone folder.")
    }

    override fun openInput(documentId: String): InputStream {
        val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return requireNotNull(resolver.openInputStream(document)) {
            "Cannot read the selected phone file."
        }
    }
}

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

private fun uniqueName(name: String, existing: Set<String>): String {
    if (name !in existing) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val base = name.substring(0, dot)
    val extension = name.substring(dot)
    var suffix = 1
    while (true) {
        val candidate = "$base (${suffix++})$extension"
        if (candidate !in existing) return candidate
    }
}

private fun mimeType(name: String): String =
    URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
