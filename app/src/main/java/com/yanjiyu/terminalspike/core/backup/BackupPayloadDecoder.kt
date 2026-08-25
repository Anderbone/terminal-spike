package com.yanjiyu.terminalspike.core.backup

import com.yanjiyu.terminalspike.core.model.BellSettings
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.KnownHost
import com.yanjiyu.terminalspike.core.model.LinkBehavior
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.core.model.ReconnectPolicy
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.core.model.ScrollBehavior
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import com.yanjiyu.terminalspike.core.model.SshCredentialKind
import com.yanjiyu.terminalspike.core.model.SshKeyIdentity
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.core.model.TouchScrollMode
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal object BackupPayloadDecoder {
    fun read(input: InputStream, expectedMode: BackupMode, maximumBytes: Long): BackupPayloadReadResult {
        val reader = PayloadInput(input, maximumBytes)
        val magic = reader.readExact(
            BackupPayloadWire.MAGIC_V1.size,
            BackupPayloadSection.MAGIC,
        )
        if (!magic.contentEquals(BackupPayloadWire.MAGIC_V1)) {
            parseMagicVersion(magic)?.let { throw BackupPayloadException.UnsupportedVersion(it) }
            throw malformed(BackupPayloadMalformedReason.INVALID_MAGIC)
        }

        val state = DecoderState(expectedMode)
        var completed = false
        try {
            var previousCollectionId = 0
            val seenCollections = mutableSetOf<Int>()
            while (true) {
                val high = reader.readByteOrEof() ?: break
                val low = reader.readByte(BackupPayloadSection.COLLECTION_HEADER)
                val collectionId = (high shl 8) or low
                if (collectionId <= previousCollectionId) {
                    throw malformed(BackupPayloadMalformedReason.NON_CANONICAL_COLLECTIONS)
                }
                previousCollectionId = collectionId
                val rawLength = reader.readU32(BackupPayloadSection.COLLECTION_HEADER)
                if (rawLength > maximumBytes) {
                    throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.COLLECTION_BYTES)
                }
                val section = PayloadSectionInput(reader, rawLength)
                if (collectionId in BackupPayloadWire.KNOWN_COLLECTIONS) {
                    if (collectionId in BackupPayloadWire.REQUIRED_COLLECTIONS) {
                        seenCollections += collectionId
                    }
                    state.readKnownCollection(collectionId, section)
                } else {
                    if (collectionId and BackupPayloadWire.REQUIRED_ID_MASK != 0) {
                        throw BackupPayloadException.UnsupportedRequiredCollection(collectionId)
                    }
                    state.readUnknownCollection(collectionId, section)
                }
                section.requireExhausted()
            }
            if (!seenCollections.containsAll(BackupPayloadWire.REQUIRED_COLLECTIONS)) {
                throw malformed(BackupPayloadMalformedReason.MISSING_COLLECTION)
            }
            val result = state.finish()
            completed = true
            return result
        } finally {
            if (!completed) state.wipeSecrets()
        }
    }

    private fun parseMagicVersion(magic: ByteArray): Long? {
        val prefix = "TSPPAY".toByteArray(Charsets.US_ASCII)
        if (magic.size != prefix.size + 2 || !magic.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            return null
        }
        val tens = magic[prefix.size].toInt() - '0'.code
        val ones = magic[prefix.size + 1].toInt() - '0'.code
        return if (tens in 0..9 && ones in 0..9) tens * 10L + ones else null
    }
}

private class DecoderState(private val expectedMode: BackupMode) {
    private val hostProfiles = mutableListOf<HostProfile>()
    private val credentials = mutableListOf<BackupCredentialRecord>()
    private val sshKeys = mutableListOf<BackupSshKeyRecord>()
    private val knownHosts = mutableListOf<KnownHost>()
    private val snippets = mutableListOf<Snippet>()
    private val terminalProfiles = mutableListOf<TerminalProfile>()
    private val terminalThemes = mutableListOf<BackupTerminalTheme>()
    private val keyboardProfiles = mutableListOf<KeyboardProfile>()
    private val customFonts = mutableListOf<BackupCustomFont>()
    private var globalSettings = BackupGlobalSettings()
    private var decodedGlobalSettings = false

    private val recordIds = mutableSetOf<String>()
    private val secretReferenceIds = mutableSetOf<String>()
    private val incompatible = mutableListOf<IncompatibleBackupRecord>()
    private var skippedUnknownCollections = 0
    private var skippedUnknownRecordTypes = 0
    private var skippedUnknownRecords = 0
    private var skippedOptionalFields = 0

