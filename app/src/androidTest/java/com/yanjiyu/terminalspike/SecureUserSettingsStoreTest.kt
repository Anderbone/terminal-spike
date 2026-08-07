package com.yanjiyu.terminalspike

import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.SavedSshIdentity
import com.yanjiyu.terminalspike.settings.SecureUserSettingsStore
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import java.security.KeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class SecureUserSettingsStoreTest {
    @Test
    fun encryptsAndRoundTripsLocalSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val fileName = "settings-test-$suffix.bin"
        val keyAlias = "terminal-spike-settings-test-$suffix"
        val file = context.filesDir.resolve(fileName)
        val settings = UserSettings(
            profiles = listOf(SavedSshProfile(1, "Test host", "private.example", 22, "tester")),
            snippets = listOf(CommandSnippet(2, "List", "ls -la", true)),
            extraKeys = listOf(TerminalExtraKey.ESC, TerminalExtraKey.CTRL),
            identities = listOf(
                SavedSshIdentity(3, "Test identity", "RSA", "SHA256:test", true),
            ),
        )

        try {
            val store = SecureUserSettingsStore(context, fileName, keyAlias)
            store.save(settings)

            assertFalse(file.readText(Charsets.ISO_8859_1).contains("private.example"))
            assertEquals(settings, store.load().settings)

            val corrupted = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            file.writeBytes(corrupted)
            val recovery = store.load()
            assertNotNull(recovery.warning)
            assertEquals(UserSettings(), recovery.settings)
            assertFalse(file.exists())
        } finally {
            file.delete()
            file.resolveSibling("$fileName.bak").delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        }
    }
}
