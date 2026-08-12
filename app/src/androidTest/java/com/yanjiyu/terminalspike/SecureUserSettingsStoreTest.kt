package com.yanjiyu.terminalspike

import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.SavedSshIdentity
import com.yanjiyu.terminalspike.settings.SecureUserSettingsStore
import com.yanjiyu.terminalspike.settings.SettingsRecoveryRequiredException
import com.yanjiyu.terminalspike.settings.SettingsLoadFailure
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import java.security.KeyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
            assertEquals(SettingsLoadFailure.CORRUPT_OR_UNSUPPORTED, recovery.failure)
            assertTrue(file.exists())
            assertArrayEquals(corrupted, file.readBytes())
            assertTrue(
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(keyAlias),
            )
            try {
                store.save(settings)
                fail("Saving must not overwrite encrypted data that still needs recovery")
            } catch (_: SettingsRecoveryRequiredException) {
                // Expected: only an explicit recovery/reset flow may replace preserved data.
            }

            store.resetAfterRecoveryConfirmation()
            assertFalse(file.exists())
            assertFalse(
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(keyAlias),
            )
            store.save(settings)
            assertEquals(settings, store.load().settings)
        } finally {
            file.delete()
            file.resolveSibling("$fileName.bak").delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        }
    }

    @Test
    fun missingKeyIsReportedWithoutCreatingAReplacementOrChangingCiphertext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val fileName = "settings-missing-key-test-$suffix.bin"
        val keyAlias = "terminal-spike-settings-missing-key-test-$suffix"
        val file = context.filesDir.resolve(fileName)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val settings = UserSettings(
            profiles = listOf(SavedSshProfile(1, "Test host", "private.example", 22, "tester")),
        )

        try {
            val store = SecureUserSettingsStore(context, fileName, keyAlias)
            store.save(settings)
            val encrypted = file.readBytes()
            keyStore.deleteEntry(keyAlias)

            val recovery = store.load()

            assertEquals(SettingsLoadFailure.KEY_UNAVAILABLE, recovery.failure)
            assertArrayEquals(encrypted, file.readBytes())
            assertFalse(keyStore.containsAlias(keyAlias))
        } finally {
            file.delete()
            file.resolveSibling("$fileName.bak").delete()
            keyStore.deleteEntry(keyAlias)
        }
    }
}
