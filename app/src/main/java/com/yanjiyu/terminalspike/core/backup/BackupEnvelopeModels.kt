package com.yanjiyu.terminalspike.core.backup

/** Stable backup modes from the portable-backup wire format. */
enum class BackupMode(internal val wireValue: Int) {
    STANDARD(1),
    FULL(2),
    ;

    internal companion object {
        fun fromWireValue(value: Int): BackupMode? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Display-safe producing-app information included in every backup header. */
data class BackupAppVersion(
    val code: Long,
    val name: String,
) {
    init {
        require(code >= 0) { "App version code must be non-negative." }
        require(name.isNotEmpty()) { "App version name must not be empty." }
        require(name.utf8LengthOrThrow("App version name") <= BackupEnvelopeFormat.MAX_APP_VERSION_NAME_BYTES) {
            "App version name is too long."
        }
    }
}

/** Non-personal compatibility metadata permitted by envelope header field 11. */
data class BackupCompatibility(
    val minimumSdk: Int,
    val targetSdk: Int,
    val capabilityBits: Long,
) {
    init {
        require(minimumSdk > 0) { "Minimum SDK must be positive." }
        require(targetSdk >= minimumSdk) { "Target SDK must not be lower than minimum SDK." }
        require(capabilityBits >= 0) { "Capability bits must fit the supported unsigned range." }
    }
}

/** Authenticated, non-secret metadata supplied when creating an envelope. */
data class BackupEnvelopeMetadata(
    val mode: BackupMode,
    val createdAtEpochMillis: Long,
    val appVersion: BackupAppVersion,
    val payloadSchemaVersion: Int,
    val compatibility: BackupCompatibility,
) {
    init {
        require(createdAtEpochMillis >= 0) { "Creation time must be non-negative." }
        require(payloadSchemaVersion > 0) { "Payload schema version must be positive." }
    }
}

/** An unknown low-ID header field that can be skipped by this envelope version. */
class UnknownBackupHeaderField internal constructor(
    val id: Int,
    value: ByteArray,
) {
    private val storedValue = value.copyOf()

    val valueSize: Int
        get() = storedValue.size

    fun copyValue(): ByteArray = storedValue.copyOf()

    override fun equals(other: Any?): Boolean =
        other is UnknownBackupHeaderField && id == other.id && storedValue.contentEquals(other.storedValue)

    override fun hashCode(): Int = 31 * id + storedValue.contentHashCode()
}

/** Parsed envelope metadata. Salt and nonce are exposed only as defensive copies. */
class BackupEnvelopeHeader internal constructor(
    val envelopeSchemaVersion: Int,
    val mode: BackupMode,
    val createdAtEpochMillis: Long,
    val appVersion: BackupAppVersion,
    val payloadSchemaVersion: Int,
    val kdfIterations: Int,
    salt: ByteArray,
    nonce: ByteArray,
    val compatibility: BackupCompatibility,
    unknownOptionalFields: List<UnknownBackupHeaderField>,
) {
    private val storedSalt = salt.copyOf()
    private val storedNonce = nonce.copyOf()
    private val storedUnknownFields = unknownOptionalFields.toList()

    val unknownOptionalFields: List<UnknownBackupHeaderField>
        get() = storedUnknownFields

    fun copyKdfSalt(): ByteArray = storedSalt.copyOf()

    fun copyNonce(): ByteArray = storedNonce.copyOf()

    internal fun copySaltForCrypto(): ByteArray = storedSalt.copyOf()

    internal fun copyNonceForCrypto(): ByteArray = storedNonce.copyOf()
}

/** Fixed version-1 wire constants and reviewed resource bounds. */
object BackupEnvelopeFormat {
    const val ENVELOPE_SCHEMA_VERSION = 1
    const val CURRENT_PAYLOAD_SCHEMA_VERSION = 1

    // Calibrated values are clamped to this reviewed range before export and before import work.
    const val MIN_KDF_ITERATIONS = 100_000
    const val MAX_KDF_ITERATIONS = 2_000_000
    const val DEFAULT_KDF_ITERATIONS = 600_000

    const val KDF_SALT_BYTES = 32
    const val GCM_NONCE_BYTES = 12
    const val GCM_TAG_BYTES = 16
    const val AES_KEY_BYTES = 32

    const val MAX_HEADER_BYTES = 64 * 1024
    const val MAX_APP_VERSION_NAME_BYTES = 256
    const val MAX_PASSPHRASE_CHARS = 1_024
    const val STREAM_BUFFER_BYTES = 32 * 1024
    const val MAX_PAYLOAD_BYTES = 256L * 1024L * 1024L
}

enum class BackupEnvelopeSection {
    MAGIC,
    HEADER_LENGTH,
    HEADER,
    CIPHERTEXT_LENGTH,
    CIPHERTEXT,
}

enum class BackupMalformedReason {
    INVALID_MAGIC,
    NON_CANONICAL_HEADER,
    MISSING_REQUIRED_FIELD,
    INVALID_FIELD_LENGTH,
    INVALID_FIELD_VALUE,
    INVALID_UTF8,
    TRAILING_DATA,
}

enum class BackupLimit {
    HEADER_LENGTH,
    HEADER_FIELD_LENGTH,
    PAYLOAD_LENGTH,
    CIPHERTEXT_LENGTH,
    KDF_ITERATIONS,
    PASSPHRASE_LENGTH,
}

enum class BackupVersionKind {
    ENVELOPE,
    PAYLOAD,
}

enum class BackupUnsupportedReason {
    REQUIRED_HEADER_FIELD,
    KDF,
    AEAD,
}

/** Stable failures for callers to map to non-oracular import UI. */
sealed class BackupEnvelopeException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Truncated(
        val section: BackupEnvelopeSection,
    ) : BackupEnvelopeException("Backup is truncated in ${section.name.lowercase()}.")

    class Malformed(
        val reason: BackupMalformedReason,
    ) : BackupEnvelopeException("Backup envelope is malformed (${reason.name.lowercase()}).")

    class LimitExceeded(
        val limit: BackupLimit,
    ) : BackupEnvelopeException("Backup exceeds the ${limit.name.lowercase()} limit.")

    class UnsupportedVersion(
        val kind: BackupVersionKind,
        val version: Long,
        val producingApp: BackupAppVersion? = null,
    ) : BackupEnvelopeException(
        "Backup uses unsupported ${kind.name.lowercase()} schema version $version.",
    )

    class UnsupportedFormat(
        val reason: BackupUnsupportedReason,
        val fieldId: Int? = null,
        val producingApp: BackupAppVersion? = null,
    ) : BackupEnvelopeException("Backup uses an unsupported ${reason.name.lowercase()} value.")

    /** Wrong passphrase and authenticated header/ciphertext/tag changes deliberately share a type. */
    class UnlockFailed : BackupEnvelopeException("Could not unlock backup or the file was changed.")

    class PayloadLengthMismatch : BackupEnvelopeException(
        "Payload stream length did not match its declared length.",
    )

    class CryptographyUnavailable(cause: Throwable) : BackupEnvelopeException(
        "Required backup cryptography is unavailable.",
        cause,
    )
}
