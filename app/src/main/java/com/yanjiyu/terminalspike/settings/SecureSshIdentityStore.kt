package com.yanjiyu.terminalspike.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SshIdentityInspection(
    val keyType: String,
    val fingerprint: String,
    val passphraseRequired: Boolean,
)

/** Stores imported SSH private-key documents encrypted under an app-specific Keystore key. */
class SecureSshIdentityStore(
    context: Context,
    directoryName: String = DIRECTORY_NAME,
    private val keyAlias: String = KEY_ALIAS,
) {
    private val appContext = context.applicationContext
    private val directory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext.filesDir.resolve(directoryName)
    }

    @Synchronized
    fun import(identityId: Long, privateKey: ByteArray): SshIdentityInspection {
        require(identityId > 0) { "Invalid identity ID." }
        require(privateKey.size in 1..MAX_PRIVATE_KEY_BYTES) { "Private key file is too large." }
        val inspection = inspect(privateKey)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            updateAAD(associatedData(identityId))
        }
        val ciphertext = cipher.doFinal(privateKey)
        directory.mkdirs()
        val file = AtomicFile(fileFor(identityId))
        val stream = file.startWrite()
        try {
            DataOutputStream(stream).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.writeInt(ciphertext.size)
                data.write(ciphertext)
                data.flush()
                file.finishWrite(stream)
            }
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        return inspection
    }

    @Synchronized
    fun load(identityId: Long): ByteArray {
        val file = fileFor(identityId)
        require(file.isFile) { "SSH identity is missing." }
        require(file.length() in 1..MAX_ENCRYPTED_BYTES.toLong()) { "Invalid SSH identity file size." }
        val encrypted = DataInputStream(ByteArrayInputStream(file.readBytes())).use { data ->
            require(data.readInt() == MAGIC) { "Invalid SSH identity header." }
            require(data.readInt() == VERSION) { "Unsupported SSH identity version." }
            val ivLength = data.readInt()
            require(ivLength in 12..32) { "Invalid SSH identity IV." }
            val iv = ByteArray(ivLength).also(data::readFully)
            val ciphertextLength = data.readInt()
            require(ciphertextLength in 16..MAX_ENCRYPTED_BYTES) { "Invalid SSH identity size." }
            val ciphertext = ByteArray(ciphertextLength).also(data::readFully)
            require(data.read() == -1) { "Trailing SSH identity data." }
            EncryptedIdentity(iv, ciphertext)
        }
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, encrypted.iv))
            updateAAD(associatedData(identityId))
            doFinal(encrypted.ciphertext).also { cleartext ->
                require(cleartext.size in 1..MAX_PRIVATE_KEY_BYTES) { "Invalid decrypted SSH identity size." }
            }
        }
    }

    @Synchronized
    fun delete(identityId: Long) {
        AtomicFile(fileFor(identityId)).delete()
    }

    private fun inspect(privateKey: ByteArray): SshIdentityInspection {
        val keyPair = KeyPair.load(JSch(), privateKey, null)
        return try {
            SshIdentityInspection(
                keyType = keyPair.keyTypeString.ifBlank { "SSH key" }.take(MAX_KEY_TYPE_LENGTH),
                fingerprint = keyPair.fingerPrint.take(MAX_FINGERPRINT_LENGTH),
                passphraseRequired = keyPair.isEncrypted,
            )
        } finally {
            keyPair.dispose()
        }
    }

    private fun fileFor(identityId: Long) = directory.resolve("$identityId.identity")

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun associatedData(identityId: Long): ByteArray =
        "terminal-spike-ssh-identity-v1:$identityId".toByteArray(Charsets.UTF_8)

    private data class EncryptedIdentity(val iv: ByteArray, val ciphertext: ByteArray)

    companion object {
        const val MAX_PRIVATE_KEY_BYTES = 256 * 1024
        private const val MAX_ENCRYPTED_BYTES = MAX_PRIVATE_KEY_BYTES + 256
        private const val MAX_KEY_TYPE_LENGTH = 32
        private const val MAX_FINGERPRINT_LENGTH = 128
        private const val DIRECTORY_NAME = "ssh-identities"
        private const val KEY_ALIAS = "terminal_spike_ssh_identities_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val MAGIC = 0x54534931
        private const val VERSION = 1
    }
}
