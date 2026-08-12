package com.yanjiyu.terminalspike.core.backup

import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Streaming envelope encryption and preamble parsing for portable backup version 1.
 *
 * The payload length is declared up front so encryption does not buffer or seek. Decryption writes
 * to a caller-owned *staging* stream. Callers must discard that staging destination on every
 * exception and may parse or publish it only after this method returns successfully.
 */
class BackupEnvelopeCrypto internal constructor(
    private val entropy: BackupEntropySource,
    private val keyDeriver: BackupKeyDeriver,
    private val maximumPayloadBytes: Long,
    private val cipherFactory: BackupCipherFactory = JcaBackupCipherFactory,
) {
    constructor(
        maximumPayloadBytes: Long = BackupEnvelopeFormat.MAX_PAYLOAD_BYTES,
    ) : this(
        SecureRandomEntropySource(),
        JcaPbkdf2KeyDeriver,
        maximumPayloadBytes,
        JcaBackupCipherFactory,
    )

    internal constructor(
        entropy: BackupEntropySource,
        maximumPayloadBytes: Long = BackupEnvelopeFormat.MAX_PAYLOAD_BYTES,
    ) : this(entropy, JcaPbkdf2KeyDeriver, maximumPayloadBytes, JcaBackupCipherFactory)

    init {
        require(maximumPayloadBytes in 0..BackupEnvelopeFormat.MAX_PAYLOAD_BYTES) {
            "Configured payload limit is outside the reviewed range."
        }
    }

    /** Encrypts exactly [payloadLength] bytes and always wipes [passphrase]. */
    fun encryptAndWipePassphrase(
        payload: InputStream,
        payloadLength: Long,
        output: OutputStream,
        passphrase: CharArray,
        metadata: BackupEnvelopeMetadata,
        kdfIterations: Int,
    ): BackupEnvelopeHeader {
        var salt: ByteArray? = null
        var nonce: ByteArray? = null
        var headerBytes: ByteArray? = null
        var keyMaterial: ByteArray? = null
        var key: WipeableSecretKey? = null
        val plaintextBuffer = ByteArray(BackupEnvelopeFormat.STREAM_BUFFER_BYTES)
        try {
            validatePassphrase(passphrase)
            validatePayloadLength(payloadLength)
            validateKdfIterations(kdfIterations)

            salt = ByteArray(BackupEnvelopeFormat.KDF_SALT_BYTES).also(entropy::nextBytes)
            nonce = ByteArray(BackupEnvelopeFormat.GCM_NONCE_BYTES).also(entropy::nextBytes)
            headerBytes = BackupHeaderCodec.encode(metadata, kdfIterations, salt, nonce)
            val header = BackupHeaderCodec.decode(headerBytes)

            keyMaterial = keyDeriver.deriveAndWipePassphrase(passphrase, salt, kdfIterations)
            if (keyMaterial.size != BackupEnvelopeFormat.AES_KEY_BYTES) {
                throw BackupEnvelopeException.CryptographyUnavailable(
                    IllegalStateException("PBKDF2 returned an unexpected key length."),
                )
            }
            key = WipeableSecretKey(keyMaterial, AES)
            val cipher = encryptionCipher(key, nonce, headerBytes)
            // JCA providers may retain the SecretKey object and read it after init.
            keyMaterial = null

            output.write(BackupHeaderCodec.magicV1)
            output.writeU32(headerBytes.size.toLong())
            output.write(headerBytes)
            val ciphertextLength = payloadLength + BackupEnvelopeFormat.GCM_TAG_BYTES
            output.writeU64(ciphertextLength)

            var remaining = payloadLength
            var ciphertextWritten = 0L
            while (remaining > 0) {
                val requested = minOf(remaining, plaintextBuffer.size.toLong()).toInt()
                val count = payload.readNonZero(plaintextBuffer, requested)
                if (count < 0) throw BackupEnvelopeException.PayloadLengthMismatch()
                val encrypted = cipher.update(plaintextBuffer, 0, count)
                if (encrypted != null) {
                    try {
                        output.write(encrypted)
                        ciphertextWritten += encrypted.size
                    } finally {
                        encrypted.fill(0)
                    }
                }
                plaintextBuffer.fill(0, 0, count)
                remaining -= count
            }
            if (payload.read() != -1) throw BackupEnvelopeException.PayloadLengthMismatch()
            val finalCiphertext = try {
                cipher.doFinal()
            } catch (error: GeneralSecurityException) {
                throw BackupEnvelopeException.CryptographyUnavailable(error)
            } catch (error: ProviderException) {
                throw BackupEnvelopeException.CryptographyUnavailable(error)
            }
            try {
                output.write(finalCiphertext)
                ciphertextWritten += finalCiphertext.size
            } finally {
                finalCiphertext.fill(0)
            }
            if (ciphertextWritten != ciphertextLength) {
                throw BackupEnvelopeException.CryptographyUnavailable(
                    IllegalStateException("AES-GCM emitted an unexpected ciphertext length."),
                )
            }
            return header
        } finally {
            passphrase.fill('\u0000')
            plaintextBuffer.fill(0)
            key?.destroy()
            keyMaterial?.fill(0)
            salt?.fill(0)
            nonce?.fill(0)
            headerBytes?.fill(0)
        }
    }

    /**
     * Parses and bounds the unauthenticated preamble without doing KDF work.
     *
     * The returned reader owns the current position of [input] but does not close it.
     */
    fun open(input: InputStream): BackupEnvelopeReader {
        val magic = input.readExact(BackupHeaderCodec.magicV1.size, BackupEnvelopeSection.MAGIC)
        if (!magic.contentEquals(BackupHeaderCodec.magicV1)) {
            val version = parseProductMagicVersion(magic)
            if (version != null) {
                throw BackupEnvelopeException.UnsupportedVersion(BackupVersionKind.ENVELOPE, version)
            }
            throw BackupEnvelopeException.Malformed(BackupMalformedReason.INVALID_MAGIC)
        }

        val headerLengthBytes = input.readExact(U32_BYTES, BackupEnvelopeSection.HEADER_LENGTH)
        val headerLength = headerLengthBytes.readU32().also { length ->
            if (length > BackupEnvelopeFormat.MAX_HEADER_BYTES.toLong()) {
                throw BackupEnvelopeException.LimitExceeded(BackupLimit.HEADER_LENGTH)
            }
        }.toInt()
        val headerBytes = input.readExact(headerLength, BackupEnvelopeSection.HEADER)
        val header = BackupHeaderCodec.decode(headerBytes)

        val ciphertextLengthBytes = input.readExact(U64_BYTES, BackupEnvelopeSection.CIPHERTEXT_LENGTH)
        val ciphertextLength = ciphertextLengthBytes.readSupportedU64Length()
        if (ciphertextLength < BackupEnvelopeFormat.GCM_TAG_BYTES) {
            throw BackupEnvelopeException.Malformed(BackupMalformedReason.INVALID_FIELD_VALUE)
        }
        val payloadLength = ciphertextLength - BackupEnvelopeFormat.GCM_TAG_BYTES
        if (payloadLength > maximumPayloadBytes) {
            throw BackupEnvelopeException.LimitExceeded(BackupLimit.CIPHERTEXT_LENGTH)
        }

        return BackupEnvelopeReader(
            input = input,
            header = header,
            canonicalHeader = headerBytes,
            ciphertextLength = ciphertextLength,
            plaintextLength = payloadLength,
            keyDeriver = keyDeriver,
            cipherFactory = cipherFactory,
        )
    }

    private fun encryptionCipher(
        key: SecretKey,
        nonce: ByteArray,
        headerBytes: ByteArray,
    ): BackupCipherSession = try {
        cipherFactory.create().apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAssociatedData(BackupHeaderCodec.magicV1)
            updateAssociatedData(headerBytes)
        }
    } catch (error: GeneralSecurityException) {
        throw BackupEnvelopeException.CryptographyUnavailable(error)
    } catch (error: ProviderException) {
        throw BackupEnvelopeException.CryptographyUnavailable(error)
    }

    private fun validatePayloadLength(payloadLength: Long) {
        if (payloadLength !in 0..maximumPayloadBytes) {
            throw BackupEnvelopeException.LimitExceeded(BackupLimit.PAYLOAD_LENGTH)
        }
    }

    companion object {
        private const val AES = "AES"
        private const val GCM_TAG_BITS = BackupEnvelopeFormat.GCM_TAG_BYTES * 8
        private const val U32_BYTES = 4
        private const val U64_BYTES = 8
    }
}

