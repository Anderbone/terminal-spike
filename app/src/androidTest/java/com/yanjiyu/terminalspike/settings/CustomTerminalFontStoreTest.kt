package com.yanjiyu.terminalspike.settings

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.backup.BackupCustomFont
import com.yanjiyu.terminalspike.core.backup.BackupPayloadFormat
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomTerminalFontStoreTest {
    private val directories = mutableListOf<File>()

    @After
    fun removeTestFiles() {
        directories.forEach(File::deleteRecursively)
    }

    @Test
    fun fixedGridValidationAcceptsMonospaceAndRejectsProportionalTypefaces() {
        assertTrue(isMonospaceTerminalTypeface(Typeface.MONOSPACE))
        assertFalse(isMonospaceTerminalTypeface(Typeface.SANS_SERIF))
    }

    @Test
    fun preparedRestoreRollsBackAndCrashJournalReconcilesAgainstAuthoritativeReferences() {
        val context = isolatedContext()
        val bytes = context.resources
            .openRawResource(R.font.source_code_pro_regular)
            .use { it.readBytes() }
        val id = "custom_" + MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        val record = BackupCustomFont.copyOf(id, "Portable Source Code Pro", bytes)
        val store = CustomTerminalFontStore(context)

        store.prepare(listOf(record)).rollback()
        assertTrue(store.list().isEmpty())

        store.prepare(listOf(record))
        CustomTerminalFontStore(context).reconcilePending(emptySet())
        assertTrue(store.list().isEmpty())

        store.prepare(listOf(record))
        CustomTerminalFontStore(context).reconcilePending(setOf(id))
        assertEquals(id, store.list().single().id)
    }

    @Test
    fun backupFontCountIsCheckedBeforeAnyFileContentsAreRead() {
        val context = isolatedContext()
        val store = CustomTerminalFontStore(context)
        val countFailure = assertThrows(IllegalArgumentException::class.java) {
            store.readForBackup(fontIds(BackupPayloadFormat.MAX_CUSTOM_FONTS + 1))
        }
        assertEquals("Too many custom fonts for a backup.", countFailure.message)
    }

    @Test
    fun backupFontBytesAreCheckedBeforeAnyFileContentsAreRead() {
        val context = isolatedContext()
        val store = CustomTerminalFontStore(context)
        val directory = context.filesDir.resolve("terminal-fonts")
        check(directory.mkdir())
        val count = (BackupPayloadFormat.MAX_PAYLOAD_BYTES / BackupPayloadFormat.MAX_CUSTOM_FONT_BYTES).toInt() + 1
        val oversizedIds = fontIds(count)
        oversizedIds.forEach { id ->
            // Sparse, deliberately invalid fonts prove the aggregate cap precedes validation/readBytes.
            RandomAccessFile(directory.resolve("$id--Rm9udA.font"), "rw").use {
                it.setLength(BackupPayloadFormat.MAX_CUSTOM_FONT_BYTES.toLong())
            }
        }
        val sizeFailure = assertThrows(IllegalArgumentException::class.java) {
            store.readForBackup(oversizedIds)
        }
        assertEquals("Custom font files exceed the backup payload limit.", sizeFailure.message)
    }

    @Test
    fun unicodeRestoreNamesFitTheFilesystemAndRetainCompleteCharacters() {
        val context = isolatedContext()
        val bytes = context.resources.openRawResource(R.font.source_code_pro_regular)
            .use { it.readBytes() }
        val id = "custom_" + MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        // Three- and four-byte UTF-8 code points cross different filename cut positions.
        val names = listOf(
            "字".repeat(80),
            "😀".repeat(40),
            "a" + "字".repeat(79),
            "a" + "😀".repeat(39),
            "字".repeat(43) + "   " + "字".repeat(20),
        )
        for (name in names) {
            val store = CustomTerminalFontStore(context)
            val record = BackupCustomFont.copyOf(id, name, bytes)
            val pending = store.prepare(listOf(record))
            val imported = store.list().single()
            assertEquals(id, imported.id)
            assertTrue(name.startsWith(imported.displayName))
            assertFalse(imported.displayName.contains('\uFFFD'))
            assertTrue(imported.file.name.encodeToByteArray().size <= 255)
            assertEquals(imported.displayName, store.readForBackup(setOf(id)).single().displayName)
            pending.rollback()
            assertTrue(store.list().isEmpty())
        }
    }

    private fun fontIds(count: Int): Set<String> = (0 until count)
        .map { "custom_" + it.toString(16).padStart(64, '0') }.toSet()

    private fun isolatedContext(): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File.createTempFile("font-store-test-", "", target.cacheDir)
        check(directory.delete() && directory.mkdir())
        directories += directory
        return object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
    }
}

private fun ByteArray.hex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
