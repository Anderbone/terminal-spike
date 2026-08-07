package com.yanjiyu.terminalspike

import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.settings.SecureSshPasswordStore
import com.yanjiyu.terminalspike.settings.SshPasswordScope
import java.security.KeyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureSshPasswordStoreTest {
    @Test
    fun encryptsBindsLoadsAndDeletesPassword() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val directoryName = "password-test-$suffix"
        val keyAlias = "terminal-spike-password-test-$suffix"
        val store = SecureSshPasswordStore(context, directoryName, keyAlias)
        val scope = SshPasswordScope(7, "example.com", 22, "operator")
        val password = "correct horse battery staple".toByteArray()

        try {
            store.save(scope, password)
            val encryptedFile = context.filesDir.resolve(directoryName).resolve("7.password")

            assertTrue(encryptedFile.isFile)
            assertFalse(encryptedFile.readText(Charsets.ISO_8859_1).contains("correct horse"))
            val loaded = store.load(scope)
            try {
                assertArrayEquals(password, loaded)
            } finally {
                loaded.fill(0)
            }
            assertTrue(runCatching { store.load(scope.copy(host = "other.example")) }.isFailure)
            store.delete(scope.profileId)
            assertFalse(encryptedFile.exists())
        } finally {
            password.fill(0)
            context.filesDir.resolve(directoryName).deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        }
    }
}
