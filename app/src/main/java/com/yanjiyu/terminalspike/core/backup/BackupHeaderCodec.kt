package com.yanjiyu.terminalspike.core.backup

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal object BackupHeaderCodec {
    val magicV1: ByteArray = "TSPBKP01".toByteArray(Charsets.US_ASCII)

    private const val FIELD_ENVELOPE_VERSION = 1
    private const val FIELD_MODE = 2
    private const val FIELD_CREATED_AT = 3
    private const val FIELD_APP_VERSION = 4
    private const val FIELD_PAYLOAD_VERSION = 5
    private const val FIELD_KDF = 6
    private const val FIELD_KDF_ITERATIONS = 7
    private const val FIELD_KDF_SALT = 8
    private const val FIELD_AEAD = 9
    private const val FIELD_NONCE = 10
    private const val FIELD_COMPATIBILITY = 11
    private const val LAST_KNOWN_FIELD = FIELD_COMPATIBILITY

    private const val APP_FIELD_CODE = 1
    private const val APP_FIELD_NAME = 2
    private const val COMPAT_FIELD_MIN_SDK = 1
    private const val COMPAT_FIELD_TARGET_SDK = 2
    private const val COMPAT_FIELD_CAPABILITIES = 3

    private const val KDF_PBKDF2_HMAC_SHA256 = 1
    private const val AEAD_AES_256_GCM = 1
    private const val REQUIRED_FIELD_MASK = 0x8000

    fun encode(
        metadata: BackupEnvelopeMetadata,
        kdfIterations: Int,
        salt: ByteArray,
        nonce: ByteArray,
        unknownOptionalFields: List<UnknownBackupHeaderField> = emptyList(),
    ): ByteArray {
        validateKdfIterations(kdfIterations)
        require(salt.size == BackupEnvelopeFormat.KDF_SALT_BYTES) { "KDF salt has the wrong size." }
        require(nonce.size == BackupEnvelopeFormat.GCM_NONCE_BYTES) { "GCM nonce has the wrong size." }
        require(unknownOptionalFields.all { field ->
            field.id in (LAST_KNOWN_FIELD + 1) until REQUIRED_FIELD_MASK
        }) { "Optional fields must use unknown low-bit field IDs." }
        require(unknownOptionalFields.map { it.id }.distinct().size == unknownOptionalFields.size) {
            "Optional field IDs must be unique."
        }

        val output = ByteArrayOutputStream()
        output.writeTlv(FIELD_ENVELOPE_VERSION, u32(BackupEnvelopeFormat.ENVELOPE_SCHEMA_VERSION))
        output.writeTlv(FIELD_MODE, byteArrayOf(metadata.mode.wireValue.toByte()))
        output.writeTlv(FIELD_CREATED_AT, u64(metadata.createdAtEpochMillis))
        output.writeTlv(FIELD_APP_VERSION, encodeAppVersion(metadata.appVersion))
        output.writeTlv(FIELD_PAYLOAD_VERSION, u32(metadata.payloadSchemaVersion))
        output.writeTlv(FIELD_KDF, byteArrayOf(KDF_PBKDF2_HMAC_SHA256.toByte()))
        output.writeTlv(FIELD_KDF_ITERATIONS, u32(kdfIterations))
        output.writeTlv(FIELD_KDF_SALT, salt)
        output.writeTlv(FIELD_AEAD, byteArrayOf(AEAD_AES_256_GCM.toByte()))
        output.writeTlv(FIELD_NONCE, nonce)
        output.writeTlv(FIELD_COMPATIBILITY, encodeCompatibility(metadata.compatibility))
        unknownOptionalFields.sortedBy { it.id }.forEach { field ->
            val value = field.copyValue()
            try {
                output.writeTlv(field.id, value)
            } finally {
                value.fill(0)
            }
        }
        return output.toByteArray().also { encoded ->
            require(encoded.size <= BackupEnvelopeFormat.MAX_HEADER_BYTES) { "Header is too large." }
        }
    }

    fun decode(bytes: ByteArray): BackupEnvelopeHeader {
        val cursor = ByteCursor(bytes)
        var previousId = 0
        var envelopeVersion: Long? = null
        var mode: BackupMode? = null
        var createdAt: Long? = null
        var appVersion: BackupAppVersion? = null
        var payloadVersion: Long? = null
        var kdf: Int? = null
        var iterations: Long? = null
        var salt: ByteArray? = null
        var aead: Int? = null
        var nonce: ByteArray? = null
        var compatibility: BackupCompatibility? = null
        val unknownFields = mutableListOf<UnknownBackupHeaderField>()

        while (cursor.remaining > 0) {
            if (cursor.remaining < TLV_PREFIX_BYTES) malformed(BackupMalformedReason.INVALID_FIELD_LENGTH)
            val fieldId = cursor.readU16()
            if (fieldId <= previousId) malformed(BackupMalformedReason.NON_CANONICAL_HEADER)
            previousId = fieldId
            val rawLength = cursor.readU32()
            if (rawLength > BackupEnvelopeFormat.MAX_HEADER_BYTES.toLong()) {
                throw BackupEnvelopeException.LimitExceeded(BackupLimit.HEADER_FIELD_LENGTH)
            }
            val length = rawLength.toInt()
            if (length > cursor.remaining) malformed(BackupMalformedReason.INVALID_FIELD_LENGTH)
            val value = cursor.readBytes(length)

            when (fieldId) {
                FIELD_ENVELOPE_VERSION -> envelopeVersion = value.readU32Field()
                FIELD_MODE -> mode = BackupMode.fromWireValue(value.readU8Field())
                    ?: malformed(BackupMalformedReason.INVALID_FIELD_VALUE)
                FIELD_CREATED_AT -> createdAt = value.readU64Field()
                FIELD_APP_VERSION -> appVersion = decodeAppVersion(value)
                FIELD_PAYLOAD_VERSION -> payloadVersion = value.readU32Field()
                FIELD_KDF -> kdf = value.readU8Field()
                FIELD_KDF_ITERATIONS -> iterations = value.readU32Field()
                FIELD_KDF_SALT -> {
                    if (value.size != BackupEnvelopeFormat.KDF_SALT_BYTES) {
                        malformed(BackupMalformedReason.INVALID_FIELD_LENGTH)
                    }
                    salt = value
                }
                FIELD_AEAD -> aead = value.readU8Field()
                FIELD_NONCE -> {
                    if (value.size != BackupEnvelopeFormat.GCM_NONCE_BYTES) {
                        malformed(BackupMalformedReason.INVALID_FIELD_LENGTH)
                    }
                    nonce = value
                }
                FIELD_COMPATIBILITY -> compatibility = decodeCompatibility(value)
                else -> {
                    if (fieldId and REQUIRED_FIELD_MASK != 0) {
                        throw BackupEnvelopeException.UnsupportedFormat(
                            reason = BackupUnsupportedReason.REQUIRED_HEADER_FIELD,
                            fieldId = fieldId,
                            producingApp = appVersion,
                        )
                    }
                    unknownFields += UnknownBackupHeaderField(fieldId, value)
                }
            }
        }

        val parsedAppVersion = appVersion ?: missingField()
        val parsedEnvelopeVersion = envelopeVersion ?: missingField()
        if (parsedEnvelopeVersion != BackupEnvelopeFormat.ENVELOPE_SCHEMA_VERSION.toLong()) {
            throw BackupEnvelopeException.UnsupportedVersion(
                kind = BackupVersionKind.ENVELOPE,
                version = parsedEnvelopeVersion,
                producingApp = parsedAppVersion,
            )
        }
        val parsedPayloadVersion = payloadVersion ?: missingField()
        if (parsedPayloadVersion > Int.MAX_VALUE) {
            throw BackupEnvelopeException.UnsupportedVersion(
                kind = BackupVersionKind.PAYLOAD,
                version = parsedPayloadVersion,
                producingApp = parsedAppVersion,
            )
        }
        if ((kdf ?: missingField()) != KDF_PBKDF2_HMAC_SHA256) {
            throw BackupEnvelopeException.UnsupportedFormat(
                reason = BackupUnsupportedReason.KDF,
                producingApp = parsedAppVersion,
            )
        }
        if ((aead ?: missingField()) != AEAD_AES_256_GCM) {
            throw BackupEnvelopeException.UnsupportedFormat(
                reason = BackupUnsupportedReason.AEAD,
                producingApp = parsedAppVersion,
            )
        }
        val parsedIterations = iterations ?: missingField()
        if (parsedIterations !in
            BackupEnvelopeFormat.MIN_KDF_ITERATIONS.toLong()..
            BackupEnvelopeFormat.MAX_KDF_ITERATIONS.toLong()
        ) {
            throw BackupEnvelopeException.LimitExceeded(BackupLimit.KDF_ITERATIONS)
        }

        return BackupEnvelopeHeader(
            envelopeSchemaVersion = parsedEnvelopeVersion.toInt(),
            mode = mode ?: missingField(),
            createdAtEpochMillis = createdAt ?: missingField(),
            appVersion = parsedAppVersion,
            payloadSchemaVersion = parsedPayloadVersion.toInt(),
            kdfIterations = parsedIterations.toInt(),
            salt = salt ?: missingField(),
            nonce = nonce ?: missingField(),
            compatibility = compatibility ?: missingField(),
            unknownOptionalFields = unknownFields,
        )
    }

    private fun encodeAppVersion(version: BackupAppVersion): ByteArray =
        ByteArrayOutputStream().also { output ->
            output.writeTlv(APP_FIELD_CODE, version.code.toString().toByteArray(Charsets.UTF_8))
            output.writeTlv(APP_FIELD_NAME, version.name.utf8BytesOrThrow("App version name"))
        }.toByteArray()

    private fun decodeAppVersion(bytes: ByteArray): BackupAppVersion {
        val fields = decodeNestedRequiredFields(bytes, setOf(APP_FIELD_CODE, APP_FIELD_NAME))
        val codeText = fields.getValue(APP_FIELD_CODE).decodeUtf8Strict()
        if (codeText.isEmpty() || (codeText.length > 1 && codeText.startsWith('0')) ||
            codeText.any { character -> character !in '0'..'9' }
        ) {
            malformed(BackupMalformedReason.INVALID_FIELD_VALUE)
        }
        val code = codeText.toLongOrNull() ?: malformed(BackupMalformedReason.INVALID_FIELD_VALUE)
        val nameBytes = fields.getValue(APP_FIELD_NAME)
        if (nameBytes.isEmpty() || nameBytes.size > BackupEnvelopeFormat.MAX_APP_VERSION_NAME_BYTES) {
            malformed(BackupMalformedReason.INVALID_FIELD_VALUE)
        }
        val name = nameBytes.decodeUtf8Strict()
        return try {
            BackupAppVersion(code, name)
        } catch (_: IllegalArgumentException) {
            malformed(BackupMalformedReason.INVALID_FIELD_VALUE)
        }
    }

    private fun encodeCompatibility(compatibility: BackupCompatibility): ByteArray =
        ByteArrayOutputStream().also { output ->
            output.writeTlv(COMPAT_FIELD_MIN_SDK, u32(compatibility.minimumSdk))
            output.writeTlv(COMPAT_FIELD_TARGET_SDK, u32(compatibility.targetSdk))
            output.writeTlv(COMPAT_FIELD_CAPABILITIES, u64(compatibility.capabilityBits))
        }.toByteArray()

    private fun decodeCompatibility(bytes: ByteArray): BackupCompatibility {
        val fields = decodeNestedRequiredFields(
            bytes,
            setOf(COMPAT_FIELD_MIN_SDK, COMPAT_FIELD_TARGET_SDK, COMPAT_FIELD_CAPABILITIES),
        )
        val minimumSdk = fields.getValue(COMPAT_FIELD_MIN_SDK).readU32Field()
        val targetSdk = fields.getValue(COMPAT_FIELD_TARGET_SDK).readU32Field()
        if (minimumSdk > Int.MAX_VALUE || targetSdk > Int.MAX_VALUE) {
            malformed(BackupMalformedReason.INVALID_FIELD_VALUE)
        }
        return try {
            BackupCompatibility(
                minimumSdk = minimumSdk.toInt(),
                targetSdk = targetSdk.toInt(),
                capabilityBits = fields.getValue(COMPAT_FIELD_CAPABILITIES).readU64Field(),
            )
        } catch (_: IllegalArgumentException) {
            malformed(BackupMalformedReason.INVALID_FIELD_VALUE)
        }
    }

    private fun decodeNestedRequiredFields(
        bytes: ByteArray,
        requiredIds: Set<Int>,
    ): Map<Int, ByteArray> {
        val cursor = ByteCursor(bytes)
        val fields = linkedMapOf<Int, ByteArray>()
        var previousId = 0
        while (cursor.remaining > 0) {
            if (cursor.remaining < TLV_PREFIX_BYTES) malformed(BackupMalformedReason.INVALID_FIELD_LENGTH)
            val fieldId = cursor.readU16()
            if (fieldId <= previousId) malformed(BackupMalformedReason.NON_CANONICAL_HEADER)
            previousId = fieldId
            val rawLength = cursor.readU32()
            if (rawLength > BackupEnvelopeFormat.MAX_HEADER_BYTES.toLong()) {
                throw BackupEnvelopeException.LimitExceeded(BackupLimit.HEADER_FIELD_LENGTH)
            }
            val length = rawLength.toInt()
            if (length > cursor.remaining) malformed(BackupMalformedReason.INVALID_FIELD_LENGTH)
            val value = cursor.readBytes(length)
            when {
                fieldId in requiredIds -> fields[fieldId] = value
                fieldId and REQUIRED_FIELD_MASK != 0 -> throw BackupEnvelopeException.UnsupportedFormat(
                    BackupUnsupportedReason.REQUIRED_HEADER_FIELD,
                    fieldId,
                )
                else -> Unit
            }
        }
        if (!fields.keys.containsAll(requiredIds)) missingField()
        return fields
    }

    private fun validateKdfIterations(iterations: Int) {
        if (iterations !in BackupEnvelopeFormat.MIN_KDF_ITERATIONS..BackupEnvelopeFormat.MAX_KDF_ITERATIONS) {
            throw BackupEnvelopeException.LimitExceeded(BackupLimit.KDF_ITERATIONS)
        }
    }

    private fun ByteArray.readU8Field(): Int {
        if (size != 1) malformed(BackupMalformedReason.INVALID_FIELD_LENGTH)
        return this[0].toInt() and 0xff
    }

    private fun ByteArray.readU32Field(): Long {
        if (size != U32_BYTES) malformed(BackupMalformedReason.INVALID_FIELD_LENGTH)
        return ByteCursor(this).readU32()
    }

    private fun ByteArray.readU64Field(): Long {
        if (size != U64_BYTES) malformed(BackupMalformedReason.INVALID_FIELD_LENGTH)
        return ByteCursor(this).readU64()
    }

    private fun ByteArray.decodeUtf8Strict(): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    } catch (_: CharacterCodingException) {
        malformed(BackupMalformedReason.INVALID_UTF8)
    }

    private fun ByteArrayOutputStream.writeTlv(id: Int, value: ByteArray) {
        require(id in 1..0xffff) { "TLV ID is out of range." }
        write((id ushr 8) and 0xff)
        write(id and 0xff)
        writeU32(value.size.toLong())
        write(value)
    }

    private fun u32(value: Int): ByteArray = ByteArrayOutputStream(U32_BYTES).also { output ->
        output.writeU32(value.toLong())
    }.toByteArray()

    private fun u64(value: Long): ByteArray = ByteArrayOutputStream(U64_BYTES).also { output ->
        output.writeU64(value)
    }.toByteArray()

    private fun missingField(): Nothing = malformed(BackupMalformedReason.MISSING_REQUIRED_FIELD)

    private fun malformed(reason: BackupMalformedReason): Nothing =
        throw BackupEnvelopeException.Malformed(reason)

    private class ByteCursor(private val bytes: ByteArray) {
        private var position = 0

        val remaining: Int
            get() = bytes.size - position

        fun readU16(): Int = (readByte() shl 8) or readByte()

        fun readU32(): Long =
            (readByte().toLong() shl 24) or
                (readByte().toLong() shl 16) or
                (readByte().toLong() shl 8) or
                readByte().toLong()

        fun readU64(): Long {
            if (remaining < U64_BYTES) malformed(BackupMalformedReason.INVALID_FIELD_LENGTH)
            if ((bytes[position].toInt() and 0x80) != 0) {
                malformed(BackupMalformedReason.INVALID_FIELD_VALUE)
            }
            var value = 0L
            repeat(U64_BYTES) { value = (value shl 8) or readByte().toLong() }
            return value
        }

        fun readBytes(length: Int): ByteArray {
            if (length < 0 || length > remaining) malformed(BackupMalformedReason.INVALID_FIELD_LENGTH)
            val result = bytes.copyOfRange(position, position + length)
            position += length
            return result
        }

        private fun readByte(): Int {
            if (remaining <= 0) malformed(BackupMalformedReason.INVALID_FIELD_LENGTH)
            return bytes[position++].toInt() and 0xff
        }
    }

    private const val U32_BYTES = 4
    private const val U64_BYTES = 8
    private const val TLV_PREFIX_BYTES = 2 + U32_BYTES
}

internal fun OutputStream.writeU32(value: Long) {
    require(value in 0..0xffff_ffffL) { "Value does not fit u32." }
    write(((value ushr 24) and 0xff).toInt())
    write(((value ushr 16) and 0xff).toInt())
    write(((value ushr 8) and 0xff).toInt())
    write((value and 0xff).toInt())
}

internal fun OutputStream.writeU64(value: Long) {
    require(value >= 0) { "Value does not fit the supported u64 range." }
    for (shift in 56 downTo 0 step 8) write(((value ushr shift) and 0xff).toInt())
}

internal fun String.utf8BytesOrThrow(fieldName: String): ByteArray = try {
    val encoded = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(this))
    ByteArray(encoded.remaining()).also(encoded::get)
} catch (error: CharacterCodingException) {
    throw IllegalArgumentException("$fieldName is not valid Unicode.", error)
}

internal fun String.utf8LengthOrThrow(fieldName: String): Int =
    utf8BytesOrThrow(fieldName).size
