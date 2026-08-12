package com.yanjiyu.terminalspike.core.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persistent recovery boundary for a destructive Replace import. */
interface BackupRecoveryMarkerStore {
    /**
     * Consumes [snapshot] and wipes all portable secret material on every exit. Implementations
     * must atomically refuse to replace an existing marker with [BackupRecoveryMarkerException.AlreadyPending].
     */
    suspend fun writeAndWipe(snapshot: BackupPayloadSnapshot)

    /** Returns the authenticated pre-import snapshot, or null when no recovery is pending. */
    suspend fun read(): BackupPayloadSnapshot?

    suspend fun clear()

    fun hasPendingRecovery(): Boolean
}

sealed class BackupRecoveryMarkerException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class KeyUnavailable(cause: Throwable? = null) : BackupRecoveryMarkerException(
        "The device recovery key is unavailable.",
        cause,
    )

    class Malformed(cause: Throwable? = null) : BackupRecoveryMarkerException(
        "The pending import recovery snapshot is malformed or did not authenticate.",
        cause,
    )

    class AlreadyPending : BackupRecoveryMarkerException(
        "A pending import recovery snapshot already exists.",
    )
}

/**
 * App-private, device-bound recovery storage. The payload is the existing bounded backup payload
 * format, encrypted directly with a non-exportable Android Keystore AES-256-GCM key. No recovery
 * passphrase, plaintext temporary file, or Android Keystore key is written to disk.
 */