    fun readKnownCollection(collectionId: Int, input: PayloadSectionInput) {
        val count = input.readCount(maximumCount(collectionId))
        if (collectionId == BackupPayloadWire.COLLECTION_GLOBAL_SETTINGS && count != 1) {
            throw malformed(BackupPayloadMalformedReason.MISSING_REQUIRED_FIELD)
        }
        repeat(count) {
            val type = input.readU16(BackupPayloadSection.RECORD_HEADER)
            val bodyLength = input.readRecordLength()
            val body = input.readBytes(bodyLength, BackupPayloadSection.RECORD_BODY)
            try {
                when {
                    collectionId == BackupPayloadWire.COLLECTION_TERMINAL &&
                        type == BackupPayloadWire.RECORD_PRIMARY -> decodeKnown(
                        collectionId,
                        type,
                        body,
                        TERMINAL_FIELDS,
                        ::decodeTerminalProfile,
                    )?.let { profile ->
                        if (terminalProfiles.size == BackupPayloadFormat.MAX_TERMINAL_PROFILES) {
                            throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.RECORD_COUNT)
                        }
                        terminalProfiles += profile
                    }
                    collectionId == BackupPayloadWire.COLLECTION_TERMINAL &&
                        type == BackupPayloadWire.RECORD_TERMINAL_THEME -> decodeKnown(
                        collectionId,
                        type,
                        body,
                        THEME_FIELDS,
                        ::decodeTheme,
                    )?.let { theme ->
                        if (terminalThemes.size == BackupPayloadFormat.MAX_TERMINAL_THEMES) {
                            throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.RECORD_COUNT)
                        }
                        terminalThemes += theme
                    }
                    type != BackupPayloadWire.RECORD_PRIMARY -> decodeUnknownRecord(collectionId, type, body)
                    collectionId == BackupPayloadWire.COLLECTION_HOSTS -> decodeKnown(
                        collectionId,
                        type,
                        body,
                        HOST_FIELDS,
                        ::decodeHost,
                    )?.let(hostProfiles::add)
                    collectionId == BackupPayloadWire.COLLECTION_CREDENTIALS -> decodeKnown(
                        collectionId,
                        type,
                        body,
                        CREDENTIAL_FIELDS,
                        ::decodeCredential,
                    )?.let { record ->
                        try {
                            claimSecretReference(record.secretReferenceId)
                            credentials += record
                        } catch (error: Throwable) {
                            record.portableSecret?.wipe()
                            throw error
                        }
                    }
                    collectionId == BackupPayloadWire.COLLECTION_SSH_KEYS -> decodeKnown(
                        collectionId,
                        type,
                        body,
                        SSH_KEY_FIELDS,
                        ::decodeSshKey,
                    )?.let { record ->
                        try {
                            claimSecretReference(record.metadata.privateKeySecretReferenceId)
                            sshKeys += record
                        } catch (error: Throwable) {
                            record.portablePrivateKey?.wipe()
                            throw error
                        }
                    }
                    collectionId == BackupPayloadWire.COLLECTION_KNOWN_HOSTS -> decodeKnown(
                        collectionId,
                        type,
                        body,
                        KNOWN_HOST_FIELDS,
                        ::decodeKnownHost,
                    )?.let(knownHosts::add)
                    collectionId == BackupPayloadWire.COLLECTION_SNIPPETS -> decodeKnown(
                        collectionId,
                        type,
                        body,
                        SNIPPET_FIELDS,
                        ::decodeSnippet,
                    )?.let(snippets::add)
                    collectionId == BackupPayloadWire.COLLECTION_KEYBOARD -> decodeKnown(
                        collectionId,
                        type,
                        body,
                        KEYBOARD_FIELDS,
                        ::decodeKeyboard,
                    )?.let(keyboardProfiles::add)
                    collectionId == BackupPayloadWire.COLLECTION_GLOBAL_SETTINGS -> decodeKnown(
                        collectionId,
                        type,
                        body,
                        GLOBAL_FIELDS,
                        ::decodeGlobalSettings,
                    )?.let { settings ->
                        if (decodedGlobalSettings) {
                            throw malformed(BackupPayloadMalformedReason.DUPLICATE_RECORD_ID)
                        }
                        decodedGlobalSettings = true
                        globalSettings = settings
                    }
                    collectionId == BackupPayloadWire.COLLECTION_CUSTOM_FONTS -> decodeKnown(
                        collectionId,
                        type,
                        body,
                        CUSTOM_FONT_FIELDS,
                        ::decodeCustomFont,
                    )?.let(customFonts::add)
                }
            } finally {
                body.fill(0)
            }
        }
    }

    fun readUnknownCollection(collectionId: Int, input: PayloadSectionInput) {
        skippedUnknownCollections += 1
        val count = input.readCount(BackupPayloadFormat.MAX_RECORDS_PER_UNKNOWN_COLLECTION)
        repeat(count) {
            val type = input.readU16(BackupPayloadSection.RECORD_HEADER)
            val bodyLength = input.readRecordLength()
            val body = input.readBytes(bodyLength, BackupPayloadSection.RECORD_BODY)
            try {
                decodeUnknownRecord(collectionId, type, body)
                skippedUnknownRecords += 1
            } finally {
                body.fill(0)
            }
        }
    }

    fun finish(): BackupPayloadReadResult {
        val snapshot = try {
            BackupPayloadSnapshot(
                mode = expectedMode,
                hostProfiles = hostProfiles,
                credentials = credentials,
                sshKeys = sshKeys,
                knownHosts = knownHosts,
                snippets = snippets,
                terminalProfiles = terminalProfiles,
                terminalThemes = terminalThemes,
                keyboardProfiles = keyboardProfiles,
                customFonts = customFonts,
                globalSettings = globalSettings,
            )
        } catch (error: IllegalArgumentException) {
            throw malformed(mapSnapshotFailure(error))
        }
        return BackupPayloadReadResult(
            snapshot,
            BackupPayloadCompatibilityReport(
                incompatibleRecords = incompatible.toList(),
                skippedUnknownCollections = skippedUnknownCollections,
                skippedUnknownRecordTypes = skippedUnknownRecordTypes,
                skippedUnknownRecords = skippedUnknownRecords,
                skippedOptionalFields = skippedOptionalFields,
                unresolvedReferences = snapshot.unresolvedReferences,
            ),
        )
    }

    fun wipeSecrets() {
        credentials.forEach { it.portableSecret?.wipe() }
        sshKeys.forEach { it.portablePrivateKey?.wipe() }
    }

    private fun <T> decodeKnown(
        collectionId: Int,
        type: Int,
        body: ByteArray,
        knownFields: Set<Int>,
        decode: (PayloadFields, String) -> T,
    ): T? = PayloadFields.parse(body).use { fields ->
        val common = fields.readCommon()
        claimRecordId(common.id)
        if (common.schemaVersion > BackupPayloadWire.RECORD_SCHEMA_VERSION) {
            incompatible += IncompatibleBackupRecord(
                collectionId,
                type,
                common.id,
                BackupIncompatibleReason.NEWER_RECORD_SCHEMA,
            )
            return null
        }
        if (common.schemaVersion != BackupPayloadWire.RECORD_SCHEMA_VERSION.toLong()) {
            throw malformed(BackupPayloadMalformedReason.INVALID_VALUE, common.id)
        }
        val unknownRequired = fields.ids.firstOrNull { id ->
            id !in knownFields && id and BackupPayloadWire.REQUIRED_ID_MASK != 0
        }
        if (unknownRequired != null) {
            incompatible += IncompatibleBackupRecord(
                collectionId,
                type,
                common.id,
                BackupIncompatibleReason.UNKNOWN_REQUIRED_FIELD,
            )
            return null
        }
        skippedOptionalFields += fields.ids.count { id ->
            id !in knownFields && id and BackupPayloadWire.REQUIRED_ID_MASK == 0
        }
        try {
            decode(fields, common.id)
        } catch (error: BackupPayloadException) {
            throw error
        } catch (_: IllegalArgumentException) {
            throw malformed(BackupPayloadMalformedReason.INVALID_VALUE, common.id)
        }
    }

    private fun decodeUnknownRecord(collectionId: Int, type: Int, body: ByteArray) {
        PayloadFields.parse(body).use { fields ->
            val common = fields.readCommon()
            claimRecordId(common.id)
            if (type and BackupPayloadWire.REQUIRED_ID_MASK != 0) {
                incompatible += IncompatibleBackupRecord(
                    collectionId,
                    type,
                    common.id,
                    BackupIncompatibleReason.UNKNOWN_REQUIRED_RECORD_TYPE,
                )
            } else {
                skippedUnknownRecordTypes += 1
            }
        }
    }

    private fun claimRecordId(id: String) {
        if (!recordIds.add(id)) throw malformed(BackupPayloadMalformedReason.DUPLICATE_RECORD_ID, id)
    }

    private fun claimSecretReference(id: String?) {
        if (id != null && !secretReferenceIds.add(id)) {
            throw malformed(BackupPayloadMalformedReason.DUPLICATE_SECRET_REFERENCE, id)
        }
    }

    private fun decodeHost(fields: PayloadFields, id: String): HostProfile {
        val rangeFirst = fields.optionalU32Int(25)
        val rangeLast = fields.optionalU32Int(26)
        if ((rangeFirst == null) != (rangeLast == null)) {
            throw malformed(BackupPayloadMalformedReason.INVALID_VALUE, id)
        }
        return HostProfile(
            id = id,
            displayName = fields.requiredString(10),
            hostname = fields.requiredString(11),
            port = fields.requiredU32Int(12),
            username = fields.requiredString(13),
            protocol = ConnectionProtocol.fromWireCode(fields.requiredString(14)),
            credentialId = fields.optionalString(15),
            terminalProfileId = fields.optionalString(16),
            keyboardProfileId = fields.optionalString(17),
            isFavorite = fields.requiredBoolean(18),
            group = fields.optionalString(19),
            tag = fields.optionalString(20),
            startupCommand = fields.optionalString(21),
            keepaliveIntervalSeconds = fields.optionalU32Int(22),
            reconnectPolicy = fields.optionalString(23)?.let(ReconnectPolicy::fromWireCode),
            moshPort = fields.optionalU32Int(24),
            moshPortRange = rangeFirst?.let { MoshPortRange(it, requireNotNull(rangeLast)) },
            moshServerCommand = fields.optionalString(27),
            moshLocale = fields.optionalString(30),
            moshFallbackPolicy = fields.optionalString(31)
                ?.let(MoshFallbackPolicy::fromWireCode)
                ?: MoshFallbackPolicy.NEVER,
            createdAtEpochMillis = fields.requiredU64(28),
            updatedAtEpochMillis = fields.requiredU64(29),
        )
    }

    private fun decodeCredential(fields: PayloadFields, id: String): BackupCredentialRecord {
        val kind = SshCredentialKind.fromWireCode(fields.requiredString(11))
        val secretReference = fields.optionalString(12)
        val keyIdentity = fields.optionalString(13)
        val authentication = when (kind) {
            SshCredentialKind.PASSWORD -> {
                require(keyIdentity == null)
                SshAuthentication.Password(secretReference)
            }
            SshCredentialKind.PRIVATE_KEY ->
                SshAuthentication.PrivateKey(requireNotNull(keyIdentity), secretReference)
            SshCredentialKind.KEYBOARD_INTERACTIVE -> {
                require(keyIdentity == null)
                SshAuthentication.KeyboardInteractive(secretReference)
            }
        }
        val metadata = SshCredential(
            id,
            fields.requiredString(10),
            authentication,
            fields.requiredU64(15),
            fields.requiredU64(16),
        )
        val portable = fields.optionalSecret(14, BackupPayloadFormat.MAX_PASSWORD_OR_PASSPHRASE_BYTES)
        if (portable != null && expectedMode != BackupMode.FULL) {
            portable.wipe()
            throw malformed(BackupPayloadMalformedReason.MODE_CONTENT_MISMATCH, id)
        }
        return try {
            BackupCredentialRecord(metadata, portable)
        } catch (error: Throwable) {
            portable?.wipe()
            throw error
        }
    }

    private fun decodeSshKey(fields: PayloadFields, id: String): BackupSshKeyRecord {
        val metadata = SshKeyIdentity(
            id = id,
            name = fields.requiredString(10),
            algorithm = fields.requiredString(11),
            publicKeyFingerprint = fields.requiredString(12),
            publicKey = fields.optionalString(13),
            privateKeySecretReferenceId = fields.requiredString(14),
            origin = SshKeyOrigin.fromWireCode(fields.requiredString(15)),
            isPassphraseProtected = fields.requiredBoolean(16),
            comment = fields.optionalString(17),
            createdAtEpochMillis = fields.requiredU64(19),
            updatedAtEpochMillis = fields.requiredU64(20),
        )
        val portable = fields.optionalSecret(18, BackupPayloadFormat.MAX_PRIVATE_KEY_BYTES)
        if (portable != null && expectedMode != BackupMode.FULL) {
            portable.wipe()
            throw malformed(BackupPayloadMalformedReason.MODE_CONTENT_MISMATCH, id)
        }
        return try {
            BackupSshKeyRecord(metadata, portable)
        } catch (error: Throwable) {
            portable?.wipe()
            throw error
        }
    }

    private fun decodeKnownHost(fields: PayloadFields, id: String): KnownHost = KnownHost(
        id,
        fields.requiredString(10),
        fields.requiredU32Int(11),
        fields.requiredString(12),
        fields.requiredString(13),
        fields.requiredString(14),
        fields.optionalU64(15),
        fields.optionalU64(16),
    )

    private fun decodeSnippet(fields: PayloadFields, id: String): Snippet = Snippet(
        id = id,
        name = fields.requiredString(10),
        group = fields.optionalString(11),
        command = fields.requiredString(12),
        tapAction = SnippetTapAction.fromWireCode(fields.requiredString(13)),
        appendEnter = fields.requiredBoolean(14),
        confirmMultilineExecution = fields.requiredBoolean(15),
        isFavorite = fields.requiredBoolean(16),
        createdAtEpochMillis = fields.requiredU64(17),
        updatedAtEpochMillis = fields.requiredU64(18),
    )

    private fun decodeTerminalProfile(fields: PayloadFields, id: String): TerminalProfile = TerminalProfile(
        id = id,
        name = fields.requiredString(10),
        themeId = fields.requiredString(11),
        fontId = fields.requiredString(12),
        fontSizeSp = fields.requiredFloat(13),
        lineHeightMultiplier = fields.requiredFloat(14),
        letterSpacingEm = fields.requiredFloat(15),
        cursorStyle = CursorStyle.fromWireCode(fields.requiredString(16)),
        cursorBlinkEnabled = fields.requiredBoolean(17),
        scrollbackLines = fields.requiredU32Int(18),
        bell = BellSettings(
            fields.requiredBoolean(19),
            fields.requiredBoolean(20),
            fields.requiredBoolean(21),
        ),
        scroll = ScrollBehavior(
            TouchScrollMode.fromWireCode(fields.requiredString(22)),
            fields.requiredBoolean(23),
            fields.requiredBoolean(24),
            fields.requiredBoolean(25),
        ),
        links = LinkBehavior(
            detectPlainTextUrls = fields.requiredBoolean(26),
            osc8HyperlinksEnabled = fields.requiredBoolean(27),
            remoteClipboardMode = RemoteClipboardMode.fromWireCode(fields.requiredString(28)),
            copyOnSelection = fields.optionalBoolean(36) ?: false,
        ),
        termValue = fields.requiredString(29),
        preserveAlternateScreenHistory = fields.requiredBoolean(30),
        boldRenderingEnabled = fields.optionalBoolean(33) ?: true,
        ligaturesEnabled = fields.optionalBoolean(34) ?: false,
        pinchZoomEnabled = fields.optionalBoolean(35) ?: true,
        createdAtEpochMillis = fields.requiredU64(31),
        updatedAtEpochMillis = fields.requiredU64(32),
    )

    private fun decodeTheme(fields: PayloadFields, id: String): BackupTerminalTheme = BackupTerminalTheme(
        id,
        fields.requiredString(10),
        fields.requiredRawU32(11),
        fields.requiredRawU32(12),
        fields.requiredRawU32(13),
        fields.requiredRawU32(14),
        fields.requiredColours(15),
        fields.requiredU64(16),
        fields.requiredU64(17),
        fields.optionalBoolean(18) ?: true,
    )

    private fun decodeKeyboard(fields: PayloadFields, id: String): KeyboardProfile = KeyboardProfile(
        id = id,
        name = fields.requiredString(10),
        orderedActions = fields.requiredStringList(11).map(KeyboardAction::fromWireCode),
        layout = KeyboardLayout.fromWireCode(fields.requiredString(12)),
        modifierBehavior = ModifierBehavior.fromWireCode(fields.requiredString(13)),
        hapticFeedbackEnabled = fields.requiredBoolean(14),
        keyRepeatEnabled = fields.requiredBoolean(15),
        inputMode = TerminalInputMode.fromWireCode(fields.requiredString(16)),
        tmuxPrefix = fields.requiredString(17),
        createdAtEpochMillis = fields.requiredU64(18),
        updatedAtEpochMillis = fields.requiredU64(19),
    )

    private fun decodeGlobalSettings(fields: PayloadFields, id: String): BackupGlobalSettings {
        if (id != BackupPayloadFormat.GLOBAL_SETTINGS_RECORD_ID) {
            throw malformed(BackupPayloadMalformedReason.INVALID_VALUE, id)
        }
        return BackupGlobalSettings(
            themeMode = BackupThemeMode.fromWireValue(fields.requiredU8(10)) ?: invalid(id),
            dynamicColorEnabled = fields.requiredBoolean(11),
            accentPreset = fields.optionalString(12),
            defaultTerminalProfileId = fields.optionalString(13),
            defaultKeyboardProfileId = fields.optionalString(14),
            keepaliveIntervalSeconds = fields.requiredU32Int(15),
            reconnectEnabled = fields.requiredBoolean(16),
            reconnectMaxAttempts = fields.requiredU32Int(17),
            backgroundSessionsEnabled = fields.requiredBoolean(18),
            notificationPrivacyEnabled = fields.requiredBoolean(19),
            disconnectNotificationsEnabled = fields.requiredBoolean(20),
            reconnectNotificationsEnabled = fields.requiredBoolean(21),
            keepCpuAwake = fields.requiredBoolean(22),
            keepScreenOnWhileTerminalVisible = fields.requiredBoolean(23),
            appLockMode = BackupAppLockMode.fromWireValue(fields.requiredU8(24)) ?: invalid(id),
            appLockDelaySeconds = fields.requiredU32Int(25),
            screenshotBlockingEnabled = fields.requiredBoolean(26),
            sensitiveClipboardClearSeconds = fields.requiredU32Int(27),
            osc52Policy = RemoteClipboardMode.fromWireCode(fields.requiredString(28)),
            multilinePasteConfirmationEnabled = fields.requiredBoolean(29),
            lastBackupMode = fields.optionalU8(30)?.let { BackupMode.fromWireValue(it) ?: invalid(id) },
            tmuxSessionSelectorDisabled = fields.optionalBoolean(31) ?: false,
        )
    }

    private fun decodeCustomFont(fields: PayloadFields, id: String): BackupCustomFont {
        val bytes = fields.takeRequiredBytes(12, BackupPayloadFormat.MAX_CUSTOM_FONT_BYTES)
        return try {
            BackupCustomFont.takeOwnership(
                recordId = id,
                fontId = fields.requiredString(10),
                displayName = fields.requiredString(11),
                bytes = bytes,
            )
        } catch (error: Throwable) {
            bytes.fill(0)
            throw error
        }
    }

    private fun maximumCount(collectionId: Int): Int = when (collectionId) {
        BackupPayloadWire.COLLECTION_HOSTS -> BackupPayloadFormat.MAX_HOST_PROFILES
        BackupPayloadWire.COLLECTION_CREDENTIALS -> BackupPayloadFormat.MAX_CREDENTIALS
        BackupPayloadWire.COLLECTION_SSH_KEYS -> BackupPayloadFormat.MAX_SSH_KEYS
        BackupPayloadWire.COLLECTION_KNOWN_HOSTS -> BackupPayloadFormat.MAX_KNOWN_HOSTS
        BackupPayloadWire.COLLECTION_SNIPPETS -> BackupPayloadFormat.MAX_SNIPPETS
        BackupPayloadWire.COLLECTION_TERMINAL ->
            BackupPayloadFormat.MAX_TERMINAL_PROFILES + BackupPayloadFormat.MAX_TERMINAL_THEMES
        BackupPayloadWire.COLLECTION_KEYBOARD -> BackupPayloadFormat.MAX_KEYBOARD_PROFILES
        BackupPayloadWire.COLLECTION_GLOBAL_SETTINGS -> 1
        BackupPayloadWire.COLLECTION_CUSTOM_FONTS -> BackupPayloadFormat.MAX_CUSTOM_FONTS
        else -> error("Unknown collection")
    }

    private fun mapSnapshotFailure(error: IllegalArgumentException): BackupPayloadMalformedReason = when {
        error.message?.contains("record IDs") == true -> BackupPayloadMalformedReason.DUPLICATE_RECORD_ID
        error.message?.contains("secret reference") == true ->
            BackupPayloadMalformedReason.DUPLICATE_SECRET_REFERENCE
        error.message?.contains("Standard backups") == true -> BackupPayloadMalformedReason.MODE_CONTENT_MISMATCH
        else -> BackupPayloadMalformedReason.INVALID_VALUE
    }

    private fun invalid(recordId: String): Nothing =
        throw malformed(BackupPayloadMalformedReason.INVALID_VALUE, recordId)

    private companion object {
        val COMMON_FIELDS = setOf(1, 2)
        val HOST_FIELDS = COMMON_FIELDS + (10..31)
        val CREDENTIAL_FIELDS = COMMON_FIELDS + (10..16)
        val SSH_KEY_FIELDS = COMMON_FIELDS + (10..20)
        val KNOWN_HOST_FIELDS = COMMON_FIELDS + (10..16)
        val SNIPPET_FIELDS = COMMON_FIELDS + (10..18)
        val TERMINAL_FIELDS = COMMON_FIELDS + (10..36)
        val THEME_FIELDS = COMMON_FIELDS + (10..18)
        val KEYBOARD_FIELDS = COMMON_FIELDS + (10..19)
        val GLOBAL_FIELDS = COMMON_FIELDS + (10..31)
        val CUSTOM_FONT_FIELDS = COMMON_FIELDS + (10..12)
    }
}

