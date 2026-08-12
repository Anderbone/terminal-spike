package com.yanjiyu.terminalspike.core.data.migration

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.AtomicFile
import com.yanjiyu.terminalspike.settings.SecureSshIdentityStore
import com.yanjiyu.terminalspike.settings.SecureSshPasswordStore
import com.yanjiyu.terminalspike.settings.SshPasswordScope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.util.Locale
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal sealed interface LegacySecretReadResult {
    data class Loaded(val cleartext: ByteArray) : LegacySecretReadResult
    data class Unavailable(val failure: LegacySecretFailure) : LegacySecretReadResult
}

internal enum class LegacySecretFailure {
    MISSING,
    KEY_UNAVAILABLE,
    CORRUPT,
    UNSUPPORTED_VERSION,
    IO_UNAVAILABLE,
}

/** Existing-key-only password/private-key reader for non-destructive legacy migration. */
internal class LegacySecretReaders(
    context: Context,
    passwordDirectoryName: String = PASSWORD_DIRECTORY,
    private val passwordKeyAlias: String = PASSWORD_KEY_ALIAS,
    identityDirectoryName: String = IDENTITY_DIRECTORY,
    private val identityKeyAlias: String = IDENTITY_KEY_ALIAS,
) {
    private val filesDirectory = context.applicationContext.filesDir
    private val passwordDirectory = filesDirectory.resolve(passwordDirectoryName)
    private val identityDirectory = filesDirectory.resolve(identityDirectoryName)

    fun readPassword(scope: SshPasswordScope): LegacySecretReadResult {
        require(scope.profileId > 0)
        return readEncrypted(
            file = AtomicFile(passwordDirectory.resolve("${scope.profileId}.password")),
            keyAlias = passwordKeyAlias,
            maximumEncryptedBytes = SecureSshPasswordStore.MAX_PASSWORD_BYTES + ENVELOPE_OVERHEAD_BYTES,
        ) { source, key ->
            val envelope = readEnvelope(
                source = source,
                expectedMagic = PASSWORD_MAGIC,
                supportedVersions = PASSWORD_VERSION_V1..PASSWORD_VERSION_V2,
                maximumCiphertextBytes = SecureSshPasswordStore.MAX_PASSWORD_BYTES + GCM_TAG_BYTES,
            )
            val associatedData = passwordAssociatedData(scope, envelope.version)
            try {
                decrypt(
                    key = key,
                    iv = envelope.iv,
                    ciphertext = envelope.ciphertext,
                    associatedData = associatedData,
                    maximumPlaintextBytes = SecureSshPasswordStore.MAX_PASSWORD_BYTES,
                )
            } finally {
                associatedData.fill(0)
                envelope.iv.fill(0)
                envelope.ciphertext.fill(0)
            }
        }
    }

    fun readPrivateKey(identityId: Long): LegacySecretReadResult {
        require(identityId > 0)
        return readEncrypted(
            file = AtomicFile(identityDirectory.resolve("$identityId.identity")),
            keyAlias = identityKeyAlias,
            maximumEncryptedBytes = SecureSshIdentityStore.MAX_PRIVATE_KEY_BYTES + ENVELOPE_OVERHEAD_BYTES,
        ) { source, key ->
            val envelope = readEnvelope(
                source = source,
                expectedMagic = IDENTITY_MAGIC,
                supportedVersions = IDENTITY_VERSION..IDENTITY_VERSION,
                maximumCiphertextBytes = SecureSshIdentityStore.MAX_PRIVATE_KEY_BYTES + GCM_TAG_BYTES,
            )
            val associatedData = "terminal-spike-ssh-identity-v1:$identityId".toByteArray()
            try {
                decrypt(
                    key = key,
                    iv = envelope.iv,
                    ciphertext = envelope.ciphertext,
                    associatedData = associatedData,
                    maximumPlaintextBytes = SecureSshIdentityStore.MAX_PRIVATE_KEY_BYTES,
                )
            } finally {
                associatedData.fill(0)
                envelope.iv.fill(0)
                envelope.ciphertext.fill(0)
            }
        }
    }

    private inline fun readEncrypted(
        file: AtomicFile,
        keyAlias: String,
        maximumEncryptedBytes: Int,
        decryptSource: (ByteArray, SecretKey) -> ByteArray,
    ): LegacySecretReadResult {
        val backup = file.baseFile.resolveSibling("${file.baseFile.name}.bak")
        if (!file.baseFile.isFile && !backup.isFile) {
            return LegacySecretReadResult.Unavailable(LegacySecretFailure.MISSING)
        }
        val source = try {
            file.openRead().use { input ->
                val size = input.channel.size()
                require(size in 1..maximumEncryptedBytes.toLong())
                input.readBytes().also { bytes -> require(bytes.size.toLong() == size) }
            }
        } catch (_: FileNotFoundException) {
            return LegacySecretReadResult.Unavailable(LegacySecretFailure.MISSING)
        } catch (_: IOException) {
            return LegacySecretReadResult.Unavailable(LegacySecretFailure.IO_UNAVAILABLE)
        } catch (_: IllegalArgumentException) {
            return LegacySecretReadResult.Unavailable(LegacySecretFailure.CORRUPT)
        }
        val key = try {
            existingKey(keyAlias)
        } catch (_: GeneralSecurityException) {
            null
        }
        if (key == null) {
            source.fill(0)
            return LegacySecretReadResult.Unavailable(LegacySecretFailure.KEY_UNAVAILABLE)
        }
        return try {
            LegacySecretReadResult.Loaded(decryptSource(source, key))
        } catch (_: UnsupportedLegacySecretVersionException) {
            LegacySecretReadResult.Unavailable(LegacySecretFailure.UNSUPPORTED_VERSION)
        } catch (_: KeyPermanentlyInvalidatedException) {
            LegacySecretReadResult.Unavailable(LegacySecretFailure.KEY_UNAVAILABLE)
        } catch (_: InvalidKeyException) {
            LegacySecretReadResult.Unavailable(LegacySecretFailure.KEY_UNAVAILABLE)
        } catch (_: AEADBadTagException) {
            LegacySecretReadResult.Unavailable(LegacySecretFailure.CORRUPT)
        } catch (_: BadPaddingException) {
            LegacySecretReadResult.Unavailable(LegacySecretFailure.CORRUPT)
        } catch (_: IllegalBlockSizeException) {
            LegacySecretReadResult.Unavailable(LegacySecretFailure.CORRUPT)
        } catch (_: EOFException) {
            LegacySecretReadResult.Unavailable(LegacySecretFailure.CORRUPT)
        } catch (_: IOException) {
            LegacySecretReadResult.Unavailable(LegacySecretFailure.CORRUPT)
        } catch (_: GeneralSecurityException) {
            LegacySecretReadResult.Unavailable(LegacySecretFailure.KEY_UNAVAILABLE)
        } catch (_: IllegalArgumentException) {
            LegacySecretReadResult.Unavailable(LegacySecretFailure.CORRUPT)
        } finally {
            source.fill(0)
        }
    }

    private fun existingKey(alias: String): SecretKey? = KeyStore.getInstance(KEYSTORE_PROVIDER).run {
        load(null)
        getKey(alias, null) as? SecretKey
    }

    private fun readEnvelope(
        source: ByteArray,
        expectedMagic: Int,
        supportedVersions: IntRange,
        maximumCiphertextBytes: Int,
    ): LegacySecretEnvelope = DataInputStream(ByteArrayInputStream(source)).use { data ->
        require(data.readInt() == expectedMagic)
        val version = data.readInt()
        if (version !in supportedVersions) throw UnsupportedLegacySecretVersionException(version)
        val ivLength = data.readInt()
        require(ivLength in 12..32)
        val iv = ByteArray(ivLength).also(data::readFully)
        val ciphertextLength = data.readInt()
        require(ciphertextLength in GCM_TAG_BYTES + 1..maximumCiphertextBytes)
        val ciphertext = ByteArray(ciphertextLength).also(data::readFully)
        require(data.read() == -1)
        LegacySecretEnvelope(version, iv, ciphertext)
    }

    private fun decrypt(
        key: SecretKey,
        iv: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
        maximumPlaintextBytes: Int,
    ): ByteArray = Cipher.getInstance(TRANSFORMATION).run {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        updateAAD(associatedData)
        doFinal(ciphertext).also { cleartext -> require(cleartext.size in 1..maximumPlaintextBytes) }
    }

    private fun passwordAssociatedData(scope: SshPasswordScope, version: Int): ByteArray {
        val format = when (version) {
            PASSWORD_VERSION_V1 -> "terminal-spike-ssh-password-v1"
            PASSWORD_VERSION_V2 -> "terminal-spike-ssh-password-v2"
            else -> throw UnsupportedLegacySecretVersionException(version)
        }
        val host = if (version == PASSWORD_VERSION_V1) scope.host else scope.host.lowercase(Locale.ROOT)
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeUTF(format)
                data.writeLong(scope.profileId)
                data.writeUTF(host)
                data.writeInt(scope.port)
                data.writeUTF(scope.username)
            }
        }.toByteArray()
    }

    private data class LegacySecretEnvelope(
        val version: Int,
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    private class UnsupportedLegacySecretVersionException(version: Int) :
        IllegalArgumentException("Unsupported legacy secret version $version.")

    private companion object {
        const val PASSWORD_DIRECTORY = "ssh-passwords"
        const val PASSWORD_KEY_ALIAS = "terminal_spike_ssh_passwords_v1"
        const val IDENTITY_DIRECTORY = "ssh-identities"
        const val IDENTITY_KEY_ALIAS = "terminal_spike_ssh_identities_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val GCM_TAG_BYTES = TAG_BITS / 8
        const val ENVELOPE_OVERHEAD_BYTES = 256
        const val PASSWORD_MAGIC = 0x54535057
        const val PASSWORD_VERSION_V1 = 1
        const val PASSWORD_VERSION_V2 = 2
        const val IDENTITY_MAGIC = 0x54534931
        const val IDENTITY_VERSION = 1
    }
}
