package com.yanjiyu.terminalspike

import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.yanjiyu.terminalspike.settings.SecureSshIdentityStore
import com.yanjiyu.terminalspike.settings.StoredCredentialKeyUnavailableException
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureSshIdentityStoreTest {
    @Test
    fun validatesEncryptsLoadsAndDeletesPrivateKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val directoryName = "identity-test-$suffix"
        val keyAlias = "terminal-spike-identity-test-$suffix"
        val store = SecureSshIdentityStore(context, directoryName, keyAlias)
        val key = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        val privateKey = ByteArrayOutputStream().also(key::writePrivateKey).toByteArray()
        key.dispose()

        try {
            val inspection = store.import(1, privateKey)
            val encryptedFile = context.filesDir.resolve(directoryName).resolve("1.identity")

            assertTrue(inspection.keyType.contains("RSA", ignoreCase = true))
            assertFalse(inspection.passphraseRequired)
            assertFalse(encryptedFile.readText(Charsets.ISO_8859_1).contains("PRIVATE KEY"))
            val loaded = store.load(1)
            try {
                assertArrayEquals(privateKey, loaded)
            } finally {
                loaded.fill(0)
            }

            val backupFile = encryptedFile.resolveSibling("${encryptedFile.name}.bak")
            assertTrue(encryptedFile.renameTo(backupFile))
            val recovered = store.load(1)
            try {
                assertArrayEquals(privateKey, recovered)
            } finally {
                recovered.fill(0)
            }
            assertTrue(encryptedFile.isFile)
            assertFalse(backupFile.exists())

            store.delete(1)
            assertFalse(encryptedFile.exists())
        } finally {
            privateKey.fill(0)
            context.filesDir.resolve(directoryName).deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        }
    }

    @Test
    fun missingDeviceKeyDoesNotCreateAReplacementOrAlterIdentityCiphertext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val directoryName = "identity-missing-key-test-$suffix"
        val keyAlias = "terminal-spike-identity-missing-key-test-$suffix"
        val store = SecureSshIdentityStore(context, directoryName, keyAlias)
        val key = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        val privateKey = ByteArrayOutputStream().also(key::writePrivateKey).toByteArray()
        key.dispose()

        try {
            store.import(2, privateKey)
            val encryptedFile = context.filesDir.resolve(directoryName).resolve("2.identity")
            val encrypted = encryptedFile.readBytes()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)

            assertThrows(StoredCredentialKeyUnavailableException::class.java) {
                store.load(2)
            }
            assertArrayEquals(encrypted, encryptedFile.readBytes())
            assertFalse(
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(keyAlias),
            )
        } finally {
            privateKey.fill(0)
            context.filesDir.resolve(directoryName).deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        }
    }
}
