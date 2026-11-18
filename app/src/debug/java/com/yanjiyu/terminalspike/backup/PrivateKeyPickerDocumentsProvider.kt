package com.yanjiyu.terminalspike.backup

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.yanjiyu.terminalspike.BuildConfig
import java.io.File
import java.io.FileNotFoundException

/** Enabled only for the picker regression; supplies an extensionless opaque document. */
class PrivateKeyPickerDocumentsProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = BuildConfig.APPLICATION_ID + ".test.keypicker.documents"
    }

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES)).apply {
            addRow(columnNames.map { column ->
                when (column) {
                    Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID -> "root"
                    Root.COLUMN_TITLE -> "SSH key picker fixture"
                    Root.COLUMN_FLAGS -> Root.FLAG_LOCAL_ONLY
                    Root.COLUMN_MIME_TYPES -> "*/*"
                    else -> null
                }
            })
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        cursor(projection).apply { include(documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (parentDocumentId != "root") throw FileNotFoundException(parentDocumentId)
        return cursor(projection).apply {
            directory().listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".mime") }
                .forEach { include(it.name) }
        }
    }

    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path {
        if (parentDocumentId != null && parentDocumentId != "root") throw java.io.FileNotFoundException()
        if (childDocumentId != "root" && !file(childDocumentId).isFile) throw java.io.FileNotFoundException()
        return DocumentsContract.Path(
            if (parentDocumentId == null) "root" else null,
            if (childDocumentId == "root") listOf("root") else listOf("root", childDocumentId),
        )
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        require(mode == "r")
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    ))

    override fun getDocumentType(documentId: String): String =
        if (documentId == "root") Document.MIME_TYPE_DIR else mimeType(file(documentId))

    private fun MatrixCursor.include(id: String) {
        // Validate before resolving metadata, including when the projection omits the ID.
        val document = if (id == "root") null else file(id)
        addRow(columnNames.map { column ->
            when (column) {
                Document.COLUMN_DOCUMENT_ID -> id
                Document.COLUMN_DISPLAY_NAME -> if (id == "root") "SSH key picker fixture" else id
                Document.COLUMN_MIME_TYPE -> document?.let(::mimeType) ?: Document.MIME_TYPE_DIR
                Document.COLUMN_FLAGS -> 0
                Document.COLUMN_SIZE -> document?.length() ?: 0L
                else -> null
            }
        })
    }

    private fun mimeType(document: File): String =
        directory().resolve("${document.name}.mime").takeIf { it.isFile }?.readText()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "application/x-ssh-private-key"

    private fun directory() = requireNotNull(context).filesDir.resolve("key-picker-fixtures")
    private fun file(id: String): File {
        if (id == "root" || id.isEmpty() || id == "." || id == ".." ||
            id.any { it == '/' || it == '\\' || it.isISOControl() }) {
            throw FileNotFoundException(id)
        }
        val directory = directory().canonicalFile
        val document = directory.resolve(id).canonicalFile
        if (document.parentFile != directory || !document.isFile) throw FileNotFoundException(id)
        return document
    }
}
