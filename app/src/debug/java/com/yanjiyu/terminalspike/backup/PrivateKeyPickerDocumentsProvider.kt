package com.yanjiyu.terminalspike.backup

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** Enabled only for the picker regression; supplies an extensionless opaque document. */
class PrivateKeyPickerDocumentsProvider : DocumentsProvider() {
    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES)).apply {
            newRow().add(Root.COLUMN_ROOT_ID, "root").add(Root.COLUMN_DOCUMENT_ID, "root")
                .add(Root.COLUMN_TITLE, "SSH key picker fixture").add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_MIME_TYPES, "*/*")
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        cursor(projection).apply { include(documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        require(parentDocumentId == "root")
        return cursor(projection).apply {
            directory().listFiles().orEmpty().forEach { include(it.name) }
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

    private fun MatrixCursor.include(id: String) {
        newRow().add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, if (id == "root") "SSH key picker fixture" else id)
            .add(Document.COLUMN_MIME_TYPE, if (id == "root") Document.MIME_TYPE_DIR else "application/x-ssh-private-key")
            .add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, if (id == "root") 0L else file(id).length())
    }

    private fun directory() = requireNotNull(context).filesDir.resolve("key-picker-fixtures")
    private fun file(id: String): File {
        require(id != "root" && id.isNotEmpty() && id.none { it == '/' || it == '\\' || it.isISOControl() })
        return directory().resolve(id)
    }
}
