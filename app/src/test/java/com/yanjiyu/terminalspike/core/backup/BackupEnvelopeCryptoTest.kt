package com.yanjiyu.terminalspike.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupEnvelopeCryptoTest {
    @Test
    fun deterministicEnvelopeMatchesIndependentVersion1KnownAnswer() {
        val crypto = BackupEnvelopeCrypto(CountingEntropy())
        val payload = "TSPPAY01:known-answer".toByteArray()

        val envelope = encrypt(crypto, payload)

        assertEquals(191L, envelope.readU32At(8))
        assertEquals(248, envelope.size)
        assertEquals(
            "078dadbae20e2cd936fff39a3bc73fa5811e758078a006c17f3245c0c4c92135",
            MessageDigest.getInstance("SHA-256").digest(envelope).toHex(),
        )
    }

    @Test
    fun standardAndFullRoundTripThroughFragmentedNonSeekableStreams() {
        BackupMode.entries.forEachIndexed { index, mode ->
            val crypto = BackupEnvelopeCrypto(CountingEntropy(index * 80))
            val payload = ("TSPPAY01:${mode.name}:" + "payload-".repeat(2_000)).toByteArray()
            val encrypted = ByteArrayOutputStream()
            val exportPassphrase = "  pässphrase 🔒  ".toCharArray()

            val writtenHeader = crypto.encryptAndWipePassphrase(
                payload = FragmentedInputStream(payload, maximumChunk = 17),
                payloadLength = payload.size.toLong(),
                output = encrypted,
                passphrase = exportPassphrase,
                metadata = metadata(mode),
                kdfIterations = BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
            )

            assertAllZero(exportPassphrase)
            assertEquals(mode, writtenHeader.mode)
            assertEquals(payload.size.toLong(), encryptedPayloadLength(encrypted.toByteArray()))

            val reader = crypto.open(FragmentedInputStream(encrypted.toByteArray(), maximumChunk = 3))
            assertEquals(mode, reader.header.mode)
            assertEquals(payload.size.toLong(), reader.plaintextLength)
            assertEquals(metadata(mode).appVersion, reader.header.appVersion)
            assertEquals(metadata(mode).compatibility, reader.header.compatibility)
            val restored = ByteArrayOutputStream()
            val importPassphrase = "  pässphrase 🔒  ".toCharArray()

            val unlockedHeader = reader.decryptToStagingAndWipePassphrase(restored, importPassphrase)

            assertAllZero(importPassphrase)
            assertEquals(mode, unlockedHeader.mode)
            assertArrayEquals(payload, restored.toByteArray())
        }
    }

    @Test
    fun everyExportGetsFreshSaltAndNonceAndHeaderArraysAreDefensive() {
        val crypto = BackupEnvelopeCrypto(CountingEntropy())
        val first = encrypt(crypto, "TSPPAY01:first".toByteArray())
        val second = encrypt(crypto, "TSPPAY01:second".toByteArray())
        val firstHeader = crypto.open(ByteArrayInputStream(first)).header
        val secondHeader = crypto.open(ByteArrayInputStream(second)).header

        assertNotEquals(firstHeader.copyKdfSalt().toList(), secondHeader.copyKdfSalt().toList())
        assertNotEquals(firstHeader.copyNonce().toList(), secondHeader.copyNonce().toList())
        val salt = firstHeader.copyKdfSalt().apply { fill(0) }
        val nonce = firstHeader.copyNonce().apply { fill(0) }

        assertTrue(salt.all { it == 0.toByte() })
        assertTrue(nonce.all { it == 0.toByte() })
        assertTrue(firstHeader.copyKdfSalt().any { it != 0.toByte() })
        assertTrue(firstHeader.copyNonce().any { it != 0.toByte() })
    }

    @Test
    fun wrongPassphraseHeaderCiphertextAndTagTamperShareUnlockFailure() {
        val crypto = BackupEnvelopeCrypto(CountingEntropy())
        val original = encrypt(crypto, "TSPPAY01:authenticated payload".toByteArray())
        val offsets = offsets(original)
        val changedHeader = original.copyOf().apply {
            // Field 3's u64 value; structure and all declared lengths remain valid.
            this[offsets.headerStart + 10 + 7 + 6 + 7] =
                this[offsets.headerStart + 10 + 7 + 6 + 7].inc()
        }
        val changedCiphertext = original.copyOf().apply {
            this[offsets.ciphertextStart] = this[offsets.ciphertextStart].inc()
        }
        val changedTag = original.copyOf().apply { this[lastIndex] = this[lastIndex].inc() }

        listOf(
            original to "wrong passphrase",
            changedHeader to PASSPHRASE,
            changedCiphertext to PASSPHRASE,
            changedTag to PASSPHRASE,
        ).forEach { (envelope, candidate) ->
            val passphrase = candidate.toCharArray()
            val error = assertThrows(BackupEnvelopeException.UnlockFailed::class.java) {
                crypto.open(FragmentedInputStream(envelope, 5))
                    .decryptToStagingAndWipePassphrase(ByteArrayOutputStream(), passphrase)
            }
            assertEquals("Could not unlock backup or the file was changed.", error.message)
            assertAllZero(passphrase)
        }
    }

    @Test
    fun truncationAtEveryByteBoundaryIsTypedAndNeverReadsAvailable() {
        val crypto = BackupEnvelopeCrypto(CountingEntropy())
        val envelope = encrypt(crypto, "TSPPAY01".toByteArray())

        for (cut in 0 until envelope.size) {
            val truncated = envelope.copyOf(cut)
            val error = runCatching {
                val reader = crypto.open(FragmentedInputStream(truncated, 2))
                reader.decryptToStagingAndWipePassphrase(ByteArrayOutputStream(), PASSPHRASE.toCharArray())
            }.exceptionOrNull()

            assertTrue("cut=$cut produced ${error?.javaClass}", error is BackupEnvelopeException.Truncated)
        }
    }

    @Test
    fun trailingBytesAndInvalidCiphertextLengthsFailBeforeSuccess() {
        val crypto = BackupEnvelopeCrypto(CountingEntropy())
        val original = encrypt(crypto, "TSPPAY01:payload".toByteArray())
        val withTrailingByte = original + 0x55.toByte()
        val passphrase = PASSPHRASE.toCharArray()

        val trailing = assertThrows(BackupEnvelopeException.Malformed::class.java) {
            crypto.open(ByteArrayInputStream(withTrailingByte))
                .decryptToStagingAndWipePassphrase(ByteArrayOutputStream(), passphrase)
        }
        assertEquals(BackupMalformedReason.TRAILING_DATA, trailing.reason)
        assertAllZero(passphrase)

        val offsets = offsets(original)
        val shorterThanTag = original.copyOf().apply {
            writeU64At(offsets.ciphertextLengthOffset, BackupEnvelopeFormat.GCM_TAG_BYTES - 1L)
        }
        assertEquals(
            BackupMalformedReason.INVALID_FIELD_VALUE,
            assertThrows(BackupEnvelopeException.Malformed::class.java) {
                crypto.open(ByteArrayInputStream(shorterThanTag))
            }.reason,
        )

        val unrepresentable = original.copyOf().apply { this[offsets.ciphertextLengthOffset] = 0x80.toByte() }
        assertEquals(
            BackupLimit.CIPHERTEXT_LENGTH,
            assertThrows(BackupEnvelopeException.LimitExceeded::class.java) {
                crypto.open(ByteArrayInputStream(unrepresentable))
            }.limit,
        )

        val overConfiguredLimit = original.copyOf().apply {
            writeU64At(offsets.ciphertextLengthOffset, 65L)
        }
        assertEquals(
            BackupLimit.CIPHERTEXT_LENGTH,
            assertThrows(BackupEnvelopeException.LimitExceeded::class.java) {
                BackupEnvelopeCrypto(maximumPayloadBytes = 32)
                    .open(ByteArrayInputStream(overConfiguredLimit))
            }.limit,
        )
    }

    @Test
    fun oversizedHeaderAndKdfWorkAreRejectedBeforeKeyDerivation() {
        val normalCrypto = BackupEnvelopeCrypto(CountingEntropy())
        val original = encrypt(normalCrypto, "TSPPAY01:payload".toByteArray())
        val neverDerive = BackupEnvelopeCrypto(
            entropy = CountingEntropy(),
            keyDeriver = BackupKeyDeriver { _, _, _ -> throw AssertionError("KDF must not run") },
            maximumPayloadBytes = BackupEnvelopeFormat.MAX_PAYLOAD_BYTES,
        )
        val oversizedHeader = original.copyOf().apply {
            writeU32At(8, BackupEnvelopeFormat.MAX_HEADER_BYTES.toLong() + 1)
        }
        assertEquals(
            BackupLimit.HEADER_LENGTH,
            assertThrows(BackupEnvelopeException.LimitExceeded::class.java) {
                neverDerive.open(ByteArrayInputStream(oversizedHeader))
            }.limit,
        )

        val excessiveIterations = mutateHeaderField(original, fieldId = 7) { value ->
            ByteArray(4).also { replacement ->
                replacement.writeU32At(0, BackupEnvelopeFormat.MAX_KDF_ITERATIONS.toLong() + 1)
            }
        }
        assertEquals(
            BackupLimit.KDF_ITERATIONS,
            assertThrows(BackupEnvelopeException.LimitExceeded::class.java) {
                neverDerive.open(ByteArrayInputStream(excessiveIterations))
            }.limit,
        )
    }

    @Test
    fun unsupportedEnvelopePayloadKdfAeadAndRequiredFieldAreTyped() {
        val crypto = BackupEnvelopeCrypto(CountingEntropy())
        val original = encrypt(crypto, "TSPPAY01:payload".toByteArray())
        val futureMagic = original.copyOf().apply {
            "TSPBKP02".toByteArray().copyInto(this, 0)
        }
        val magicError = assertThrows(BackupEnvelopeException.UnsupportedVersion::class.java) {
            crypto.open(ByteArrayInputStream(futureMagic))
        }
        assertEquals(BackupVersionKind.ENVELOPE, magicError.kind)
        assertEquals(2L, magicError.version)

        val futureEnvelopeField = mutateHeaderField(original, 1) {
            byteArrayOf(0, 0, 0, 2)
        }
        val envelopeError = assertThrows(BackupEnvelopeException.UnsupportedVersion::class.java) {
            crypto.open(ByteArrayInputStream(futureEnvelopeField))
        }
        assertEquals(BackupVersionKind.ENVELOPE, envelopeError.kind)
        assertEquals(metadata().appVersion, envelopeError.producingApp)

        val unsupportedKdf = mutateHeaderField(original, 6) { byteArrayOf(2) }
        assertEquals(
            BackupUnsupportedReason.KDF,
            assertThrows(BackupEnvelopeException.UnsupportedFormat::class.java) {
                crypto.open(ByteArrayInputStream(unsupportedKdf))
            }.reason,
        )
        val unsupportedAead = mutateHeaderField(original, 9) { byteArrayOf(2) }
        assertEquals(
            BackupUnsupportedReason.AEAD,
            assertThrows(BackupEnvelopeException.UnsupportedFormat::class.java) {
                crypto.open(ByteArrayInputStream(unsupportedAead))
            }.reason,
        )

        val unknownRequired = appendHeaderField(original, 0x800c, byteArrayOf(1))
        val requiredError = assertThrows(BackupEnvelopeException.UnsupportedFormat::class.java) {
            crypto.open(ByteArrayInputStream(unknownRequired))
        }
        assertEquals(BackupUnsupportedReason.REQUIRED_HEADER_FIELD, requiredError.reason)
        assertEquals(0x800c, requiredError.fieldId)
        assertEquals(metadata().appVersion, requiredError.producingApp)

        val futurePayload = encrypt(
            crypto,
            payload = "TSPPAY02:future".toByteArray(),
            metadata = metadata(payloadSchemaVersion = 2),
        )
        val staging = ByteArrayOutputStream()
        val payloadError = assertThrows(BackupEnvelopeException.UnsupportedVersion::class.java) {
            crypto.open(ByteArrayInputStream(futurePayload)).decryptToStagingAndWipePassphrase(
                staging,
                PASSPHRASE.toCharArray(),
            )
        }
        assertEquals(BackupVersionKind.PAYLOAD, payloadError.kind)
        assertEquals(2L, payloadError.version)
        assertEquals(metadata().appVersion, payloadError.producingApp)
        assertArrayEquals("TSPPAY02:future".toByteArray(), staging.toByteArray())

        val accepted = ByteArrayOutputStream()
        crypto.open(ByteArrayInputStream(futurePayload)).decryptToStagingAndWipePassphrase(
            accepted,
            PASSPHRASE.toCharArray(),
            supportedPayloadSchemas = 1..2,
        )
        assertArrayEquals("TSPPAY02:future".toByteArray(), accepted.toByteArray())
    }

    @Test
    fun declaredPayloadLengthIsBoundedAndExactAndSecretsAreWipedOnFailure() {
        val crypto = BackupEnvelopeCrypto(CountingEntropy(), maximumPayloadBytes = 32)
        val tooLongPassphrase = CharArray(BackupEnvelopeFormat.MAX_PASSPHRASE_CHARS + 1) { 'x' }
        assertEquals(
            BackupLimit.PASSPHRASE_LENGTH,
            assertThrows(BackupEnvelopeException.LimitExceeded::class.java) {
                crypto.encryptAndWipePassphrase(
                    ByteArrayInputStream(byteArrayOf()),
                    0,
                    ByteArrayOutputStream(),
                    tooLongPassphrase,
                    metadata(),
                    BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
                )
            }.limit,
        )
        assertAllZero(tooLongPassphrase)

        val overLimitPassphrase = PASSPHRASE.toCharArray()
        assertEquals(
            BackupLimit.PAYLOAD_LENGTH,
            assertThrows(BackupEnvelopeException.LimitExceeded::class.java) {
                crypto.encryptAndWipePassphrase(
                    ByteArrayInputStream(ByteArray(33)),
                    33,
                    ByteArrayOutputStream(),
                    overLimitPassphrase,
                    metadata(),
                    BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
                )
            }.limit,
        )
        assertAllZero(overLimitPassphrase)

        listOf(ByteArray(3) to 4L, ByteArray(5) to 4L).forEach { (source, declared) ->
            val passphrase = PASSPHRASE.toCharArray()
            assertThrows(BackupEnvelopeException.PayloadLengthMismatch::class.java) {
                crypto.encryptAndWipePassphrase(
                    ByteArrayInputStream(source),
                    declared,
                    ByteArrayOutputStream(),
                    passphrase,
                    metadata(),
                    BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
                )
            }
            assertAllZero(passphrase)
        }
    }

    @Test
    fun derivedKeyAndProviderPlaintextBuffersAreZeroedAfterUse() {
        val recordingDeriver = RecordingKeyDeriver()
        val crypto = BackupEnvelopeCrypto(
            entropy = CountingEntropy(),
            keyDeriver = recordingDeriver,
            maximumPayloadBytes = BackupEnvelopeFormat.MAX_PAYLOAD_BYTES,
        )
        val payload = "TSPPAY01:secret credential material".toByteArray()
        val inspectingInput = InspectingInputStream(payload)
        val encrypted = ByteArrayOutputStream()

        crypto.encryptAndWipePassphrase(
            inspectingInput,
            payload.size.toLong(),
            encrypted,
            PASSPHRASE.toCharArray(),
            metadata(),
            BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
        )

        assertAllZero(recordingDeriver.lastDerived)
        assertAllZero(inspectingInput.lastDestination)

        val inspectingOutput = InspectingOutputStream()
        crypto.open(ByteArrayInputStream(encrypted.toByteArray())).decryptToStagingAndWipePassphrase(
            inspectingOutput,
            PASSPHRASE.toCharArray(),
        )
        assertArrayEquals(payload, inspectingOutput.copyWrittenBytes())
        assertAllZero(recordingDeriver.lastDerived)
        assertTrue(inspectingOutput.borrowedArrays.isNotEmpty())
        inspectingOutput.borrowedArrays.forEach(::assertAllZero)
    }

    @Test
    fun keyRemainsLiveThroughProviderUpdateAndFinalThenIsDestroyed() {
        val cipherFactory = RetainingKeyCipherFactory()
        val crypto = BackupEnvelopeCrypto(
            entropy = CountingEntropy(),
            keyDeriver = RecordingKeyDeriver(),
            maximumPayloadBytes = BackupEnvelopeFormat.MAX_PAYLOAD_BYTES,
            cipherFactory = cipherFactory,
        )
        val payload = "TSPPAY01:provider-retains-key".toByteArray()

        val envelope = encrypt(crypto, payload)
        val restored = ByteArrayOutputStream()
        crypto.open(ByteArrayInputStream(envelope)).decryptToStagingAndWipePassphrase(
            restored,
            PASSPHRASE.toCharArray(),
        )

        assertArrayEquals(payload, restored.toByteArray())
        assertEquals(2, cipherFactory.sessions.size)
        cipherFactory.sessions.forEach { session ->
            assertTrue(session.liveKeyChecks > 0)
            assertTrue(session.retainedKey.isDestroyed)
            assertAllZero(session.retainedKey.encoded)
        }
    }

    private fun encrypt(
        crypto: BackupEnvelopeCrypto,
        payload: ByteArray,
        metadata: BackupEnvelopeMetadata = metadata(),
    ): ByteArray = ByteArrayOutputStream().also { output ->
        crypto.encryptAndWipePassphrase(
            ByteArrayInputStream(payload),
            payload.size.toLong(),
            output,
            PASSPHRASE.toCharArray(),
            metadata,
            BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
        )
    }.toByteArray()

    private fun metadata(
        mode: BackupMode = BackupMode.STANDARD,
        payloadSchemaVersion: Int = BackupEnvelopeFormat.CURRENT_PAYLOAD_SCHEMA_VERSION,
    ) = BackupEnvelopeMetadata(
        mode = mode,
        createdAtEpochMillis = 1_786_186_800_123,
        appVersion = BackupAppVersion(42, "1.2.3-test"),
        payloadSchemaVersion = payloadSchemaVersion,
        compatibility = BackupCompatibility(minimumSdk = 26, targetSdk = 37, capabilityBits = 5),
    )

    private fun offsets(envelope: ByteArray): EnvelopeOffsets {
        val headerLength = envelope.readU32At(8).toInt()
        val ciphertextLengthOffset = 12 + headerLength
        return EnvelopeOffsets(
            headerStart = 12,
            ciphertextLengthOffset = ciphertextLengthOffset,
            ciphertextStart = ciphertextLengthOffset + 8,
        )
    }

    private fun encryptedPayloadLength(envelope: ByteArray): Long {
        val offsets = offsets(envelope)
        return envelope.readU64At(offsets.ciphertextLengthOffset) - BackupEnvelopeFormat.GCM_TAG_BYTES
    }

    private fun mutateHeaderField(
        envelope: ByteArray,
        fieldId: Int,
        replacement: (ByteArray) -> ByteArray,
    ): ByteArray {
        val result = envelope.copyOf()
        val bounds = headerFieldBounds(result, fieldId)
        val old = result.copyOfRange(bounds.valueStart, bounds.valueEnd)
        val new = replacement(old)
        require(new.size == old.size)
        new.copyInto(result, bounds.valueStart)
        return result
    }

    private fun appendHeaderField(envelope: ByteArray, fieldId: Int, value: ByteArray): ByteArray {
        val offsets = offsets(envelope)
        val extra = ByteArray(6 + value.size)
        extra[0] = (fieldId ushr 8).toByte()
        extra[1] = fieldId.toByte()
        extra.writeU32At(2, value.size.toLong())
        value.copyInto(extra, 6)
        return ByteArrayOutputStream().also { output ->
            output.write(envelope, 0, 8)
            output.writeU32(envelope.readU32At(8) + extra.size)
            output.write(envelope, offsets.headerStart, offsets.ciphertextLengthOffset - offsets.headerStart)
            output.write(extra)
            output.write(envelope, offsets.ciphertextLengthOffset, envelope.size - offsets.ciphertextLengthOffset)
        }.toByteArray()
    }

    private fun headerFieldBounds(envelope: ByteArray, expectedId: Int): FieldBounds {
        val offsets = offsets(envelope)
        var position = offsets.headerStart
        while (position < offsets.ciphertextLengthOffset) {
            val id = ((envelope[position].toInt() and 0xff) shl 8) or
                (envelope[position + 1].toInt() and 0xff)
            val length = envelope.readU32At(position + 2).toInt()
            if (id == expectedId) return FieldBounds(position + 6, position + 6 + length)
            position += 6 + length
        }
        throw AssertionError("Missing field $expectedId")
    }

    private fun ByteArray.readU32At(offset: Int): Long =
        ((this[offset].toInt() and 0xff).toLong() shl 24) or
            ((this[offset + 1].toInt() and 0xff).toLong() shl 16) or
            ((this[offset + 2].toInt() and 0xff).toLong() shl 8) or
            (this[offset + 3].toInt() and 0xff).toLong()

    private fun ByteArray.readU64At(offset: Int): Long {
        var value = 0L
        repeat(8) { index -> value = (value shl 8) or (this[offset + index].toInt() and 0xff).toLong() }
        return value
    }

    private fun ByteArray.writeU32At(offset: Int, value: Long) {
        repeat(4) { index -> this[offset + index] = (value ushr (24 - index * 8)).toByte() }
    }

    private fun ByteArray.writeU64At(offset: Int, value: Long) {
        repeat(8) { index -> this[offset + index] = (value ushr (56 - index * 8)).toByte() }
    }

    private fun assertAllZero(bytes: ByteArray) {
        assertTrue("Expected mutable secret buffer to be wiped", bytes.all { it == 0.toByte() })
    }

    private fun assertAllZero(characters: CharArray) {
        assertTrue("Expected mutable passphrase to be wiped", characters.all { it == '\u0000' })
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private data class EnvelopeOffsets(
        val headerStart: Int,
        val ciphertextLengthOffset: Int,
        val ciphertextStart: Int,
    )

    private data class FieldBounds(val valueStart: Int, val valueEnd: Int)

    private class CountingEntropy(start: Int = 0) : BackupEntropySource {
        private var next = start

        override fun nextBytes(destination: ByteArray) {
            destination.indices.forEach { index -> destination[index] = (++next).toByte() }
        }
    }

    private class FragmentedInputStream(
        bytes: ByteArray,
        private val maximumChunk: Int,
    ) : ByteArrayInputStream(bytes) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(length, maximumChunk))

        override fun available(): Int = throw AssertionError("available() must not be used")

        override fun markSupported(): Boolean = false
    }

    private class RecordingKeyDeriver : BackupKeyDeriver {
        var lastDerived = ByteArray(0)

        override fun deriveAndWipePassphrase(
            passphrase: CharArray,
            salt: ByteArray,
            iterations: Int,
        ): ByteArray {
            passphrase.fill('\u0000')
            return ByteArray(BackupEnvelopeFormat.AES_KEY_BYTES) { (it + 1).toByte() }.also {
                lastDerived = it
            }
        }
    }

    private class InspectingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var lastDestination = ByteArray(0)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            lastDestination = buffer
            return super.read(buffer, offset, length)
        }
    }

    private class InspectingOutputStream : OutputStream() {
        private val written = ByteArrayOutputStream()
        val borrowedArrays = mutableListOf<ByteArray>()

        override fun write(value: Int) = written.write(value)

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            borrowedArrays += buffer
            written.write(buffer, offset, length)
        }

        fun copyWrittenBytes(): ByteArray = written.toByteArray()
    }

    private class RetainingKeyCipherFactory : BackupCipherFactory {
        val sessions = mutableListOf<RetainingKeyCipherSession>()

        override fun create(): BackupCipherSession = RetainingKeyCipherSession().also(sessions::add)
    }

    private class RetainingKeyCipherSession : BackupCipherSession {
        private val delegate = Cipher.getInstance("AES/GCM/NoPadding")
        lateinit var retainedKey: SecretKey
        var liveKeyChecks = 0

        override fun init(mode: Int, key: SecretKey, parameters: GCMParameterSpec) {
            retainedKey = key
            delegate.init(mode, key, parameters)
        }

        override fun updateAssociatedData(bytes: ByteArray) = delegate.updateAAD(bytes)

        override fun update(input: ByteArray, offset: Int, length: Int): ByteArray? {
            assertKeyIsLive()
            return delegate.update(input, offset, length)
        }

        override fun doFinal(): ByteArray {
            assertKeyIsLive()
            return delegate.doFinal()
        }

        private fun assertKeyIsLive() {
            assertFalse("Provider observed a destroyed key before doFinal", retainedKey.isDestroyed)
            assertTrue("Provider observed zero key bytes before doFinal", retainedKey.encoded.any { it != 0.toByte() })
            liveKeyChecks += 1
        }
    }

    companion object {
        private const val PASSPHRASE = "correct horse battery staple"
    }
}