private data class CommonRecordFields(val schemaVersion: Long, val id: String)

private class PayloadFields private constructor(
    private val values: LinkedHashMap<Int, ByteArray>,
) : AutoCloseable {
    val ids: Set<Int>
        get() = values.keys

    fun readCommon(): CommonRecordFields {
        val schema = requiredU32(BackupPayloadWire.RECORD_SCHEMA_FIELD)
        val id = requiredString(BackupPayloadWire.RECORD_ID_FIELD)
        try {
            requireCanonicalBackupUuid(id, "backup record ID")
        } catch (_: IllegalArgumentException) {
            throw malformed(BackupPayloadMalformedReason.INVALID_VALUE, id)
        }
        return CommonRecordFields(schema, id)
    }

    fun requiredString(id: Int): String = required(id).decodeUtf8()

    fun takeRequiredBytes(id: Int, maximum: Int): ByteArray {
        val value = values.remove(id)
            ?: throw malformed(BackupPayloadMalformedReason.MISSING_REQUIRED_FIELD)
        if (value.size !in 1..maximum) {
            value.fill(0)
            throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.FIELD_BYTES)
        }
        return value
    }

    fun optionalString(id: Int): String? = values[id]?.decodeUtf8()

    fun requiredU8(id: Int): Int = required(id).readU8()

    fun optionalU8(id: Int): Int? = values[id]?.readU8()

    fun requiredBoolean(id: Int): Boolean = when (val value = requiredU8(id)) {
        0 -> false
        1 -> true
        else -> throw malformed(BackupPayloadMalformedReason.INVALID_VALUE)
    }

    fun optionalBoolean(id: Int): Boolean? = optionalU8(id)?.let { value ->
        when (value) {
            0 -> false
            1 -> true
            else -> throw malformed(BackupPayloadMalformedReason.INVALID_VALUE)
        }
    }

    fun requiredU32(id: Int): Long = required(id).readU32()

    fun requiredU32Int(id: Int): Int = requiredU32(id).toSupportedInt()

    fun optionalU32Int(id: Int): Int? = values[id]?.readU32()?.toSupportedInt()

    fun requiredRawU32(id: Int): Int = required(id).readU32().toInt()

    fun requiredFloat(id: Int): Float = Float.fromBits(requiredRawU32(id))

    fun requiredU64(id: Int): Long = required(id).readU64()

    fun optionalU64(id: Int): Long? = values[id]?.readU64()

    fun optionalSecret(id: Int, maximum: Int): PortableBackupSecret? {
        val value = values[id] ?: return null
        if (value.size !in 1..maximum) throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.SECRET_BYTES)
        return PortableBackupSecret.copyAndWipe(value)
    }

    fun requiredColours(id: Int): List<Int> {
        val bytes = required(id)
        if (bytes.size != BackupTerminalTheme.ANSI_COLOUR_COUNT * 4) {
            throw malformed(BackupPayloadMalformedReason.INVALID_LENGTH)
        }
        return List(BackupTerminalTheme.ANSI_COLOUR_COUNT) { index -> bytes.readRawU32(index * 4).toInt() }
    }

    fun requiredStringList(id: Int): List<String> {
        val cursor = PayloadByteCursor(required(id))
        val count = cursor.readU32().toSupportedInt()
        if (count > KeyboardAction.entries.size) {
            throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.RECORD_COUNT)
        }
        val result = List(count) {
            val length = cursor.readU16()
            cursor.readBytes(length).decodeUtf8()
        }
        cursor.requireExhausted()
        return result
    }

    private fun required(id: Int): ByteArray = values[id]
        ?: throw malformed(BackupPayloadMalformedReason.MISSING_REQUIRED_FIELD)

    override fun close() = values.values.forEach { it.fill(0) }

    companion object {
        fun parse(body: ByteArray): PayloadFields {
            val cursor = PayloadByteCursor(body)
            val values = linkedMapOf<Int, ByteArray>()
            var previousId = 0
            try {
                while (cursor.remaining > 0) {
                    if (cursor.remaining < BackupPayloadWire.TLV_PREFIX_BYTES) {
                        throw malformed(BackupPayloadMalformedReason.INVALID_LENGTH)
                    }
                    val id = cursor.readU16()
                    if (id <= previousId) throw malformed(BackupPayloadMalformedReason.NON_CANONICAL_FIELDS)
                    previousId = id
                    val rawLength = cursor.readU32()
                    if (rawLength > BackupPayloadFormat.MAX_RECORD_BYTES) {
                        throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.FIELD_BYTES)
                    }
                    values[id] = cursor.readBytes(rawLength.toSupportedInt())
                }
                return PayloadFields(values)
            } catch (error: Throwable) {
                values.values.forEach { it.fill(0) }
                throw error
            }
        }
    }
}

