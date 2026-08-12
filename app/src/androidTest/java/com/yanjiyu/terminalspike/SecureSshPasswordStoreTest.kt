package com.yanjiyu.terminalspike

import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.settings.SecureSshPasswordStore
import com.yanjiyu.terminalspike.settings.SshPasswordScope
import com.yanjiyu.terminalspike.settings.StoredCredentialKeyUnavailableException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

    @Test
    fun canonicalHostnameScopeSurvivesCaseOnlyEdit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val directoryName = "password-case-test-$suffix"
        val keyAlias = "terminal-spike-password-case-test-$suffix"
        val store = SecureSshPasswordStore(context, directoryName, keyAlias)
        val savedScope = SshPasswordScope(9, "MixedCase.EXAMPLE", 22, "operator")
        val password = "case insensitive host".toByteArray()

        try {
            store.save(savedScope, password)

            val loaded = store.load(savedScope.copy(host = "mixedcase.example"))
            try {
                assertArrayEquals(password, loaded)
            } finally {
                loaded.fill(0)
            }
        } finally {
            password.fill(0)
            context.filesDir.resolve(directoryName).deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        }
    }

    @Test
    fun loadsAndMigratesLegacyExactHostnameScope() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val directoryName = "password-legacy-test-$suffix"
        val keyAlias = "terminal-spike-password-legacy-test-$suffix"
        val store = SecureSshPasswordStore(context, directoryName, keyAlias)
        val legacyScope = SshPasswordScope(11, "LegacyCase.EXAMPLE", 22, "operator")
        val password = "legacy password".toByteArray()

        try {
            store.save(legacyScope, password)
            val encryptedFile = context.filesDir.resolve(directoryName).resolve("11.password")
            writeLegacyPassword(encryptedFile, keyAlias, legacyScope, password)

            store.load(legacyScope).also { loaded ->
                try {
                    assertArrayEquals(password, loaded)
                } finally {
                    loaded.fill(0)
                }
            }
            store.load(legacyScope.copy(host = "legacycase.example")).also { migrated ->
                try {
                    assertArrayEquals(password, migrated)
                } finally {
                    migrated.fill(0)
                }
            }
        } finally {
            password.fill(0)
            context.filesDir.resolve(directoryName).deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        }
    }

    @Test
    fun missingDeviceKeyDoesNotCreateAReplacementOrAlterPasswordCiphertext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val directoryName = "password-missing-key-test-$suffix"
        val keyAlias = "terminal-spike-password-missing-key-test-$suffix"
        val store = SecureSshPasswordStore(context, directoryName, keyAlias)
        val scope = SshPasswordScope(13, "example.com", 22, "operator")
        val password = "still encrypted".toByteArray()

        try {
            store.save(scope, password)
            val encryptedFile = context.filesDir.resolve(directoryName).resolve("13.password")
            val encrypted = encryptedFile.readBytes()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)

            assertThrows(StoredCredentialKeyUnavailableException::class.java) {
                store.load(scope)
            }
            assertArrayEquals(encrypted, encryptedFile.readBytes())
            assertFalse(
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(keyAlias),
            )
        } finally {
            password.fill(0)
            context.filesDir.resolve(directoryName).deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        }
    }

    private fun writeLegacyPassword(
        file: java.io.File,
        keyAlias: String,
        scope: SshPasswordScope,
        password: ByteArray,
    ) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = requireNotNull(keyStore.getKey(keyAlias, null) as? SecretKey)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(legacyAssociatedData(scope))
        }
        val ciphertext = cipher.doFinal(password)
        DataOutputStream(file.outputStream()).use { data ->
            data.writeInt(0x54535057)
            data.writeInt(1)
            data.writeInt(cipher.iv.size)
            data.write(cipher.iv)
            data.writeInt(ciphertext.size)
            data.write(ciphertext)
        }
    }

    private fun legacyAssociatedData(scope: SshPasswordScope): ByteArray =
        ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeUTF("terminal-spike-ssh-password-v1")
                data.writeLong(scope.profileId)
                data.writeUTF(scope.host)
                data.writeInt(scope.port)
                data.writeUTF(scope.username)
            }
        }.toByteArray()
}
