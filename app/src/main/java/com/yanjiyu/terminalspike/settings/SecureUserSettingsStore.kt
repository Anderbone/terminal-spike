package com.yanjiyu.terminalspike.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SettingsLoadResult(
    val settings: UserSettings,
    val warning: String? = null,
    val failure: SettingsLoadFailure? = null,
)

enum class SettingsLoadFailure {
    KEY_UNAVAILABLE,
    CORRUPT_OR_UNSUPPORTED,
    /** Authoritative Room or Proto DataStore data could not be read and was preserved in place. */
    APP_DATA_UNAVAILABLE,
}

class SettingsRecoveryRequiredException : IllegalStateException(
    "Encrypted local data is unavailable; recover or explicitly reset it before saving.",
)

class SecureUserSettingsStore(
    context: Context,
    fileName: String = SETTINGS_FILE,
    private val keyAlias: String = KEY_ALIAS,
) {
    private val appContext = context.applicationContext
    private val file by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AtomicFile(appContext.filesDir.resolve(fileName))
    }
    private var recoveryRequired = false

    @Synchronized
    fun load(): SettingsLoadResult {
        val backupFile = file.baseFile.resolveSibling("${file.baseFile.name}.bak")
        if (!file.baseFile.isFile && !backupFile.isFile) {
            recoveryRequired = false
            return SettingsLoadResult(UserSettings())
        }
        return try {
            val encrypted = file.openRead().use(::readEnvelope)
            val key = getExistingKey() ?: return unavailable(SettingsLoadFailure.KEY_UNAVAILABLE)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, encrypted.iv))
                updateAAD(ASSOCIATED_DATA)
            }
            val cleartext = cipher.doFinal(encrypted.ciphertext)
            val settings = try {
                UserSettingsCodec.read(ByteArrayInputStream(cleartext))
            } finally {
                cleartext.fill(0)
            }
            recoveryRequired = false
            SettingsLoadResult(settings)
        } catch (_: AEADBadTagException) {
            unavailable(SettingsLoadFailure.CORRUPT_OR_UNSUPPORTED)
        } catch (_: BadPaddingException) {
            unavailable(SettingsLoadFailure.CORRUPT_OR_UNSUPPORTED)
        } catch (_: IllegalBlockSizeException) {
            unavailable(SettingsLoadFailure.CORRUPT_OR_UNSUPPORTED)
        } catch (_: GeneralSecurityException) {
            unavailable(SettingsLoadFailure.KEY_UNAVAILABLE)
        } catch (_: Exception) {
            unavailable(SettingsLoadFailure.CORRUPT_OR_UNSUPPORTED)
        }
    }

    @Synchronized
    fun save(settings: UserSettings) {
        if (recoveryRequired) throw SettingsRecoveryRequiredException()
        val cleartext = ByteArrayOutputStream().also { UserSettingsCodec.write(settings, it) }.toByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKeyForWrite())
            updateAAD(ASSOCIATED_DATA)
        }
        val ciphertext = try {
            cipher.doFinal(cleartext)
        } finally {
            cleartext.fill(0)
        }
        val stream = file.startWrite()
        try {
            val data = DataOutputStream(stream)
            data.writeInt(ENVELOPE_MAGIC)
            data.writeInt(ENVELOPE_VERSION)
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

    /**
     * Irreversibly discards preserved settings only after the UI has obtained explicit user
     * confirmation. Ordinary load/save paths never call this method and never replace ciphertext
     * while recovery is required.
     */
    @Synchronized
    fun resetAfterRecoveryConfirmation() {
        check(recoveryRequired) { "No encrypted settings recovery is pending." }
        file.delete()
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.run {
            if (containsAlias(keyAlias)) deleteEntry(keyAlias)
        }
        recoveryRequired = false
    }

    private fun readEnvelope(input: java.io.InputStream): EncryptedEnvelope = DataInputStream(input).use { data ->
        require(data.readInt() == ENVELOPE_MAGIC) { "Invalid encrypted settings header." }
        require(data.readInt() == ENVELOPE_VERSION) { "Unsupported encrypted settings version." }
        val ivLength = data.readInt()
        require(ivLength in 12..32) { "Invalid encrypted settings IV." }
        val iv = ByteArray(ivLength).also(data::readFully)
        val ciphertextLength = data.readInt()
        require(ciphertextLength in 16..MAX_ENCRYPTED_BYTES) { "Invalid encrypted settings size." }
        val ciphertext = ByteArray(ciphertextLength).also(data::readFully)
        require(data.read() == -1) { "Trailing encrypted settings data." }
        EncryptedEnvelope(iv, ciphertext)
    }

    private fun getExistingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

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

    private fun unavailable(failure: SettingsLoadFailure): SettingsLoadResult {
        recoveryRequired = true
        return SettingsLoadResult(
            settings = UserSettings(),
            warning = "Saved connections and settings could not be unlocked. The encrypted data was preserved for recovery.",
            failure = failure,
        )
    }

    private data class EncryptedEnvelope(val iv: ByteArray, val ciphertext: ByteArray)

    companion object {
        private const val SETTINGS_FILE = "secure_user_settings.bin"
        private const val KEY_ALIAS = "terminal_spike_user_settings_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val ENVELOPE_MAGIC = 0x54534531
        private const val ENVELOPE_VERSION = 1
        private const val MAX_ENCRYPTED_BYTES = 1024 * 1024
        private val ASSOCIATED_DATA = "terminal-spike-user-settings-v1".toByteArray(Charsets.UTF_8)
    }
}