/** A single-use reader positioned immediately before the ciphertext. */
class BackupEnvelopeReader internal constructor(
    private val input: InputStream,
    val header: BackupEnvelopeHeader,
    private val canonicalHeader: ByteArray,
    val ciphertextLength: Long,
    val plaintextLength: Long,
    private val keyDeriver: BackupKeyDeriver,
    private val cipherFactory: BackupCipherFactory,
) {
    private var consumed = false

    /**
     * Authenticates and decrypts to [stagingOutput], always wiping [passphrase].
     *
     * [stagingOutput] is deliberately not closed or flushed. It must be discarded if this method
     * throws, because a JCA provider may emit unauthenticated plaintext before checking the GCM tag.
     */
    fun decryptToStagingAndWipePassphrase(
        stagingOutput: OutputStream,
        passphrase: CharArray,
        supportedPayloadSchemas: IntRange =
            BackupEnvelopeFormat.CURRENT_PAYLOAD_SCHEMA_VERSION..
                BackupEnvelopeFormat.CURRENT_PAYLOAD_SCHEMA_VERSION,
    ): BackupEnvelopeHeader {
        var salt: ByteArray? = null
        var nonce: ByteArray? = null
        var keyMaterial: ByteArray? = null
        var key: WipeableSecretKey? = null
        val ciphertextBuffer = ByteArray(BackupEnvelopeFormat.STREAM_BUFFER_BYTES)
        try {
            check(!consumed) { "Backup envelope reader is single-use." }
            consumed = true
            validatePassphrase(passphrase)
            require(!supportedPayloadSchemas.isEmpty()) { "Supported payload schemas must not be empty." }

            salt = header.copySaltForCrypto()
            nonce = header.copyNonceForCrypto()
            keyMaterial = keyDeriver.deriveAndWipePassphrase(passphrase, salt, header.kdfIterations)
            if (keyMaterial.size != BackupEnvelopeFormat.AES_KEY_BYTES) {
                throw BackupEnvelopeException.CryptographyUnavailable(
                    IllegalStateException("PBKDF2 returned an unexpected key length."),
                )
            }
            key = WipeableSecretKey(keyMaterial, AES)
            val cipher = decryptionCipher(key, nonce)
            // JCA providers may retain the SecretKey object through update/doFinal.
            keyMaterial = null

            var remaining = ciphertextLength
            var plaintextWritten = 0L
            while (remaining > 0) {
                val requested = minOf(remaining, ciphertextBuffer.size.toLong()).toInt()
                val count = input.readNonZero(ciphertextBuffer, requested)
                if (count < 0) throw BackupEnvelopeException.Truncated(BackupEnvelopeSection.CIPHERTEXT)
                val decrypted = try {
                    cipher.update(ciphertextBuffer, 0, count)
                } catch (error: ProviderException) {
                    throw BackupEnvelopeException.CryptographyUnavailable(error)
                }
                if (decrypted != null) {
                    try {
                        stagingOutput.write(decrypted)
                        plaintextWritten += decrypted.size
                    } finally {
                        decrypted.fill(0)
                    }
                }
                ciphertextBuffer.fill(0, 0, count)
                remaining -= count
            }
            if (input.read() != -1) {
                throw BackupEnvelopeException.Malformed(BackupMalformedReason.TRAILING_DATA)
            }

            val finalPlaintext = try {
                cipher.doFinal()
            } catch (_: AEADBadTagException) {
                throw BackupEnvelopeException.UnlockFailed()
            } catch (_: BadPaddingException) {
                throw BackupEnvelopeException.UnlockFailed()
            } catch (_: IllegalBlockSizeException) {
                throw BackupEnvelopeException.UnlockFailed()
            } catch (error: GeneralSecurityException) {
                throw BackupEnvelopeException.CryptographyUnavailable(error)
            } catch (error: ProviderException) {
                throw BackupEnvelopeException.CryptographyUnavailable(error)
            }
            try {
                stagingOutput.write(finalPlaintext)
                plaintextWritten += finalPlaintext.size
            } finally {
                finalPlaintext.fill(0)
            }
            if (plaintextWritten != plaintextLength) {
                throw BackupEnvelopeException.UnlockFailed()
            }
            if (header.payloadSchemaVersion !in supportedPayloadSchemas) {
                throw BackupEnvelopeException.UnsupportedVersion(
                    kind = BackupVersionKind.PAYLOAD,
                    version = header.payloadSchemaVersion.toLong(),
                    producingApp = header.appVersion,
                )
            }
            return header
        } finally {
            passphrase.fill('\u0000')
            ciphertextBuffer.fill(0)
            key?.destroy()
            keyMaterial?.fill(0)
            salt?.fill(0)
            nonce?.fill(0)
        }
    }

    private fun decryptionCipher(key: SecretKey, nonce: ByteArray): BackupCipherSession = try {
        cipherFactory.create().apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAssociatedData(BackupHeaderCodec.magicV1)
            updateAssociatedData(canonicalHeader)
        }
    } catch (error: GeneralSecurityException) {
        throw BackupEnvelopeException.CryptographyUnavailable(error)
    } catch (error: ProviderException) {
        throw BackupEnvelopeException.CryptographyUnavailable(error)
    }

    companion object {
        private const val AES = "AES"
        private const val GCM_TAG_BITS = BackupEnvelopeFormat.GCM_TAG_BYTES * 8
    }
}