private class PayloadByteCursor(private val bytes: ByteArray) {
    private var position = 0
    val remaining: Int
        get() = bytes.size - position

    fun readU16(): Int {
        requireRemaining(2)
        return ((bytes[position++].toInt() and 0xff) shl 8) or (bytes[position++].toInt() and 0xff)
    }

    fun readU32(): Long {
        requireRemaining(4)
        var result = 0L
        repeat(4) { result = (result shl 8) or (bytes[position++].toInt() and 0xff).toLong() }
        return result
    }

    fun readBytes(length: Int): ByteArray {
        requireRemaining(length)
        return bytes.copyOfRange(position, position + length).also { position += length }
    }

    fun requireExhausted() {
        if (remaining != 0) throw malformed(BackupPayloadMalformedReason.INVALID_LENGTH)
    }

    private fun requireRemaining(length: Int) {
        if (length < 0 || length > remaining) throw malformed(BackupPayloadMalformedReason.INVALID_LENGTH)
    }
}

private class PayloadInput(
    private val input: InputStream,
    private val maximumBytes: Long,
) {
    private var bytesRead = 0L

    fun readByteOrEof(): Int? {
        val value = input.read()
        if (value < 0) return null
        addRead(1)
        return value
    }

    fun readByte(section: BackupPayloadSection): Int = readByteOrEof()
        ?: throw BackupPayloadException.Truncated(section)

    fun readU32(section: BackupPayloadSection): Long {
        var value = 0L
        repeat(4) { value = (value shl 8) or readByte(section).toLong() }
        return value
    }

    fun readExact(length: Int, section: BackupPayloadSection): ByteArray {
        if (length < 0 || length.toLong() > maximumBytes) {
            throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.TOTAL_BYTES)
        }
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            when {
                count < 0 -> throw BackupPayloadException.Truncated(section)
                count == 0 -> result[offset++] = readByte(section).toByte()
                else -> {
                    addRead(count.toLong())
                    offset += count
                }
            }
        }
        return result
    }

    private fun addRead(count: Long) {
        if (count < 0 || bytesRead + count < bytesRead || bytesRead + count > maximumBytes) {
            throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.TOTAL_BYTES)
        }
        bytesRead += count
    }
}

