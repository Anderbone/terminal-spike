package com.yanjiyu.terminalspike.backup

import android.Manifest
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.core.backup.BackupDocumentContract
import com.yanjiyu.terminalspike.core.backup.BackupMode
import com.yanjiyu.terminalspike.ui.settings.AndroidSettingsBackupGateway
import com.yanjiyu.terminalspike.ui.settings.BackupDocumentReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupDocumentsProviderIntegrationTest {
    @Test
    fun standardAndFullArchivesRoundTripThroughOpaqueProviderStreams() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as TerminalSpikeApplication
        val gateway = AndroidSettingsBackupGateway(
            context = instrumentation.targetContext,
            transfers = application.container.backupTransfers,
            imports = application.container.backupImports,
        )
        val resolver = instrumentation.context.contentResolver
        val root = DocumentsContract.buildDocumentUri(
            LocalBackupDocumentsProvider.AUTHORITY,
            LocalBackupDocumentsProvider.ROOT_DOCUMENT_ID,
        )

        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MANAGE_DOCUMENTS)
        try {
            listOf(
                BackupMode.STANDARD to "provider-standard-passphrase",
                BackupMode.FULL to "provider-full-passphrase",
            ).forEachIndexed { index, (mode, passphraseText) ->
                val uri = requireNotNull(
                    DocumentsContract.createDocument(
                        resolver,
                        root,
                        BackupDocumentContract.MIME_TYPE,
                        "provider-$index${BackupDocumentContract.FILE_EXTENSION}",
                    ),
                )
                val reference = BackupDocumentReference(uri.toString())
                val exportPassphrase = passphraseText.toCharArray()
                try {
                    val exported = gateway.exportWithOptions(
                        destination = reference,
                        mode = mode,
                        createdAtEpochMillis = CREATED_AT + index,
                        includeCustomFonts = false,
                        passphrase = exportPassphrase,
                    )
                    assertEquals(mode, exported.mode)
                    assertTrue(exported.bytesWritten > 0L)
                } finally {
                    exportPassphrase.fill('\u0000')
                }

                val header = gateway.inspect(reference)
                assertEquals(mode, header.mode)
                val importPassphrase = passphraseText.toCharArray()
                try {
                    gateway.unlock(reference, importPassphrase).use { unlocked ->
                        assertEquals(mode, unlocked.preview.header.mode)
                        assertTrue(unlocked.preview.content.terminalProfiles >= 1)
                        assertTrue(unlocked.preview.content.keyboardProfiles >= 1)
                    }
                } finally {
                    importPassphrase.fill('\u0000')
                }
                DocumentsContract.deleteDocument(resolver, uri)
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private companion object {
        const val CREATED_AT = 1_725_000_000_000L
    }
}