internal fun interface BackupEntropySource {
    fun nextBytes(destination: ByteArray)
}

private class SecureRandomEntropySource : BackupEntropySource {
    private val secureRandom = SecureRandom()

    override fun nextBytes(destination: ByteArray) = secureRandom.nextBytes(destination)
}

internal fun interface BackupKeyDeriver {
    /** Returns 32 mutable key bytes and consumes [passphrase] even when derivation fails. */
    fun deriveAndWipePassphrase(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray
}

internal fun interface BackupCipherFactory {
    fun create(): BackupCipherSession
}

internal interface BackupCipherSession {
    fun init(mode: Int, key: SecretKey, parameters: GCMParameterSpec)

    fun updateAssociatedData(bytes: ByteArray)

    fun update(input: ByteArray, offset: Int, length: Int): ByteArray?

    fun doFinal(): ByteArray
}

private object JcaBackupCipherFactory : BackupCipherFactory {
    override fun create(): BackupCipherSession = JcaBackupCipherSession()
}

private class JcaBackupCipherSession : BackupCipherSession {
    private val cipher = Cipher.getInstance(TRANSFORMATION)

    override fun init(mode: Int, key: SecretKey, parameters: GCMParameterSpec) {
        cipher.init(mode, key, parameters)
    }

    override fun updateAssociatedData(bytes: ByteArray) = cipher.updateAAD(bytes)

    override fun update(input: ByteArray, offset: Int, length: Int): ByteArray? =
        cipher.update(input, offset, length)

    override fun doFinal(): ByteArray = cipher.doFinal()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal object JcaPbkdf2KeyDeriver : BackupKeyDeriver {
    override fun deriveAndWipePassphrase(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        var specification: PBEKeySpec? = null
        var generatedKey: SecretKey? = null
        var encodedKey: ByteArray? = null
        try {
            validatePassphrase(passphrase)
            validateKdfIterations(iterations)
            specification = PBEKeySpec(passphrase, salt, iterations, BackupEnvelopeFormat.AES_KEY_BYTES * 8)
            generatedKey = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(specification)
            encodedKey = generatedKey.encoded
            return encodedKey?.also { keyBytes ->
                if (keyBytes.size != BackupEnvelopeFormat.AES_KEY_BYTES) {
                    throw BackupEnvelopeException.CryptographyUnavailable(
                        IllegalStateException("PBKDF2 returned an unexpected key length."),
                    )
                }
            }?.copyOf() ?: throw BackupEnvelopeException.CryptographyUnavailable(
                IllegalStateException("PBKDF2 key material is not encodable."),
            )
        } catch (error: BackupEnvelopeException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw BackupEnvelopeException.CryptographyUnavailable(error)
        } catch (error: ProviderException) {
            throw BackupEnvelopeException.CryptographyUnavailable(error)
        } finally {
            passphrase.fill('\u0000')
            specification?.clearPassword()
            encodedKey?.fill(0)
            try {
                generatedKey?.destroy()
            } catch (_: Exception) {
                // Some Android providers do not implement Destroyable; the returned copy is wiped.
            }
        }
    }

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
}

private class WipeableSecretKey(
    private val material: ByteArray,
    private val algorithmName: String,
) : SecretKey {
    @Volatile
    private var destroyed = false

    override fun getAlgorithm(): String = algorithmName

    override fun getFormat(): String = "RAW"

    // Providers may wipe the array returned by getEncoded(), so never lend the owned backing bytes.
    override fun getEncoded(): ByteArray = material.copyOf()

    override fun destroy() {
        material.fill(0)
        destroyed = true
    }

    override fun isDestroyed(): Boolean = destroyed
}

internal fun validateKdfIterations(iterations: Int) {
    if (iterations !in BackupEnvelopeFormat.MIN_KDF_ITERATIONS..BackupEnvelopeFormat.MAX_KDF_ITERATIONS) {
        throw BackupEnvelopeException.LimitExceeded(BackupLimit.KDF_ITERATIONS)
    }
}

internal fun validatePassphrase(passphrase: CharArray) {
    if (passphrase.size > BackupEnvelopeFormat.MAX_PASSPHRASE_CHARS) {
        throw BackupEnvelopeException.LimitExceeded(BackupLimit.PASSPHRASE_LENGTH)
    }
    var index = 0
    while (index < passphrase.size) {
        val character = passphrase[index]
        when {
            Character.isHighSurrogate(character) -> {
                if (index + 1 >= passphrase.size || !Character.isLowSurrogate(passphrase[index + 1])) {
                    throw BackupEnvelopeException.Malformed(BackupMalformedReason.INVALID_UTF8)
                }
                index += 2
            }
            Character.isLowSurrogate(character) ->
                throw BackupEnvelopeException.Malformed(BackupMalformedReason.INVALID_UTF8)
            else -> index += 1
        }
    }
}

private fun InputStream.readExact(length: Int, section: BackupEnvelopeSection): ByteArray {
    val result = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = read(result, offset, length - offset)
        when {
            count < 0 -> throw BackupEnvelopeException.Truncated(section)
            count == 0 -> {
                val oneByte = read()
                if (oneByte < 0) throw BackupEnvelopeException.Truncated(section)
                result[offset++] = oneByte.toByte()
            }
            else -> offset += count
        }
    }
    return result
}

private fun InputStream.readNonZero(destination: ByteArray, maximum: Int): Int {
    val count = read(destination, 0, maximum)
    if (count != 0) return count
    val oneByte = read()
    if (oneByte < 0) return -1
    destination[0] = oneByte.toByte()
    return 1
}

private fun ByteArray.readU32(): Long =
    ((this[0].toInt() and 0xff).toLong() shl 24) or
        ((this[1].toInt() and 0xff).toLong() shl 16) or
        ((this[2].toInt() and 0xff).toLong() shl 8) or
        (this[3].toInt() and 0xff).toLong()

private fun ByteArray.readSupportedU64Length(): Long {
    if ((this[0].toInt() and 0x80) != 0) {
        throw BackupEnvelopeException.LimitExceeded(BackupLimit.CIPHERTEXT_LENGTH)
    }
    var value = 0L
    forEach { byte -> value = (value shl 8) or (byte.toInt() and 0xff).toLong() }
    return value
}

private fun parseProductMagicVersion(magic: ByteArray): Long? {
    val prefix = "TSPBKP".toByteArray(Charsets.US_ASCII)
    if (magic.size != prefix.size + 2 || !magic.copyOfRange(0, prefix.size).contentEquals(prefix)) {
        return null
    }
    val tens = magic[prefix.size].toInt() - '0'.code
    val ones = magic[prefix.size + 1].toInt() - '0'.code
    if (tens !in 0..9 || ones !in 0..9) return null
    return (tens * 10L) + ones
}
