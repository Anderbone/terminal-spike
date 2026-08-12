package com.yanjiyu.terminalspike.core.security.credential

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Non-exportable AES-256 key provider for device-bound credential envelopes.
 *
 * Encryption may deliberately create the v2 key. Decryption is existing-key-only so loss or
 * invalidation is surfaced as [CredentialStoreException.KeyUnavailable] by the store instead of
 * silently creating a replacement that can never authenticate the existing ciphertext.
 */
class AndroidKeystoreCredentialKeyProvider(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : CredentialKeyProvider {
    init {
        require(keyAlias.length in 1..MAX_ALIAS_LENGTH && keyAlias.none(Char::isISOControl)) {
            "Invalid Android Keystore alias."
        }
    }

    @Synchronized
    override fun getOrCreateEncryptionKey(keyVersion: Int): SecretKey {
        requireV2(keyVersion)
        val keyStore = loadKeyStore()
        existingKey(keyStore)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    @Synchronized
    override fun getExistingDecryptionKey(keyVersion: Int): SecretKey? {
        requireV2(keyVersion)
        return existingKey(loadKeyStore())
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun existingKey(keyStore: KeyStore): SecretKey? {
        if (!keyStore.containsAlias(keyAlias)) return null
        val key = keyStore.getKey(keyAlias, null) as? SecretKey
            ?: error("Android Keystore alias is not an AES secret key.")
        check(key.algorithm.equals(KeyProperties.KEY_ALGORITHM_AES, ignoreCase = true)) {
            "Android Keystore alias is not an AES secret key."
        }
        check(key.encoded == null) { "Android Keystore credential key must be non-exportable." }
        val keyInfo = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE_PROVIDER)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        check(keyInfo.keySize == AES_KEY_BITS) {
            "Android Keystore credential key must be AES-256."
        }
        check(
            keyInfo.purposes and
                (KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT) ==
                (KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT),
        ) { "Android Keystore credential key has incompatible purposes." }
        check(keyInfo.blockModes.contains(KeyProperties.BLOCK_MODE_GCM)) {
            "Android Keystore credential key does not permit GCM."
        }
        check(keyInfo.encryptionPaddings.contains(KeyProperties.ENCRYPTION_PADDING_NONE)) {
            "Android Keystore credential key has incompatible padding."
        }
        return key
    }

    private fun requireV2(keyVersion: Int) {
        require(keyVersion == AesGcmCredentialStore.KEY_VERSION) {
            "Unsupported credential key version."
        }
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "terminal_spike_credential_store_aes_256_gcm_v2"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val MAX_ALIAS_LENGTH = 128
        private const val AES_KEY_BITS = 256
    }
}
