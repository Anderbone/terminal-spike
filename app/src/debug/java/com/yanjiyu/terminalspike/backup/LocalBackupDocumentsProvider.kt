package com.yanjiyu.terminalspike.backup

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.yanjiyu.terminalspike.core.backup.BackupDocumentContract
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Debug-only provider proving sensitive document workflows handle opaque ContentResolver streams. */
class LocalBackupDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean {
        storageDirectory().mkdirs()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_DOCUMENT_ID)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
                .add(Root.COLUMN_TITLE, "Terminal Spike test documents")
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE)
                .add(Root.COLUMN_MIME_TYPES, "$BACKUP_MIME_TYPE\n$PRIVATE_KEY_MIME_TYPE")
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
            includeDocument(documentId)
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(parentDocumentId == ROOT_DOCUMENT_ID)
        return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
            storageDirectory().listFiles().orEmpty().sortedBy(File::getName).forEach { file ->
                includeDocument(file.name)
            }
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        require(parentDocumentId == ROOT_DOCUMENT_ID)
        require(mimeType == BACKUP_MIME_TYPE || mimeType == PRIVATE_KEY_MIME_TYPE)
        val safeName = displayName.replace(UNSAFE_NAME_CHARACTERS, "_").take(MAX_FILE_NAME_CHARS)
        val id = "${UUID.randomUUID()}-$safeName"
        val file = documentFile(id)
        check(file.createNewFile()) { "The test backup document could not be created." }
        return id
    }

    override fun deleteDocument(documentId: String) {
        if (!documentFile(documentId).delete()) throw FileNotFoundException(documentId)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = documentFile(documentId)
        if (!file.isFile) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    private fun MatrixCursor.includeDocument(documentId: String) {
        if (documentId == ROOT_DOCUMENT_ID) {
            newRow()
                .add(Document.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
                .add(Document.COLUMN_DISPLAY_NAME, "Backup documents")
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
            return
        }
        val file = documentFile(documentId)
        if (!file.isFile) throw FileNotFoundException(documentId)
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, documentId.substringAfter('-'))
            .add(
                Document.COLUMN_MIME_TYPE,
                if (documentId.endsWith(PRIVATE_KEY_FILE_EXTENSION)) {
                    PRIVATE_KEY_MIME_TYPE
                } else {
                    BACKUP_MIME_TYPE
                },
            )
            .add(Document.COLUMN_SIZE, file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE)
    }

    private fun documentFile(documentId: String): File {
        require(documentId != ROOT_DOCUMENT_ID)
        require(documentId.none { it == '/' || it == '\\' || it.isISOControl() })
        return storageDirectory().resolve(documentId)
    }

    private fun storageDirectory(): File = requireNotNull(context).filesDir.resolve("backup-documents")

    companion object {
        const val AUTHORITY = "com.yanjiyu.terminalspike.test.backup.documents"
        const val ROOT_DOCUMENT_ID = "root"
        const val BACKUP_MIME_TYPE = BackupDocumentContract.MIME_TYPE
        const val PRIVATE_KEY_MIME_TYPE = "application/octet-stream"
        const val PRIVATE_KEY_FILE_EXTENSION = ".key"
        private const val MAX_FILE_NAME_CHARS = 120
        private val UNSAFE_NAME_CHARACTERS = Regex("[^A-Za-z0-9._-]")
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
