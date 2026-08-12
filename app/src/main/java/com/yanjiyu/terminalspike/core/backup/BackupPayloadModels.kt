package com.yanjiyu.terminalspike.core.backup

import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.KnownHost
import com.yanjiyu.terminalspike.core.model.ModelLimits
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import com.yanjiyu.terminalspike.core.model.SshKeyIdentity
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import java.security.MessageDigest
import java.util.UUID

/** Mutable, explicitly owned portable secret material. It never exposes an immutable String. */
class PortableBackupSecret private constructor(bytes: ByteArray) {
    private var storage: ByteArray? = bytes

    val size: Int
        @Synchronized get() = storage?.size ?: 0

    val isWiped: Boolean
        @Synchronized get() = storage == null

    /** The borrowed array is valid only for the synchronous duration of [use]. */
    @Synchronized
    fun <T> withBytes(use: (ByteArray) -> T): T =
        use(storage ?: throw IllegalStateException("Portable backup secret has been wiped."))

    @Synchronized
    fun wipe() {
        storage?.fill(0)
        storage = null
    }

    override fun toString(): String = "PortableBackupSecret(size=$size, wiped=$isWiped)"

    companion object {
        /** Copies [source] into owned mutable storage and wipes [source] on every exit. */
        fun copyAndWipe(source: ByteArray): PortableBackupSecret = try {
            PortableBackupSecret(source.copyOf())
        } finally {
            source.fill(0)
        }
    }
}

data class BackupCredentialRecord(
    val metadata: SshCredential,
    val portableSecret: PortableBackupSecret? = null,
) {
    val secretReferenceId: String?
        get() = when (val authentication = metadata.authentication) {
            is SshAuthentication.Password -> authentication.secretReferenceId
            is SshAuthentication.PrivateKey -> authentication.passphraseSecretReferenceId
            is SshAuthentication.KeyboardInteractive -> authentication.reusableResponseSecretReferenceId
        }

    init {
        try {
            portableSecret?.let { secret ->
                require(secretReferenceId != null) { "A portable credential secret needs a secret reference ID." }
                require(secret.size in 1..BackupPayloadFormat.MAX_PASSWORD_OR_PASSPHRASE_BYTES) {
                    "Portable credential secret is outside the supported size range."
                }
            }
        } catch (error: Throwable) {
            portableSecret?.wipe()
            throw error
        }
    }
}

data class BackupSshKeyRecord(
    val metadata: SshKeyIdentity,
    val portablePrivateKey: PortableBackupSecret? = null,
) {
    init {
        try {
            portablePrivateKey?.let { secret ->
                require(secret.size in 1..BackupPayloadFormat.MAX_PRIVATE_KEY_BYTES) {
                    "Portable private key is outside the supported size range."
                }
            }
        } catch (error: Throwable) {
            portablePrivateKey?.wipe()
            throw error
        }
    }
}

/** Portable custom terminal theme record; no font bytes or file paths are represented. */
class BackupTerminalTheme(
    val id: String,
    val name: String,
    val foregroundArgb: Int,
    val backgroundArgb: Int,
    val cursorArgb: Int,
    val selectionArgb: Int,
    ansi16Argb: List<Int>,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val boldUsesBrightColours: Boolean = true,
) {
    val ansi16Argb: List<Int> = ansi16Argb.toList()

    init {
        requireCanonicalBackupUuid(id, "terminal theme ID")
        requireBoundedText(name, "terminal theme name", ModelLimits.MAX_DISPLAY_NAME_LENGTH)
        require(this.ansi16Argb.size == ANSI_COLOUR_COUNT) { "A terminal theme needs exactly 16 ANSI colours." }
        require(
            listOf(foregroundArgb, backgroundArgb, cursorArgb, selectionArgb).all(::isOpaque) &&
                this.ansi16Argb.all(::isOpaque),
        ) { "Terminal theme colours must be opaque ARGB values." }
        requireBackupTimestamp(createdAtEpochMillis, "terminal theme creation time")
        requireBackupTimestamp(updatedAtEpochMillis, "terminal theme update time")
        require(updatedAtEpochMillis >= createdAtEpochMillis) { "Terminal theme update time is out of order." }
    }

    override fun equals(other: Any?): Boolean = other is BackupTerminalTheme &&
        id == other.id &&
        name == other.name &&
        foregroundArgb == other.foregroundArgb &&
        backgroundArgb == other.backgroundArgb &&
        cursorArgb == other.cursorArgb &&
        selectionArgb == other.selectionArgb &&
        ansi16Argb == other.ansi16Argb &&
        boldUsesBrightColours == other.boldUsesBrightColours &&
        createdAtEpochMillis == other.createdAtEpochMillis &&
        updatedAtEpochMillis == other.updatedAtEpochMillis

    override fun hashCode(): Int = listOf(
        id,
        name,
        foregroundArgb,
        backgroundArgb,
        cursorArgb,
        selectionArgb,
        ansi16Argb,
        boldUsesBrightColours,
        createdAtEpochMillis,
        updatedAtEpochMillis,
    ).hashCode()

    companion object {
        const val ANSI_COLOUR_COUNT = 16

        private fun isOpaque(colour: Int): Boolean = colour ushr 24 == 0xff
    }
}

