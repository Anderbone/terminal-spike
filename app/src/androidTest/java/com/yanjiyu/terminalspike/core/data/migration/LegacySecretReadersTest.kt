package com.yanjiyu.terminalspike.core.data.migration

import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.yanjiyu.terminalspike.settings.SecureSshIdentityStore
import com.yanjiyu.terminalspike.settings.SecureSshPasswordStore
import com.yanjiyu.terminalspike.settings.SshPasswordScope
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySecretReadersTest {
    @Test
    fun readsPasswordAndPrivateKeyWithoutChangingSources() {
        val fixture = fixture()
        val password = "test-password".toByteArray()
        val keyPair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        val privateKey = ByteArrayOutputStream().also(keyPair::writePrivateKey).toByteArray()
        keyPair.dispose()
        try {
            val scope = SshPasswordScope(7, "Mixed.EXAMPLE", 2222, "operator")
            SecureSshPasswordStore(fixture.context, fixture.passwordDirectory, fixture.passwordAlias)
                .save(scope, password)
            SecureSshIdentityStore(fixture.context, fixture.identityDirectory, fixture.identityAlias)
                .import(9, privateKey)
            val passwordFile = fixture.context.filesDir.resolve(fixture.passwordDirectory).resolve("7.password")
            val identityFile = fixture.context.filesDir.resolve(fixture.identityDirectory).resolve("9.identity")
            val passwordSource = passwordFile.readBytes()
            val identitySource = identityFile.readBytes()
            val reader = fixture.reader()

            val loadedPassword = reader.readPassword(scope) as LegacySecretReadResult.Loaded
            val loadedIdentity = reader.readPrivateKey(9) as LegacySecretReadResult.Loaded
            try {
                assertArrayEquals(password, loadedPassword.cleartext)
                assertArrayEquals(privateKey, loadedIdentity.cleartext)
            } finally {
                loadedPassword.cleartext.fill(0)
                loadedIdentity.cleartext.fill(0)
            }
            assertArrayEquals(passwordSource, passwordFile.readBytes())
            assertArrayEquals(identitySource, identityFile.readBytes())
        } finally {
            password.fill(0)
            privateKey.fill(0)
            fixture.clear()
        }
    }

    @Test
    fun missingKeystoreKeyDoesNotCreateAReplacementOrDeleteCiphertext() {
        val fixture = fixture()
        val password = "test-password".toByteArray()
        try {
            val scope = SshPasswordScope(7, "host.example", 22, "operator")
            SecureSshPasswordStore(fixture.context, fixture.passwordDirectory, fixture.passwordAlias)
                .save(scope, password)
            val file = fixture.context.filesDir.resolve(fixture.passwordDirectory).resolve("7.password")
            val before = file.readBytes()
            fixture.keyStore().deleteEntry(fixture.passwordAlias)

            val result = fixture.reader().readPassword(scope)

            assertEquals(
                LegacySecretReadResult.Unavailable(LegacySecretFailure.KEY_UNAVAILABLE),
                result,
            )
            assertArrayEquals(before, file.readBytes())
            assertFalse(fixture.keyStore().containsAlias(fixture.passwordAlias))
        } finally {
            password.fill(0)
            fixture.clear()
        }
    }

    @Test
    fun corruptCiphertextIsPreservedWithTypedFailure() {
        val fixture = fixture()
        val password = "test-password".toByteArray()
        try {
            val scope = SshPasswordScope(7, "host.example", 22, "operator")
            SecureSshPasswordStore(fixture.context, fixture.passwordDirectory, fixture.passwordAlias)
                .save(scope, password)
            val file = fixture.context.filesDir.resolve(fixture.passwordDirectory).resolve("7.password")
            val corrupt = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            file.writeBytes(corrupt)

            val result = fixture.reader().readPassword(scope)

            assertEquals(LegacySecretReadResult.Unavailable(LegacySecretFailure.CORRUPT), result)
            assertArrayEquals(corrupt, file.readBytes())
            assertTrue(fixture.keyStore().containsAlias(fixture.passwordAlias))
        } finally {
            password.fill(0)
            fixture.clear()
        }
    }

    @Test
    fun versionOnePasswordUsesTheExactStoredLegacyHostAsAad() {
        val fixture = fixture()
        val password = "legacy-password".toByteArray()
        try {
            val scope = SshPasswordScope(7, "Mixed.EXAMPLE", 22, "operator")
            val store = SecureSshPasswordStore(
                fixture.context,
                fixture.passwordDirectory,
                fixture.passwordAlias,
            )
            store.save(scope, password) // Creates the test-only Keystore key and target file.
            val file = fixture.context.filesDir.resolve(fixture.passwordDirectory).resolve("7.password")
            writeVersionOnePassword(file, fixture.keyStore(), fixture.passwordAlias, scope, password)

            val loaded = fixture.reader().readPassword(scope) as LegacySecretReadResult.Loaded
            try {
                assertArrayEquals(password, loaded.cleartext)
            } finally {
                loaded.cleartext.fill(0)
            }
            assertEquals(
                LegacySecretReadResult.Unavailable(LegacySecretFailure.CORRUPT),
                fixture.reader().readPassword(scope.copy(host = "mixed.example")),
            )
        } finally {
            password.fill(0)
            fixture.clear()
        }
    }

    private fun writeVersionOnePassword(
        file: java.io.File,
        keyStore: KeyStore,
        keyAlias: String,
        scope: SshPasswordScope,
        password: ByteArray,
    ) {
        val key = keyStore.getKey(keyAlias, null) as SecretKey
        val associatedData = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeUTF("terminal-spike-ssh-password-v1")
                data.writeLong(scope.profileId)
                data.writeUTF(scope.host)
                data.writeInt(scope.port)
                data.writeUTF(scope.username)
            }
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(associatedData)
        }
        val ciphertext = cipher.doFinal(password)
        try {
            DataOutputStream(file.outputStream()).use { data ->
                data.writeInt(0x54535057)
                data.writeInt(1)
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.writeInt(ciphertext.size)
                data.write(ciphertext)
            }
        } finally {
            associatedData.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun fixture(): Fixture {
        val suffix = System.nanoTime().toString()
        return Fixture(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            passwordDirectory = "legacy-password-reader-$suffix",
            passwordAlias = "terminal-spike-legacy-password-$suffix",
            identityDirectory = "legacy-identity-reader-$suffix",
            identityAlias = "terminal-spike-legacy-identity-$suffix",
        )
    }

    private data class Fixture(
        val context: android.content.Context,
        val passwordDirectory: String,
        val passwordAlias: String,
        val identityDirectory: String,
        val identityAlias: String,
    ) {
        fun reader() = LegacySecretReaders(
            context = context,
            passwordDirectoryName = passwordDirectory,
            passwordKeyAlias = passwordAlias,
            identityDirectoryName = identityDirectory,
            identityKeyAlias = identityAlias,
        )

        fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        fun clear() {
            context.filesDir.resolve(passwordDirectory).deleteRecursively()
            context.filesDir.resolve(identityDirectory).deleteRecursively()
            keyStore().deleteEntry(passwordAlias)
            keyStore().deleteEntry(identityAlias)
        }
    }
}
