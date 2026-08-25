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
import com.yanjiyu.terminalspike.connection.SftpUploadEntry
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection

internal class DocumentTreeDownloadDestination(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : SftpDownloadDestination {
    private val directories = mutableMapOf(
        "" to DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        ),
    )

    override fun createDirectory(relativePath: String) {
        val parentPath = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        val parent = requireNotNull(directories[parentPath]) { "Cannot create $relativePath." }
        directories[relativePath] = createUniqueDocument(
            parent = parent,
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            displayName = name,
        )
    }

    override fun openFile(relativePath: String): OutputStream {
        val parentPath = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        val parent = requireNotNull(directories[parentPath]) { "Cannot create $relativePath." }
        val document = createUniqueDocument(parent, mimeType(name), name)
        return requireNotNull(resolver.openOutputStream(document, "w")) { "Cannot write $name." }
    }

    private fun createUniqueDocument(parent: Uri, mimeType: String, displayName: String): Uri {
        val names = childNames(parent)
        val uniqueName = uniqueName(displayName, names)
        return requireNotNull(DocumentsContract.createDocument(resolver, parent, mimeType, uniqueName)) {
            "Cannot create $uniqueName."
        }
    }

    private fun childNames(parent: Uri): Set<String> {
        val parentId = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
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
}

@RequiresApi(Build.VERSION_CODES.Q)
internal class PhoneDownloadsDestination(
    private val resolver: ContentResolver,
) : SftpDownloadDestination {
    init {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Choose a phone folder for this Android version."
        }
    }

    override fun createDirectory(relativePath: String) = Unit

    override fun openFile(relativePath: String): OutputStream {
        val parentPath = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(name))
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                listOf(Environment.DIRECTORY_DOWNLOADS, parentPath)
                    .filter(String::isNotBlank)
                    .joinToString("/", postfix = "/"),
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
        ) { "Cannot create $name in Downloads." }
        val output = try {
            requireNotNull(resolver.openOutputStream(uri, "w")) { "Cannot write $name." }
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
        return object : FilterOutputStream(output) {
            override fun close() {
                try {
                    super.close()
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    )
                } catch (error: Throwable) {
                    resolver.delete(uri, null, null)
                    throw error
                }
            }
        }
    }
}

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
): Pair<String, List<SftpUploadEntry>> {
    val rootDocument = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    val rootName = queryDisplayName(resolver, rootDocument) ?: "folder"
    val entries = mutableListOf(SftpUploadEntry(relativePath = "", isDirectory = true))

    fun visit(parent: Uri, parentPath: String) {
        val parentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        resolver.query(
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
            while (cursor.moveToNext()) {
                val child = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    cursor.getString(idColumn),
                )
                val name = cursor.getString(nameColumn)
                val path = if (parentPath.isEmpty()) name else "$parentPath/$name"
                val isDirectory = cursor.getString(typeColumn) == DocumentsContract.Document.MIME_TYPE_DIR
                entries += SftpUploadEntry(
                    relativePath = path,
                    isDirectory = isDirectory,
                    open = if (isDirectory) null else {
                        { requireNotNull(resolver.openInputStream(child)) { "Cannot read $path." } }
                    },
                )
                if (isDirectory) visit(child, path)
            }
        } ?: error("Cannot read the selected phone folder.")
    }

    visit(rootDocument, "")
    return rootName to entries
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
