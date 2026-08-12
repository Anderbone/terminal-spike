package com.yanjiyu.terminalspike.core.backup

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupHeaderCodecTest {
    @Test
    fun headerUsesCanonicalAscendingRequiredFieldsAndNestedUtf8AppVersion() {
        val salt = ByteArray(BackupEnvelopeFormat.KDF_SALT_BYTES) { (it + 1).toByte() }
        val nonce = ByteArray(BackupEnvelopeFormat.GCM_NONCE_BYTES) { (it + 51).toByte() }

        val encoded = BackupHeaderCodec.encode(
            metadata(),
            BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
            salt,
            nonce,
        )
        val fields = fields(encoded)

        assertEquals((1..11).toList(), fields.map { it.id })
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), fields.single { it.id == 1 }.value)
        assertArrayEquals(byteArrayOf(2), fields.single { it.id == 2 }.value)
        assertArrayEquals(byteArrayOf(1), fields.single { it.id == 6 }.value)
        assertArrayEquals(byteArrayOf(1), fields.single { it.id == 9 }.value)
        assertArrayEquals(salt, fields.single { it.id == 8 }.value)
        assertArrayEquals(nonce, fields.single { it.id == 10 }.value)

        val appFields = fields(fields.single { it.id == 4 }.value)
        assertEquals(listOf(1, 2), appFields.map { it.id })
        assertArrayEquals("123".toByteArray(Charsets.UTF_8), appFields[0].value)
        assertArrayEquals("2.4.6-β".toByteArray(Charsets.UTF_8), appFields[1].value)

        val compatibilityFields = fields(fields.single { it.id == 11 }.value)
        assertEquals(listOf(1, 2, 3), compatibilityFields.map { it.id })
        assertArrayEquals(byteArrayOf(0, 0, 0, 26), compatibilityFields[0].value)
        assertArrayEquals(byteArrayOf(0, 0, 0, 37), compatibilityFields[1].value)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 9), compatibilityFields[2].value)
    }

    @Test
    fun decodeRoundTripRetainsOptionalFieldsAndDefensivelyCopiesArrays() {
        val salt = ByteArray(BackupEnvelopeFormat.KDF_SALT_BYTES) { (it + 1).toByte() }
        val nonce = ByteArray(BackupEnvelopeFormat.GCM_NONCE_BYTES) { (it + 71).toByte() }
        val optionalValue = byteArrayOf(7, 8, 9)
        val encoded = BackupHeaderCodec.encode(
            metadata(),
            BackupEnvelopeFormat.DEFAULT_KDF_ITERATIONS,
            salt,
            nonce,
            listOf(UnknownBackupHeaderField(12, optionalValue)),
        )

        salt.fill(0)
        nonce.fill(0)
        optionalValue.fill(0)
        val decoded = BackupHeaderCodec.decode(encoded)

        assertEquals(BackupEnvelopeFormat.ENVELOPE_SCHEMA_VERSION, decoded.envelopeSchemaVersion)
        assertEquals(BackupMode.FULL, decoded.mode)
        assertEquals(metadata().createdAtEpochMillis, decoded.createdAtEpochMillis)
        assertEquals(metadata().appVersion, decoded.appVersion)
        assertEquals(metadata().payloadSchemaVersion, decoded.payloadSchemaVersion)
        assertEquals(metadata().compatibility, decoded.compatibility)
        assertEquals(BackupEnvelopeFormat.DEFAULT_KDF_ITERATIONS, decoded.kdfIterations)
        assertArrayEquals(ByteArray(32) { (it + 1).toByte() }, decoded.copyKdfSalt())
        assertArrayEquals(ByteArray(12) { (it + 71).toByte() }, decoded.copyNonce())
        assertEquals(12, decoded.unknownOptionalFields.single().id)
        assertArrayEquals(byteArrayOf(7, 8, 9), decoded.unknownOptionalFields.single().copyValue())

        decoded.copyKdfSalt().fill(0)
        decoded.copyNonce().fill(0)
        decoded.unknownOptionalFields.single().copyValue().fill(0)
        assertTrue(decoded.copyKdfSalt().any { it != 0.toByte() })
        assertTrue(decoded.copyNonce().any { it != 0.toByte() })
        assertArrayEquals(byteArrayOf(7, 8, 9), decoded.unknownOptionalFields.single().copyValue())
    }

    @Test
    fun duplicateOutOfOrderMissingAndUnknownRequiredFieldsAreRejected() {
        val valid = encodedHeader()
        val duplicateLastField = valid + tlv(11, fields(valid).last().value)
        assertEquals(
            BackupMalformedReason.NON_CANONICAL_HEADER,
            assertThrows(BackupEnvelopeException.Malformed::class.java) {
                BackupHeaderCodec.decode(duplicateLastField)
            }.reason,
        )

        val outOfOrder = fields(valid).let { parsed ->
            ByteArrayOutputStream().also { output ->
                parsed[1].writeTo(output)
                parsed[0].writeTo(output)
                parsed.drop(2).forEach { it.writeTo(output) }
            }.toByteArray()
        }
        assertEquals(
            BackupMalformedReason.NON_CANONICAL_HEADER,
            assertThrows(BackupEnvelopeException.Malformed::class.java) {
                BackupHeaderCodec.decode(outOfOrder)
            }.reason,
        )

        val missingCompatibility = fields(valid).dropLast(1).joinTlvs()
        assertEquals(
            BackupMalformedReason.MISSING_REQUIRED_FIELD,
            assertThrows(BackupEnvelopeException.Malformed::class.java) {
                BackupHeaderCodec.decode(missingCompatibility)
            }.reason,
        )

        val unknownRequired = valid + tlv(0x800c, byteArrayOf(1))
        val unsupported = assertThrows(BackupEnvelopeException.UnsupportedFormat::class.java) {
            BackupHeaderCodec.decode(unknownRequired)
        }
        assertEquals(BackupUnsupportedReason.REQUIRED_HEADER_FIELD, unsupported.reason)
        assertEquals(0x800c, unsupported.fieldId)
        assertEquals(metadata().appVersion, unsupported.producingApp)
    }

    @Test
    fun malformedLengthsValuesAndUtf8AreRejectedWithoutReplacementCharacters() {
        val valid = encodedHeader()
        val impossibleLength = valid.copyOf().apply {
            // First field has four value bytes; claim five without adding a byte.
            writeU32At(2, 5)
        }
        assertThrows(BackupEnvelopeException.Malformed::class.java) {
            BackupHeaderCodec.decode(impossibleLength)
        }

        val invalidSalt = replaceField(valid, 8, ByteArray(31))
        assertEquals(
            BackupMalformedReason.INVALID_FIELD_LENGTH,
            assertThrows(BackupEnvelopeException.Malformed::class.java) {
                BackupHeaderCodec.decode(invalidSalt)
            }.reason,
        )

        val invalidMode = replaceField(valid, 2, byteArrayOf(3))
        assertEquals(
            BackupMalformedReason.INVALID_FIELD_VALUE,
            assertThrows(BackupEnvelopeException.Malformed::class.java) {
                BackupHeaderCodec.decode(invalidMode)
            }.reason,
        )

        val invalidUtf8App = replaceField(valid, 4) { nested ->
            val nestedFields = fields(nested).toMutableList()
            val name = nestedFields[1].value.copyOf()
            name[0] = 0xc3.toByte()
            name[1] = 0x28
            nestedFields[1] = nestedFields[1].copy(value = name)
            nestedFields.joinTlvs()
        }
        assertEquals(
            BackupMalformedReason.INVALID_UTF8,
            assertThrows(BackupEnvelopeException.Malformed::class.java) {
                BackupHeaderCodec.decode(invalidUtf8App)
            }.reason,
        )

        val negativePlatformU64 = replaceField(valid, 3, ByteArray(8).apply { this[0] = 0x80.toByte() })
        assertEquals(
            BackupMalformedReason.INVALID_FIELD_VALUE,
            assertThrows(BackupEnvelopeException.Malformed::class.java) {
                BackupHeaderCodec.decode(negativePlatformU64)
            }.reason,
        )
    }

    @Test
    fun modelValidationBoundsUtf8AndMetadataBeforeEncoding() {
        assertThrows(IllegalArgumentException::class.java) { BackupAppVersion(1, "") }
        assertThrows(IllegalArgumentException::class.java) {
            BackupAppVersion(1, "x".repeat(BackupEnvelopeFormat.MAX_APP_VERSION_NAME_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupAppVersion(1, charArrayOf('\ud800').concatToString())
        }
        assertThrows(IllegalArgumentException::class.java) { BackupCompatibility(0, 37, 0) }
        assertThrows(IllegalArgumentException::class.java) { BackupCompatibility(37, 26, 0) }
        assertThrows(IllegalArgumentException::class.java) { BackupCompatibility(26, 37, -1) }
        assertThrows(IllegalArgumentException::class.java) {
            BackupEnvelopeMetadata(BackupMode.STANDARD, -1, BackupAppVersion(1, "1"), 1, compatibility())
        }
    }

    private fun encodedHeader(): ByteArray = BackupHeaderCodec.encode(
        metadata(),
        BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
        ByteArray(BackupEnvelopeFormat.KDF_SALT_BYTES) { (it + 1).toByte() },
        ByteArray(BackupEnvelopeFormat.GCM_NONCE_BYTES) { (it + 51).toByte() },
    )

    private fun metadata() = BackupEnvelopeMetadata(
        mode = BackupMode.FULL,
        createdAtEpochMillis = 1_786_186_800_123,
        appVersion = BackupAppVersion(123, "2.4.6-β"),
        payloadSchemaVersion = 1,
        compatibility = compatibility(),
    )

    private fun compatibility() = BackupCompatibility(26, 37, 9)

    private fun fields(bytes: ByteArray): List<Tlv> {
        val result = mutableListOf<Tlv>()
        var position = 0
        while (position < bytes.size) {
            val id = ((bytes[position].toInt() and 0xff) shl 8) or
                (bytes[position + 1].toInt() and 0xff)
            val length = bytes.readU32At(position + 2)
            result += Tlv(id, bytes.copyOfRange(position + 6, position + 6 + length))
            position += 6 + length
        }
        return result
    }

    private fun replaceField(header: ByteArray, id: Int, value: ByteArray): ByteArray =
        replaceField(header, id) { value }

    private fun replaceField(
        header: ByteArray,
        id: Int,
        transform: (ByteArray) -> ByteArray,
    ): ByteArray = fields(header).map { field ->
        if (field.id == id) field.copy(value = transform(field.value)) else field
    }.joinTlvs()

    private fun List<Tlv>.joinTlvs(): ByteArray = ByteArrayOutputStream().also { output ->
        forEach { it.writeTo(output) }
    }.toByteArray()

    private fun Tlv.writeTo(output: ByteArrayOutputStream) {
        output.write(tlv(id, value))
    }

    private fun tlv(id: Int, value: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        output.write((id ushr 8) and 0xff)
        output.write(id and 0xff)
        output.writeU32(value.size.toLong())
        output.write(value)
    }.toByteArray()

    private fun ByteArray.readU32At(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)

    private fun ByteArray.writeU32At(offset: Int, value: Int) {
        repeat(4) { index -> this[offset + index] = (value ushr (24 - index * 8)).toByte() }
    }

    private data class Tlv(val id: Int, val value: ByteArray)
}
