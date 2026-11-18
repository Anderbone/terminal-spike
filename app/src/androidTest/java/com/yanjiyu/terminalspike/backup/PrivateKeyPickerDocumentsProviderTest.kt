package com.yanjiyu.terminalspike.backup

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Direct debug fixture contract tests; no picker UI, credentials, or production provider. */
class PrivateKeyPickerDocumentsProviderTest {
    private lateinit var files: File
    private lateinit var directory: File
    private lateinit var provider: PrivateKeyPickerDocumentsProvider

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        files = Files.createTempDirectory(context.cacheDir.toPath(), "key-picker-contract-").toFile()
        directory = files.resolve("key-picker-fixtures").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = files
        }
        provider = PrivateKeyPickerDocumentsProvider().apply {
            attachInfo(isolated, ProviderInfo().apply {
                authority = "com.yanjiyu.terminalspike.fixture.direct"
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            })
        }
        directory.resolve("opaque-key").writeText("fixture bytes only")
    }

    @After fun tearDown() {
        if (::files.isInitialized) files.deleteRecursively()
    }

    @Test fun rootAndDocumentQueriesHonorNarrowReorderedAndUnknownProjections() {
        provider.queryRoots(arrayOf(Root.COLUMN_TITLE)).use {
            assertEquals(listOf(Root.COLUMN_TITLE), it.columnNames.toList())
            assertTrue(it.moveToFirst())
            assertEquals("SSH key picker fixture", it.getString(0))
        }
        provider.queryDocument("opaque-key", arrayOf(Document.COLUMN_SIZE, "unknown", Document.COLUMN_DOCUMENT_ID)).use {
            assertTrue(it.moveToFirst())
            assertEquals("fixture bytes only".length.toLong(), it.getLong(0))
            assertTrue(it.isNull(1))
            assertEquals("opaque-key", it.getString(2))
        }
        provider.queryDocument("root", emptyArray()).use {
            assertEquals(0, it.columnCount)
            assertEquals(1, it.count)
        }
    }

    @Test fun documentTypeMatchesTheAdvertisedMimeAndSupportsTheRoot() {
        directory.resolve("opaque-key.mime").writeText("text/plain\n")
        assertEquals(Document.MIME_TYPE_DIR, provider.getDocumentType("root"))
        assertEquals("text/plain", provider.getDocumentType("opaque-key"))
        provider.queryDocument("opaque-key", arrayOf(Document.COLUMN_MIME_TYPE)).use {
            assertTrue(it.moveToFirst())
            assertEquals(provider.getDocumentType("opaque-key"), it.getString(0))
        }
        directory.resolve("opaque-key.mime").delete()
        assertEquals("application/x-ssh-private-key", provider.getDocumentType("opaque-key"))
    }

    @Test fun invalidAndMissingDocumentIdsFailBeforeMetadataOrFileAccess() {
        for (id in listOf("", ".", "..", "../outside", "a/b", "a\\b", "bad\nname", "missing")) {
            expectMissing { provider.queryDocument(id, arrayOf(Document.COLUMN_MIME_TYPE)).close() }
            expectMissing { provider.getDocumentType(id) }
            expectMissing { provider.openDocument(id, "r", null).close() }
            expectMissing { provider.findDocumentPath(null, id) }
        }
        expectMissing { provider.queryChildDocuments("..", null, sortOrder = null).close() }
    }

    @Test fun childListingOmitsMetadataAndDirectoriesAndOpenReturnsTheFixture() {
        directory.resolve("opaque-key.mime").writeText("application/octet-stream")
        directory.resolve("not-a-document").mkdir()
        provider.queryChildDocuments("root", arrayOf(Document.COLUMN_DOCUMENT_ID), sortOrder = null).use {
            assertEquals(1, it.count)
            assertTrue(it.moveToFirst())
            assertEquals("opaque-key", it.getString(0))
        }
        provider.openDocument("opaque-key", "r", null).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
                assertEquals("fixture bytes only", it.reader().readText())
            }
        }
    }

    private fun expectMissing(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected FileNotFoundException")
        } catch (_: FileNotFoundException) {
            // Expected provider contract.
        }
    }
}