private class PayloadSectionInput(
    private val input: PayloadInput,
    length: Long,
) {
    private var remaining = length

    fun readCount(maximum: Int): Int {
        val count = readU32(BackupPayloadSection.COLLECTION_BODY)
        if (count > maximum) throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.RECORD_COUNT)
        return count.toSupportedInt()
    }

    fun readRecordLength(): Int {
        val length = readU32(BackupPayloadSection.RECORD_HEADER)
        if (length > BackupPayloadFormat.MAX_RECORD_BYTES) {
            throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.RECORD_BYTES)
        }
        return length.toSupportedInt()
    }

    fun readU16(section: BackupPayloadSection): Int =
        (readByte(section) shl 8) or readByte(section)

    fun readU32(section: BackupPayloadSection): Long {
        var value = 0L
        repeat(4) { value = (value shl 8) or readByte(section).toLong() }
        return value
    }

    fun readBytes(length: Int, section: BackupPayloadSection): ByteArray {
        requireRemaining(length.toLong())
        remaining -= length
        return input.readExact(length, section)
    }

    fun requireExhausted() {
        if (remaining != 0L) {
            // Distinguish a collection whose declared bytes are absent from one whose record count
            // leaves real, unframed bytes behind.
            readByte(BackupPayloadSection.COLLECTION_BODY)
            throw malformed(BackupPayloadMalformedReason.INVALID_LENGTH)
        }
    }

    private fun readByte(section: BackupPayloadSection): Int {
        requireRemaining(1)
        remaining -= 1
        return input.readByte(section)
    }

    private fun requireRemaining(length: Long) {
        if (length < 0 || length > remaining) throw malformed(BackupPayloadMalformedReason.INVALID_LENGTH)
    }
}

