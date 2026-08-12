package com.yanjiyu.terminalspike.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SshPasswordScope(
    val profileId: Long,
    val host: String,
    val port: Int,
    val username: String,
)

/** Stores opt-in SSH passwords in separate app-private files under an Android Keystore key. */
class SecureSshPasswordStore(
    context: Context,
    directoryName: String = DIRECTORY_NAME,
    private val keyAlias: String = KEY_ALIAS,
) {
    private val appContext = context.applicationContext
    private val directory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext.filesDir.resolve(directoryName)
    }

    @Synchronized
    fun save(scope: SshPasswordScope, password: ByteArray) {
        validate(scope)
        require(password.size in 1..MAX_PASSWORD_BYTES) { "Invalid SSH password size." }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKeyForWrite())
            updateAAD(canonicalAssociatedData(scope))
        }
        val ciphertext = cipher.doFinal(password)
        require(directory.isDirectory || directory.mkdirs()) { "Could not create credential storage." }
        val file = AtomicFile(fileFor(scope.profileId))
        val stream = file.startWrite()
        try {
            val data = DataOutputStream(stream)
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(cipher.iv.size)
            data.write(cipher.iv)
            data.writeInt(ciphertext.size)
            data.write(ciphertext)
            data.flush()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun load(scope: SshPasswordScope): ByteArray {
        validate(scope)
        val file = AtomicFile(fileFor(scope.profileId))
        require(file.baseFile.isFile) { "Saved SSH password is missing." }
        require(file.baseFile.length() in 1..MAX_ENCRYPTED_BYTES.toLong()) {
            "Invalid saved SSH password file size."
        }
        val encrypted = file.openRead().use { input ->
            DataInputStream(input).use { data ->
                require(data.readInt() == MAGIC) { "Invalid saved SSH password header." }
                val version = data.readInt()
                require(version in LEGACY_VERSION..VERSION) { "Unsupported saved SSH password version." }
                val ivLength = data.readInt()
                require(ivLength in 12..32) { "Invalid saved SSH password IV." }
                val iv = ByteArray(ivLength).also(data::readFully)
                val ciphertextLength = data.readInt()
                require(ciphertextLength in 16..MAX_ENCRYPTED_BYTES) { "Invalid saved SSH password size." }
                val ciphertext = ByteArray(ciphertextLength).also(data::readFully)
                require(data.read() == -1) { "Trailing saved SSH password data." }
                EncryptedPassword(version, iv, ciphertext)
            }
        }
        val cleartext = decrypt(
            encrypted = encrypted,
            associatedData = if (encrypted.version == LEGACY_VERSION) {
                legacyAssociatedData(scope)
            } else {
                canonicalAssociatedData(scope)
            },
        )
        if (encrypted.version == LEGACY_VERSION) {
            // Keep a readable v1 file if a best-effort migration cannot be committed atomically.
            runCatching { save(scope, cleartext) }
        }
        return cleartext
    }

    private fun decrypt(encrypted: EncryptedPassword, associatedData: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, getExistingKeyForRead(), GCMParameterSpec(TAG_BITS, encrypted.iv))
            updateAAD(associatedData)
            doFinal(encrypted.ciphertext).also { cleartext ->
                require(cleartext.size in 1..MAX_PASSWORD_BYTES) { "Invalid decrypted SSH password size." }
            }
        }

    @Synchronized
    fun delete(profileId: Long) {
        require(profileId > 0) { "Invalid profile ID." }
        AtomicFile(fileFor(profileId)).delete()
    }

    @Synchronized
    fun clearAllAfterRecoveryConfirmation() {
        directory.deleteRecursively()
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.run {
            if (containsAlias(keyAlias)) deleteEntry(keyAlias)
        }
    }

    private fun fileFor(profileId: Long) = directory.resolve("$profileId.password")

    private fun canonicalAssociatedData(scope: SshPasswordScope): ByteArray = associatedData(
        format = ASSOCIATED_DATA_FORMAT,
        scope = scope,
        host = scope.host.lowercase(Locale.ROOT),
    )

    private fun legacyAssociatedData(scope: SshPasswordScope): ByteArray = associatedData(
        format = LEGACY_ASSOCIATED_DATA_FORMAT,
        scope = scope,
        host = scope.host,
    )

    private fun associatedData(
        format: String,
        scope: SshPasswordScope,
        host: String,
    ): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            data.writeUTF(format)
            data.writeLong(scope.profileId)
            data.writeUTF(host)
            data.writeInt(scope.port)
            data.writeUTF(scope.username)
        }
    }.toByteArray()

    private fun validate(scope: SshPasswordScope) {
        require(scope.profileId > 0) { "Invalid profile ID." }
        require(scope.host.length in 1..UserSettings.MAX_HOST_LENGTH && scope.host.none(Char::isWhitespace)) {
            "Invalid saved host."
        }
        require(scope.port in 1..65_535) { "Invalid SSH port." }
        require(
            scope.username.length in 1..UserSettings.MAX_USERNAME_LENGTH &&
                scope.username.none { it.isWhitespace() || it.isISOControl() },
        ) { "Invalid saved username." }
    }

    private fun getExistingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) return null
        return keyStore.getKey(keyAlias, null) as? SecretKey
            ?: throw StoredCredentialKeyUnavailableException(StoredCredentialKind.SSH_PASSWORD)
    }

    private fun getExistingKeyForRead(): SecretKey =
        getExistingKey()
            ?: throw StoredCredentialKeyUnavailableException(StoredCredentialKind.SSH_PASSWORD)

    private fun getOrCreateKeyForWrite(): SecretKey {
        getExistingKey()?.let { return it }
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

    private data class EncryptedPassword(
        val version: Int,
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    companion object {
        const val MAX_PASSWORD_BYTES = 4 * 1024
        private const val MAX_ENCRYPTED_BYTES = MAX_PASSWORD_BYTES + 256
        private const val DIRECTORY_NAME = "ssh-passwords"
        private const val KEY_ALIAS = "terminal_spike_ssh_passwords_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val MAGIC = 0x54535057
        private const val LEGACY_VERSION = 1
        private const val VERSION = 2
        private const val LEGACY_ASSOCIATED_DATA_FORMAT = "terminal-spike-ssh-password-v1"
        private const val ASSOCIATED_DATA_FORMAT = "terminal-spike-ssh-password-v2"
    }
}