/**
 * One content-addressed imported terminal font selected explicitly for portable export.
 *
 * Font bytes are not credentials, but they are still bounded and copied into archive-owned
 * mutable storage so a caller cannot change them after validation. The record identifier is
 * derived from the SHA-256 font ID and therefore remains deterministic across devices.
 */
class BackupCustomFont private constructor(
    val recordId: String,
    val fontId: String,
    val displayName: String,
    private val content: ByteArray,
) {
    val byteCount: Int
        get() = content.size

    init {
        requireCanonicalBackupUuid(recordId, "custom font record ID")
        require(fontId.matches(CUSTOM_FONT_ID_REGEX)) { "Custom font ID is not content-addressed." }
        require(recordId == customFontBackupRecordId(fontId)) {
            "Custom font record ID does not match its content ID."
        }
        requireBoundedText(displayName, "custom font display name", MAX_DISPLAY_NAME_CHARACTERS)
        require(content.size in MIN_FONT_BYTES..BackupPayloadFormat.MAX_CUSTOM_FONT_BYTES) {
            "Custom font bytes are outside the supported size range."
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(content).toHex()
        require(fontId == "custom_$digest") { "Custom font bytes do not match their content ID." }
    }

    internal fun copyBytes(): ByteArray = content.copyOf()

    internal fun writeBytes(use: (ByteArray) -> Unit) = use(content)

    override fun equals(other: Any?): Boolean = other is BackupCustomFont &&
        recordId == other.recordId &&
        fontId == other.fontId &&
        displayName == other.displayName &&
        content.contentEquals(other.content)

    override fun hashCode(): Int = 31 * listOf(recordId, fontId, displayName).hashCode() + content.contentHashCode()

    override fun toString(): String =
        "BackupCustomFont(recordId=$recordId, fontId=$fontId, displayName=$displayName, byteCount=$byteCount)"

    companion object {
        private const val MAX_DISPLAY_NAME_CHARACTERS = 80
        private const val MIN_FONT_BYTES = 12
        private val CUSTOM_FONT_ID_REGEX = Regex("custom_[0-9a-f]{64}")

        fun copyOf(fontId: String, displayName: String, bytes: ByteArray): BackupCustomFont =
            BackupCustomFont(
                recordId = customFontBackupRecordId(fontId),
                fontId = fontId,
                displayName = displayName,
                content = bytes.copyOf(),
            )

        internal fun takeOwnership(
            recordId: String,
            fontId: String,
            displayName: String,
            bytes: ByteArray,
        ): BackupCustomFont = BackupCustomFont(recordId, fontId, displayName, bytes)
    }
}

internal fun customFontBackupRecordId(fontId: String): String {
    require(fontId.matches(Regex("custom_[0-9a-f]{64}"))) { "Custom font ID is not content-addressed." }
    val digest = fontId.removePrefix("custom_").chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
    // RFC 4122 variant + v5-shaped deterministic identifier. The name input is already SHA-256.
    digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
    digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
    val most = digest.copyOfRange(0, 8).fold(0L) { value, byte ->
        (value shl 8) or (byte.toLong() and 0xff)
    }
    val least = digest.copyOfRange(8, 16).fold(0L) { value, byte ->
        (value shl 8) or (byte.toLong() and 0xff)
    }
    return UUID(most, least).toString()
}

enum class BackupThemeMode(internal val wireValue: Int) {
    SYSTEM(1),
    LIGHT(2),
    DARK(3),
    ;

    internal companion object {
        fun fromWireValue(value: Int): BackupThemeMode? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class BackupAppLockMode(internal val wireValue: Int) {
    OFF(1),
    IMMEDIATE(2),
    DELAYED(3),
    ON_BACKGROUND(4),
    ;

    internal companion object {
        fun fromWireValue(value: Int): BackupAppLockMode? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Typed, non-secret global settings copied from DataStore by the later integration adapter. */
data class BackupGlobalSettings(
    val themeMode: BackupThemeMode = BackupThemeMode.SYSTEM,
    val dynamicColorEnabled: Boolean = true,
    val accentPreset: String? = null,
    val defaultTerminalProfileId: String? = null,
    val defaultKeyboardProfileId: String? = null,
    val keepaliveIntervalSeconds: Int = 0,
    val reconnectEnabled: Boolean = false,
    val reconnectMaxAttempts: Int = 0,
    val backgroundSessionsEnabled: Boolean = false,
    val notificationPrivacyEnabled: Boolean = true,
    val disconnectNotificationsEnabled: Boolean = false,
    val reconnectNotificationsEnabled: Boolean = false,
    val keepCpuAwake: Boolean = false,
    val keepScreenOnWhileTerminalVisible: Boolean = false,
    val appLockMode: BackupAppLockMode = BackupAppLockMode.OFF,
    val appLockDelaySeconds: Int = 0,
    val screenshotBlockingEnabled: Boolean = false,
    val sensitiveClipboardClearSeconds: Int = 0,
    val osc52Policy: RemoteClipboardMode = RemoteClipboardMode.ASK,
    val multilinePasteConfirmationEnabled: Boolean = true,
    val lastBackupMode: BackupMode? = null,
) {
    init {
        accentPreset?.let { requireBoundedIdentifier(it, "accent preset", MAX_SETTING_IDENTIFIER_BYTES) }
        defaultTerminalProfileId?.let { requireCanonicalBackupUuid(it, "default terminal profile ID") }
        defaultKeyboardProfileId?.let { requireCanonicalBackupUuid(it, "default keyboard profile ID") }
        require(
            keepaliveIntervalSeconds == 0 ||
                keepaliveIntervalSeconds in ModelLimits.MIN_KEEPALIVE_SECONDS..ModelLimits.MAX_KEEPALIVE_SECONDS,
        ) { "Global keepalive interval is outside the supported range." }
        require(reconnectMaxAttempts in 0..MAX_RECONNECT_ATTEMPTS) {
            "Reconnect attempt count is outside the supported range."
        }
        require(appLockDelaySeconds in 0..MAX_DELAY_SECONDS) { "App-lock delay is outside the supported range." }
        require(sensitiveClipboardClearSeconds in 0..MAX_DELAY_SECONDS) {
            "Clipboard-clear delay is outside the supported range."
        }
    }

    companion object {
        const val MAX_RECONNECT_ATTEMPTS = 100
        const val MAX_DELAY_SECONDS = 86_400
        const val MAX_SETTING_IDENTIFIER_BYTES = 128
    }
}

enum class BackupReferenceKind {
    HOST_CREDENTIAL,
    HOST_TERMINAL_PROFILE,
    HOST_KEYBOARD_PROFILE,
    CREDENTIAL_KEY_IDENTITY,
    TERMINAL_PROFILE_THEME,
    DEFAULT_TERMINAL_PROFILE,
    DEFAULT_KEYBOARD_PROFILE,
}

data class BackupUnresolvedReference(
    val ownerId: String,
    val targetId: String,
    val kind: BackupReferenceKind,
)

/**
 * One consistent repository snapshot. Deliberately absent: recent/live sessions, terminal content,
 * transcripts, sockets, logs, Mosh ephemeral keys, prompts, recovery keys, and Keystore keys.
 * Imported font bytes appear only when the user explicitly opts into that export collection.
 */
class BackupPayloadSnapshot(
    val mode: BackupMode,
    hostProfiles: List<HostProfile> = emptyList(),
    credentials: List<BackupCredentialRecord> = emptyList(),
    sshKeys: List<BackupSshKeyRecord> = emptyList(),
    knownHosts: List<KnownHost> = emptyList(),
    snippets: List<Snippet> = emptyList(),
    terminalProfiles: List<TerminalProfile> = emptyList(),
    terminalThemes: List<BackupTerminalTheme> = emptyList(),
    keyboardProfiles: List<KeyboardProfile> = emptyList(),
    customFonts: List<BackupCustomFont> = emptyList(),
    val globalSettings: BackupGlobalSettings = BackupGlobalSettings(),
) : AutoCloseable {
    val hostProfiles: List<HostProfile> = hostProfiles.map { it.copy() }
    val credentials: List<BackupCredentialRecord> = credentials.toList()
    val sshKeys: List<BackupSshKeyRecord> = sshKeys.toList()
    val knownHosts: List<KnownHost> = knownHosts.map { it.copy() }
    val snippets: List<Snippet> = snippets.map { it.copy() }
    val terminalProfiles: List<TerminalProfile> = terminalProfiles.map { it.copy() }
    val terminalThemes: List<BackupTerminalTheme> = terminalThemes.toList()
    val keyboardProfiles: List<KeyboardProfile> = keyboardProfiles.map { profile ->
        profile.copy(orderedActions = profile.orderedActions.toList())
    }
    val customFonts: List<BackupCustomFont> = customFonts.toList()

    val unresolvedReferences: List<BackupUnresolvedReference>

    init {
        try {
            validateCounts()
            validateSecretsForMode()
            validateUniqueRecordIds()
            validateUniqueKnownHostKeys()
            validateUniqueSecretReferences()
            unresolvedReferences = findUnresolvedReferences()
        } catch (error: Throwable) {
            wipeSecrets()
            throw error
        }
    }

    fun wipeSecrets() {
        credentials.forEach { it.portableSecret?.wipe() }
        sshKeys.forEach { it.portablePrivateKey?.wipe() }
    }

    override fun close() = wipeSecrets()

    internal fun requireExportable() {
        require(unresolvedReferences.isEmpty()) { "Export snapshot contains unresolved repository references." }
        credentials.forEach { record ->
            require(record.portableSecret?.isWiped != true) { "A credential secret was already wiped." }
        }
        sshKeys.forEach { record ->
            require(record.portablePrivateKey?.isWiped != true) { "A private key was already wiped." }
        }
    }

    private fun validateCounts() {
        requireCount(hostProfiles.size, BackupPayloadFormat.MAX_HOST_PROFILES, "host profiles")
        requireCount(credentials.size, BackupPayloadFormat.MAX_CREDENTIALS, "credentials")
        requireCount(sshKeys.size, BackupPayloadFormat.MAX_SSH_KEYS, "SSH keys")
        requireCount(knownHosts.size, BackupPayloadFormat.MAX_KNOWN_HOSTS, "known hosts")
        requireCount(snippets.size, BackupPayloadFormat.MAX_SNIPPETS, "snippets")
        requireCount(terminalProfiles.size, BackupPayloadFormat.MAX_TERMINAL_PROFILES, "terminal profiles")
        requireCount(terminalThemes.size, BackupPayloadFormat.MAX_TERMINAL_THEMES, "terminal themes")
        requireCount(keyboardProfiles.size, BackupPayloadFormat.MAX_KEYBOARD_PROFILES, "keyboard profiles")
        requireCount(customFonts.size, BackupPayloadFormat.MAX_CUSTOM_FONTS, "custom fonts")
    }

    private fun validateSecretsForMode() {
        if (mode == BackupMode.STANDARD) {
            require(credentials.none { it.portableSecret != null }) {
                "Standard backups cannot contain credential secret payloads."
            }
            require(sshKeys.none { it.portablePrivateKey != null }) {
                "Standard backups cannot contain private-key payloads."
            }
        }
    }

    private fun validateUniqueRecordIds() {
        val allIds = buildList {
            addAll(hostProfiles.map { it.id })
            addAll(credentials.map { it.metadata.id })
            addAll(sshKeys.map { it.metadata.id })
            addAll(knownHosts.map { it.id })
            addAll(snippets.map { it.id })
            addAll(terminalProfiles.map { it.id })
            addAll(terminalThemes.map { it.id })
            addAll(keyboardProfiles.map { it.id })
            addAll(customFonts.map { it.recordId })
            add(BackupPayloadFormat.GLOBAL_SETTINGS_RECORD_ID)
        }
        require(allIds.size == allIds.distinct().size) { "Backup record IDs must be globally unique." }
        require(customFonts.map { it.fontId }.distinct().size == customFonts.size) {
            "Custom font content IDs must be unique."
        }
    }

    private fun validateUniqueSecretReferences() {
        val references = buildList {
            addAll(credentials.mapNotNull { it.secretReferenceId })
            addAll(sshKeys.map { it.metadata.privateKeySecretReferenceId })
        }
        require(references.size == references.distinct().size) {
            "Portable secret reference IDs must be unique."
        }
    }

    private fun validateUniqueKnownHostKeys() {
        val keys = knownHosts.map { knownHost ->
            Triple(knownHost.host, knownHost.port, knownHost.keyAlgorithm)
        }
        require(keys.size == keys.distinct().size) {
            "Known hosts must be unique by host, port, and key algorithm."
        }
    }

    private fun findUnresolvedReferences(): List<BackupUnresolvedReference> {
        val credentialIds = credentials.mapTo(hashSetOf()) { it.metadata.id }
        val keyIds = sshKeys.mapTo(hashSetOf()) { it.metadata.id }
        val terminalIds = terminalProfiles.mapTo(hashSetOf()) { it.id }
        val terminalThemeIds = terminalThemes.mapTo(hashSetOf()) { it.id }
        val keyboardIds = keyboardProfiles.mapTo(hashSetOf()) { it.id }
        return buildList {
            hostProfiles.forEach { host ->
                host.credentialId?.takeUnless(credentialIds::contains)?.let { target ->
                    add(BackupUnresolvedReference(host.id, target, BackupReferenceKind.HOST_CREDENTIAL))
                }
                host.terminalProfileId?.takeUnless(terminalIds::contains)?.let { target ->
                    add(BackupUnresolvedReference(host.id, target, BackupReferenceKind.HOST_TERMINAL_PROFILE))
                }
                host.keyboardProfileId?.takeUnless(keyboardIds::contains)?.let { target ->
                    add(BackupUnresolvedReference(host.id, target, BackupReferenceKind.HOST_KEYBOARD_PROFILE))
                }
            }
            credentials.forEach { record ->
                val authentication = record.metadata.authentication
                if (authentication is SshAuthentication.PrivateKey && authentication.keyIdentityId !in keyIds) {
                    add(
                        BackupUnresolvedReference(
                            record.metadata.id,
                            authentication.keyIdentityId,
                            BackupReferenceKind.CREDENTIAL_KEY_IDENTITY,
                        ),
                    )
                }
            }
            terminalProfiles.forEach { profile ->
                if (isCanonicalUuid(profile.themeId) && profile.themeId !in terminalThemeIds) {
                    add(
                        BackupUnresolvedReference(
                            profile.id,
                            profile.themeId,
                            BackupReferenceKind.TERMINAL_PROFILE_THEME,
                        ),
                    )
                }
            }
            globalSettings.defaultTerminalProfileId?.takeUnless(terminalIds::contains)?.let { target ->
                add(
                    BackupUnresolvedReference(
                        BackupPayloadFormat.GLOBAL_SETTINGS_RECORD_ID,
                        target,
                        BackupReferenceKind.DEFAULT_TERMINAL_PROFILE,
                    ),
                )
            }
            globalSettings.defaultKeyboardProfileId?.takeUnless(keyboardIds::contains)?.let { target ->
                add(
                    BackupUnresolvedReference(
                        BackupPayloadFormat.GLOBAL_SETTINGS_RECORD_ID,
                        target,
                        BackupReferenceKind.DEFAULT_KEYBOARD_PROFILE,
                    ),
                )
            }
        }
    }

    private fun requireCount(actual: Int, maximum: Int, label: String) {
        require(actual <= maximum) { "Too many $label in backup snapshot." }
    }
}

private fun isCanonicalUuid(value: String): Boolean =
    value.length == 36 && runCatching { UUID.fromString(value).toString() }.getOrNull() == value

object BackupPayloadFormat {
    const val SCHEMA_VERSION = 1
    const val MAX_PAYLOAD_BYTES = BackupEnvelopeFormat.MAX_PAYLOAD_BYTES
    const val MAX_CUSTOM_FONT_BYTES = 16 * 1024 * 1024
    const val MAX_RECORD_BYTES = MAX_CUSTOM_FONT_BYTES + 512 * 1024
    const val MAX_STRING_BYTES = 256 * 1024
    const val MAX_PASSWORD_OR_PASSPHRASE_BYTES = 4 * 1024
    const val MAX_PRIVATE_KEY_BYTES = 256 * 1024

    const val MAX_HOST_PROFILES = 10_000
    const val MAX_CREDENTIALS = 10_000
    const val MAX_SSH_KEYS = 2_000
    const val MAX_KNOWN_HOSTS = 50_000
    const val MAX_SNIPPETS = 10_000
    const val MAX_TERMINAL_PROFILES = 1_000
    const val MAX_TERMINAL_THEMES = 1_000
    const val MAX_KEYBOARD_PROFILES = 1_000
    const val MAX_CUSTOM_FONTS = 32
    const val MAX_RECORDS_PER_UNKNOWN_COLLECTION = 50_000

    const val GLOBAL_SETTINGS_RECORD_ID = "00000000-0000-0000-0000-000000000001"
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

enum class BackupPayloadSection {
    MAGIC,
    COLLECTION_HEADER,
    COLLECTION_BODY,
    RECORD_HEADER,
    RECORD_BODY,
}

enum class BackupPayloadMalformedReason {
    INVALID_MAGIC,
    NON_CANONICAL_COLLECTIONS,
    NON_CANONICAL_FIELDS,
    MISSING_COLLECTION,
    MISSING_REQUIRED_FIELD,
    INVALID_LENGTH,
    INVALID_VALUE,
    INVALID_UTF8,
    DUPLICATE_RECORD_ID,
    DUPLICATE_SECRET_REFERENCE,
    INVALID_REFERENCE,
    MODE_CONTENT_MISMATCH,
}

enum class BackupPayloadLimit {
    TOTAL_BYTES,
    COLLECTION_BYTES,
    RECORD_COUNT,
    RECORD_BYTES,
    FIELD_BYTES,
    STRING_BYTES,
    SECRET_BYTES,
}

enum class BackupIncompatibleReason {
    NEWER_RECORD_SCHEMA,
    UNKNOWN_REQUIRED_FIELD,
    UNKNOWN_REQUIRED_RECORD_TYPE,
}

data class IncompatibleBackupRecord(
    val collectionId: Int,
    val recordType: Int,
    val recordId: String,
    val reason: BackupIncompatibleReason,
)

data class BackupPayloadCompatibilityReport(
    val incompatibleRecords: List<IncompatibleBackupRecord> = emptyList(),
    val skippedUnknownCollections: Int = 0,
    val skippedUnknownRecordTypes: Int = 0,
    val skippedUnknownRecords: Int = 0,
    val skippedOptionalFields: Int = 0,
    val unresolvedReferences: List<BackupUnresolvedReference> = emptyList(),
)

data class BackupPayloadReadResult(
    val snapshot: BackupPayloadSnapshot,
    val compatibility: BackupPayloadCompatibilityReport,
)

sealed class BackupPayloadException(message: String) : Exception(message) {
    class Truncated(val section: BackupPayloadSection) : BackupPayloadException(
        "Backup payload is truncated in ${section.name.lowercase()}.",
    )

    class Malformed(
        val reason: BackupPayloadMalformedReason,
        val recordId: String? = null,
    ) : BackupPayloadException("Backup payload is malformed (${reason.name.lowercase()}).")

    class LimitExceeded(val limit: BackupPayloadLimit) : BackupPayloadException(
        "Backup payload exceeds the ${limit.name.lowercase()} limit.",
    )

    class UnsupportedVersion(val version: Long) : BackupPayloadException(
        "Backup payload schema version $version is unsupported.",
    )

    class UnsupportedRequiredCollection(val collectionId: Int) : BackupPayloadException(
        "Backup payload requires unsupported collection $collectionId.",
    )
}

internal fun requireCanonicalBackupUuid(value: String, fieldName: String) {
    require(value.length == 36 && runCatching { UUID.fromString(value).toString() }.getOrNull() == value) {
        "$fieldName must be a canonical lowercase UUID."
    }
}

private fun requireBackupTimestamp(value: Long, fieldName: String) {
    require(value in 0..ModelLimits.MAX_EPOCH_MILLIS) { "$fieldName is outside the supported range." }
}

private fun requireBoundedText(value: String, fieldName: String, maximumCharacters: Int) {
    require(value.isNotEmpty() && value.length <= maximumCharacters && value == value.trim()) {
        "$fieldName is outside the supported range."
    }
    require(value.none(Char::isISOControl)) { "$fieldName contains control characters." }
    value.utf8BytesOrThrow(fieldName)
}

private fun requireBoundedIdentifier(value: String, fieldName: String, maximumBytes: Int) {
    require(value.isNotEmpty() && value.utf8LengthOrThrow(fieldName) <= maximumBytes) {
        "$fieldName is outside the supported range."
    }
    require(value.all { it.isLetterOrDigit() || it in "._:+-@" }) { "$fieldName contains invalid characters." }
}
