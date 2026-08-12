package com.yanjiyu.terminalspike.settings

import android.graphics.Typeface
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.backup.BackupCustomFont
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomTerminalFontStoreTest {
    @Test
    fun fixedGridValidationAcceptsMonospaceAndRejectsProportionalTypefaces() {
        assertTrue(isMonospaceTerminalTypeface(Typeface.MONOSPACE))
        assertFalse(isMonospaceTerminalTypeface(Typeface.SANS_SERIF))
    }

    @Test
    fun preparedRestoreRollsBackAndCrashJournalReconcilesAgainstAuthoritativeReferences() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = context.filesDir.resolve("terminal-fonts")
        directory.deleteRecursively()
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

        directory.deleteRecursively()
    }
}

private fun ByteArray.hex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