private fun ByteArray.readU8(): Int {
    if (size != 1) throw malformed(BackupPayloadMalformedReason.INVALID_LENGTH)
    return this[0].toInt() and 0xff
}

private fun ByteArray.readU32(): Long {
    if (size != 4) throw malformed(BackupPayloadMalformedReason.INVALID_LENGTH)
    var value = 0L
    forEach { byte -> value = (value shl 8) or (byte.toInt() and 0xff).toLong() }
    return value
}

private fun ByteArray.readRawU32(offset: Int): Long {
    if (offset < 0 || size - offset < 4) throw malformed(BackupPayloadMalformedReason.INVALID_LENGTH)
    var value = 0L
    repeat(4) { index -> value = (value shl 8) or (this[offset + index].toInt() and 0xff).toLong() }
    return value
}

private fun ByteArray.readU64(): Long {
    if (size != 8) throw malformed(BackupPayloadMalformedReason.INVALID_LENGTH)
    if ((this[0].toInt() and 0x80) != 0) throw malformed(BackupPayloadMalformedReason.INVALID_VALUE)
    var value = 0L
    forEach { byte -> value = (value shl 8) or (byte.toInt() and 0xff).toLong() }
    return value
}

private fun ByteArray.decodeUtf8(): String {
    if (size > BackupPayloadFormat.MAX_STRING_BYTES) {
        throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.STRING_BYTES)
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    } catch (_: CharacterCodingException) {
        throw malformed(BackupPayloadMalformedReason.INVALID_UTF8)
    }
}

private fun Long.toSupportedInt(): Int {
    if (this > Int.MAX_VALUE) throw BackupPayloadException.LimitExceeded(BackupPayloadLimit.FIELD_BYTES)
    return toInt()
}

private fun malformed(
    reason: BackupPayloadMalformedReason,
    recordId: String? = null,
): BackupPayloadException.Malformed = BackupPayloadException.Malformed(reason, recordId)