class AndroidBackupRecoveryMarkerStore(
    context: Context,
    private val payloads: BackupPayloadCodec = BackupPayloadCodec(),
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : BackupRecoveryMarkerStore {
    private val file = AtomicFile(
        context.applicationContext.noBackupFilesDir
            .resolve(RECOVERY_DIRECTORY)
            .resolve(RECOVERY_FILE),
    )
    // AtomicFile.openRead() promotes this legacy backup to the base file after an interrupted write.
    private val legacyBackupFile = File("${file.baseFile.path}.bak")
    private val mutex = Mutex()

    init {
        require(keyAlias.length in 1..MAX_ALIAS_LENGTH && keyAlias.none(Char::isISOControl)) {
            "Invalid recovery-key alias."
        }
    }

    override suspend fun writeAndWipe(snapshot: BackupPayloadSnapshot) = mutex.withLock {
        var output: java.io.FileOutputStream? = null
        try {
            if (hasPendingRecovery()) throw BackupRecoveryMarkerException.AlreadyPending()
            require(snapshot.mode == BackupMode.FULL) {
                "A destructive import recovery marker must include portable credentials."
            }
            file.baseFile.parentFile?.mkdirs()
            require(file.baseFile.parentFile?.isDirectory == true) {
                "Private recovery storage is unavailable."
            }
            val cipher = encryptionCipher()
            val header = encodeHeader(requireNotNull(cipher.iv))
            val activeOutput = file.startWrite()
            output = activeOutput
            activeOutput.write(header)
            cipher.updateAAD(header)
            CipherOutputStream(NonClosingOutputStream(activeOutput), cipher).use { encrypted ->
                payloads.writeAndWipeSecrets(snapshot, encrypted)
            }
            activeOutput.fd.sync()
            file.finishWrite(activeOutput)
            output = null
        } catch (error: Throwable) {
            output?.let(file::failWrite)
            throw error
        } finally {
            snapshot.close()
        }
    }

    override suspend fun read(): BackupPayloadSnapshot? = mutex.withLock {
        if (!hasPendingRecovery()) return@withLock null
        try {
            file.openRead().use { raw ->
                val input = DataInputStream(raw)
                val header = readHeader(input)
                val cipher = decryptionCipher(header.nonce)
                cipher.updateAAD(header.encoded)
                CipherInputStream(input, cipher).use { decrypted ->
                    val result = payloads.read(decrypted, BackupMode.FULL)
                    if (
                        result.compatibility.incompatibleRecords.isNotEmpty() ||
                        result.compatibility.skippedUnknownCollections != 0 ||
                        result.compatibility.skippedUnknownRecordTypes != 0 ||
                        result.compatibility.skippedUnknownRecords != 0 ||
                        result.compatibility.unresolvedReferences.isNotEmpty()
                    ) {
                        result.snapshot.close()
                        throw BackupRecoveryMarkerException.Malformed()
                    }
                    return@withLock result.snapshot
                }
            }
        } catch (error: BackupRecoveryMarkerException) {
            throw error
        } catch (error: Throwable) {
            throw BackupRecoveryMarkerException.Malformed(error)
        }
    }

    override suspend fun clear() = mutex.withLock {
        file.delete()
    }

    override fun hasPendingRecovery(): Boolean = file.baseFile.isFile || legacyBackupFile.isFile

    private fun encryptionCipher(): Cipher = try {
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            check(iv?.size == NONCE_BYTES) { "Recovery encryption produced an invalid nonce." }
        }
    } catch (error: BackupRecoveryMarkerException) {
        throw error
    } catch (error: GeneralSecurityException) {
        throw BackupRecoveryMarkerException.KeyUnavailable(error)
    }

    private fun decryptionCipher(nonce: ByteArray): Cipher = try {
        val key = getExistingKey() ?: throw BackupRecoveryMarkerException.KeyUnavailable()
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        }
    } catch (error: BackupRecoveryMarkerException) {
        throw error
    } catch (error: GeneralSecurityException) {
        throw BackupRecoveryMarkerException.KeyUnavailable(error)
    }

    private fun getExistingKey(): SecretKey? = try {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.getKey(keyAlias, null) as? SecretKey
    } catch (error: Exception) {
        throw BackupRecoveryMarkerException.KeyUnavailable(error)
    }

    private fun getOrCreateKey(): SecretKey {
        getExistingKey()?.let { return it }
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(AES_KEY_BITS)
                        .setRandomizedEncryptionRequired(true)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
                generateKey()
            }
        } catch (error: Exception) {
            throw BackupRecoveryMarkerException.KeyUnavailable(error)
        }
    }

    private fun encodeHeader(nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES) { "Invalid recovery nonce." }
        return ByteArrayOutputStream(HEADER_BYTES).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeByte(BackupMode.FULL.wireValue)
                output.writeByte(nonce.size)
                output.write(nonce)
            }
            bytes.toByteArray()
        }
    }

    private fun readHeader(input: DataInputStream): RecoveryHeader {
        val magic = ByteArray(MAGIC.size).also(input::readFully)
        if (!magic.contentEquals(MAGIC)) throw BackupRecoveryMarkerException.Malformed()
        if (input.readInt() != FORMAT_VERSION) throw BackupRecoveryMarkerException.Malformed()
        if (input.readUnsignedByte() != BackupMode.FULL.wireValue) {
            throw BackupRecoveryMarkerException.Malformed()
        }
        val nonceLength = input.readUnsignedByte()
        if (nonceLength != NONCE_BYTES) throw BackupRecoveryMarkerException.Malformed()
        val nonce = ByteArray(nonceLength).also(input::readFully)
        return RecoveryHeader(nonce, encodeHeader(nonce))
    }

    private data class RecoveryHeader(
        val nonce: ByteArray,
        val encoded: ByteArray,
    )

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() = flush()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "terminal_spike_backup_recovery_aes_256_gcm_v1"
        private const val RECOVERY_DIRECTORY = "backup-recovery"
        private const val RECOVERY_FILE = "pending-replace.tsp-recovery"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = 1
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val AES_KEY_BITS = 256
        private const val MAX_ALIAS_LENGTH = 128
        private val MAGIC = "TSPREC01".encodeToByteArray()
        private val HEADER_BYTES = MAGIC.size + Int.SIZE_BYTES + 1 + 1 + NONCE_BYTES
    }
}
