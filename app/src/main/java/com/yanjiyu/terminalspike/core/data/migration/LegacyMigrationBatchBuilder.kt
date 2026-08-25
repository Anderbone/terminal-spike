package com.yanjiyu.terminalspike.core.data.migration

import com.yanjiyu.terminalspike.core.data.db.EncryptedSecretEntity
import com.yanjiyu.terminalspike.core.data.db.HostProfileEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileKeyEntity
import com.yanjiyu.terminalspike.core.data.db.KnownHostEntity
import com.yanjiyu.terminalspike.core.data.db.LegacyMigrationBatch
import com.yanjiyu.terminalspike.core.data.db.LegacyMigrationStateEntity
import com.yanjiyu.terminalspike.core.data.db.SnippetEntity
import com.yanjiyu.terminalspike.core.data.db.SshCredentialEntity
import com.yanjiyu.terminalspike.core.data.db.SshKeyIdentityEntity
import com.yanjiyu.terminalspike.core.data.db.TerminalProfileEntity
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import com.yanjiyu.terminalspike.core.model.SshCredentialKind
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.core.model.TouchScrollMode
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.EncryptedCredentialRecord
import com.yanjiyu.terminalspike.settings.SavedSshIdentity
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey

/** Stable reasons retained when one legacy sidecar cannot be migrated to a usable envelope. */
internal enum class LegacySecretUnavailableReason(val wireCode: String) {
    MISSING("missing"),
    KEY_UNAVAILABLE("key_unavailable"),
    CORRUPT("corrupt"),
    UNSUPPORTED_VERSION("unsupported_version"),
    IO_UNAVAILABLE("io_unavailable"),
    TAMPERED("tampered"),
    REOPEN_FAILED("reopen_failed"),
}

/**
 * A secret migration result contains either already-encrypted, reopened ciphertext or a typed
 * recovery state. The batch builder never accepts or manufactures plaintext.
 */
internal sealed interface LegacySecretMigrationResult {
    data class Ready(val record: EncryptedCredentialRecord) : LegacySecretMigrationResult

    data class Unavailable(
        val reason: LegacySecretUnavailableReason,
    ) : LegacySecretMigrationResult
}

internal data class LegacyPasswordSecretMigration(
    val legacyProfileId: Long,
    val result: LegacySecretMigrationResult,
)

internal data class LegacyPrivateKeySecretMigration(
    val legacyIdentityId: Long,
    val result: LegacySecretMigrationResult,
)

internal data class LegacySecretMigrationInputs(
    val passwords: List<LegacyPasswordSecretMigration> = emptyList(),
    val privateKeys: List<LegacyPrivateKeySecretMigration> = emptyList(),
)

/**
 * Pure, deterministic translation from parsed legacy values to rows for one Room transaction.
 * Completion remains the final member of [LegacyMigrationBatch], and the DAO writes it last.
 */
internal object LegacyMigrationBatchBuilder {
    const val USER_SETTINGS_SOURCE = "legacy_user_settings"
    const val KNOWN_HOSTS_SOURCE = "legacy_known_hosts"
    const val STATE_COMPLETE = LegacyMigrationStateEntity.STATE_COMPLETE
    const val STATE_COMPLETE_WITH_WARNINGS =
        LegacyMigrationStateEntity.STATE_COMPLETE_WITH_WARNINGS
    const val STATE_ABSENT = LegacyMigrationStateEntity.STATE_ABSENT
    const val STATE_DISCARDED_AFTER_RECOVERY =
        LegacyMigrationStateEntity.STATE_DISCARDED_AFTER_RECOVERY
    const val SECRET_STATE_READY = "ready"
    const val SECRET_STATE_LEGACY_UNAVAILABLE = "legacy_unavailable"

    fun buildUserSettings(
        source: LegacyUserSettingsReadResult.Loaded,
        secretInputs: LegacySecretMigrationInputs,
        migratedAtEpochMillis: Long,
    ): LegacyMigrationBatch {
        requireTimestamp(migratedAtEpochMillis)
        requireDigest(source.sourceDigestSha256)
        require(source.sourceVersion in 1..3) { "Unsupported legacy settings version." }
        validateSettingsVersion(source.settings, source.sourceVersion)

        val profiles = source.settings.profiles.sortedBy(SavedSshProfile::id)
        val identities = source.settings.identities.sortedBy(SavedSshIdentity::id)
        requireDistinctPositiveIds(profiles.map(SavedSshProfile::id), "profile")
        requireDistinctPositiveIds(source.settings.snippets.map { it.id }, "snippet")
        requireDistinctPositiveIds(identities.map(SavedSshIdentity::id), "identity")

        val passwordInputs = secretInputs.passwords.uniqueByLegacyId(
            id = LegacyPasswordSecretMigration::legacyProfileId,
            type = "password",
        )
        val privateKeyInputs = secretInputs.privateKeys.uniqueByLegacyId(
            id = LegacyPrivateKeySecretMigration::legacyIdentityId,
            type = "private key",
        )
        requireExactSecretInputs(
            expected = profiles.filter(SavedSshProfile::hasSavedPassword).map(SavedSshProfile::id).toSet(),
            actual = passwordInputs.keys,
            type = "password",
        )
        requireExactSecretInputs(
            expected = identities.map(SavedSshIdentity::id).toSet(),
            actual = privateKeyInputs.keys,
            type = "private key",
        )

        val warnings = mutableListOf<String>()
        val passwordSecrets = profiles.mapNotNull { profile ->
            passwordInputs[profile.id]?.let { input ->
                input.result.toSecretEntity(
                    expectedId = LegacyIds.passwordSecret(profile.id),
                    kind = CredentialSecretKind.PASSWORD,
                    legacyId = "profile/${profile.id}",
                    migratedAtEpochMillis = migratedAtEpochMillis,
                    unavailableWarning = "password_secret_unavailable:${profile.id}",
                    warnings = warnings,
                )
            }
        }
        val privateKeySecrets = identities.map { identity ->
            privateKeyInputs.getValue(identity.id).result.toSecretEntity(
                expectedId = LegacyIds.privateKeySecret(identity.id),
                kind = CredentialSecretKind.PRIVATE_KEY,
                legacyId = "identity/${identity.id}",
                migratedAtEpochMillis = migratedAtEpochMillis,
                unavailableWarning = "private_key_secret_unavailable:${identity.id}",
                warnings = warnings,
            )
        }

        val migratedKeyboardActions = source.settings.extraKeys.upgradeShippedDefaultDeck()
        return LegacyMigrationBatch(
            terminalProfiles = listOf(defaultTerminalProfile(migratedAtEpochMillis)),
            keyboardProfiles = listOf(
                defaultKeyboardProfile(
                    timestamp = migratedAtEpochMillis,
                    rowCount = if (migratedKeyboardActions == KeyboardAction.DEFAULT_ORDER) 2 else 1,
                ),
            ),
            keyboardKeys = migratedKeyboardActions.mapIndexed { position, action ->
                KeyboardProfileKeyEntity(
                    profileId = LegacyIds.defaultKeyboardProfile,
                    position = position,
                    actionCode = action.wireCode,
                )
            },
            secrets = passwordSecrets + privateKeySecrets,
            keyIdentities = identities.map { it.toEntity(migratedAtEpochMillis) },
            credentials = profiles.filter(SavedSshProfile::hasSavedPassword).map { profile ->
                SshCredentialEntity(
                    id = LegacyIds.passwordCredential(profile.id),
                    name = profile.label,
                    kindCode = SshCredentialKind.PASSWORD.wireCode,
                    secretId = LegacyIds.passwordSecret(profile.id),
                    keyIdentityId = null,
                    createdAtEpochMillis = migratedAtEpochMillis,
                    updatedAtEpochMillis = migratedAtEpochMillis,
                )
            },
            hosts = profiles.map { profile ->
                HostProfileEntity(
                    id = LegacyIds.hostProfile(profile.id),
                    displayName = profile.label,
                    hostname = profile.host,
                    port = profile.port,
                    username = profile.username,
                    protocolCode = ConnectionProtocol.SSH.wireCode,
                    credentialId = if (profile.hasSavedPassword) {
                        LegacyIds.passwordCredential(profile.id)
                    } else {
                        null
                    },
                    terminalProfileId = LegacyIds.defaultTerminalProfile,
                    keyboardProfileId = LegacyIds.defaultKeyboardProfile,
                    isFavorite = false,
                    groupName = null,
                    tag = null,
                    startupCommand = null,
                    keepaliveIntervalSeconds = null,
                    reconnectPolicyCode = null,
                    moshPortStart = null,
                    moshPortEnd = null,
                    moshServerCommand = null,
                    createdAtEpochMillis = migratedAtEpochMillis,
                    updatedAtEpochMillis = migratedAtEpochMillis,
                )
            },
            snippets = source.settings.snippets.sortedBy { it.id }.map { snippet ->
                SnippetEntity(
                    id = LegacyIds.snippet(snippet.id),
                    name = snippet.label,
                    groupName = null,
                    command = snippet.command,
                    actionCode = SnippetTapAction.SEND_IMMEDIATELY.wireCode,
                    appendEnter = snippet.appendEnter,
                    confirmMultiline = true,
                    isFavorite = false,
                    createdAtEpochMillis = migratedAtEpochMillis,
                    updatedAtEpochMillis = migratedAtEpochMillis,
                )
            },
            completion = completion(
                sourceCode = USER_SETTINGS_SOURCE,
                sourceDigestSha256 = source.sourceDigestSha256,
                sourceVersion = source.sourceVersion,
                warnings = warnings,
                migratedAtEpochMillis = migratedAtEpochMillis,
            ),
        )
    }

    /** Known hosts have their own source marker and never depend on settings migration success. */
    fun buildKnownHosts(
        sourceDigestSha256: String,
        parsed: LegacyKnownHostsParseResult,
        migratedAtEpochMillis: Long,
    ): LegacyMigrationBatch {
        requireTimestamp(migratedAtEpochMillis)
        requireDigest(sourceDigestSha256)
        val rows = parsed.records.sortedWith(
            compareBy<LegacyKnownHostRecord> { it.canonicalHost }
                .thenBy { it.port }
                .thenBy { it.algorithm }
                .thenBy { it.id },
        ).map { record ->
            KnownHostEntity(
                id = record.id,
                host = record.canonicalHost,
                port = record.port,
                algorithmCode = record.algorithm,
                fingerprint = record.fingerprintSha256,
                publicKey = record.publicKey.copyOf(),
                firstSeenAtEpochMillis = null,
                lastSeenAtEpochMillis = null,
            )
        }
        val warnings = parsed.warnings
            .sortedWith(compareBy<LegacyKnownHostsWarning> { it.lineNumber }.thenBy { it.code.name })
            .map { warning ->
                "known_host_${warning.code.name.lowercase()}:line_${warning.lineNumber}"
            }

        return LegacyMigrationBatch(
            knownHosts = rows,
            completion = completion(
                sourceCode = KNOWN_HOSTS_SOURCE,
                sourceDigestSha256 = sourceDigestSha256,
                sourceVersion = null,
                warnings = warnings,
                migratedAtEpochMillis = migratedAtEpochMillis,
            ),
        )
    }

    /**
     * Seeds the fixed profiles needed by AppSettings while making an absent or explicitly
     * discarded legacy settings source permanently non-importable.
     */
    fun buildDefaultUserSettingsTerminal(
        stateCode: String,
        migratedAtEpochMillis: Long,
    ): LegacyMigrationBatch {
        requireSourceWithoutDataTerminalState(stateCode)
        requireTimestamp(migratedAtEpochMillis)
        return LegacyMigrationBatch(
            terminalProfiles = listOf(defaultTerminalProfile(migratedAtEpochMillis)),
            keyboardProfiles = listOf(
                defaultKeyboardProfile(
                    timestamp = migratedAtEpochMillis,
                    rowCount = 2,
                ),
            ),
            keyboardKeys = KeyboardAction.DEFAULT_ORDER.mapIndexed { position, action ->
                KeyboardProfileKeyEntity(
                    profileId = LegacyIds.defaultKeyboardProfile,
                    position = position,
                    actionCode = action.wireCode,
                )
            },
            completion = sourceWithoutDataCompletion(
                sourceCode = USER_SETTINGS_SOURCE,
                stateCode = stateCode,
                migratedAtEpochMillis = migratedAtEpochMillis,
            ),
        )
    }

    /** Records that no retained known-host input may be imported, without deleting that input. */
    fun buildEmptyKnownHostsTerminal(
        stateCode: String,
        migratedAtEpochMillis: Long,
    ): LegacyMigrationBatch {
        requireSourceWithoutDataTerminalState(stateCode)
        requireTimestamp(migratedAtEpochMillis)
        return LegacyMigrationBatch(
            completion = sourceWithoutDataCompletion(
                sourceCode = KNOWN_HOSTS_SOURCE,
                stateCode = stateCode,
                migratedAtEpochMillis = migratedAtEpochMillis,
            ),
        )
    }

    private fun defaultTerminalProfile(timestamp: Long) = TerminalProfileEntity(
        id = LegacyIds.defaultTerminalProfile,
        name = "Default",
        themeId = "current",
        fontId = TerminalRendererProfile.DEFAULT_FONT_ID,
        fontSizeSp = 14f,
        lineHeightMultiplier = 1f,
        letterSpacingEm = 0f,
        cursorStyleCode = CursorStyle.BLOCK.wireCode,
        cursorBlink = true,
        scrollbackLines = 20_000,
        visualBellEnabled = false,
        vibrationBellEnabled = false,
        audibleBellEnabled = false,
        touchScrollModeCode = TouchScrollMode.AUTO.wireCode,
        twoFingerLocalScrollOverride = true,
        jumpToBottomOnKeyboardInput = true,
        keepViewportPositionOnOutput = true,
        detectPlainTextUrls = true,
        osc8HyperlinksEnabled = true,
        remoteClipboardModeCode = RemoteClipboardMode.DISABLED.wireCode,
        termType = "xterm-256color",
        retainAlternateScreenHistory = true,
        createdAtEpochMillis = timestamp,
        updatedAtEpochMillis = timestamp,
    )

    private fun defaultKeyboardProfile(
        timestamp: Long,
        rowCount: Int,
    ) = KeyboardProfileEntity(
        id = LegacyIds.defaultKeyboardProfile,
        name = "Default",
        rowCount = rowCount,
        modifierPolicyCode = ModifierBehavior.ONE_SHOT.wireCode,
        hapticEnabled = false,
        keyRepeatEnabled = true,
        inputModeCode = TerminalInputMode.RAW.wireCode,
        tmuxPrefix = "C-b",
        createdAtEpochMillis = timestamp,
        updatedAtEpochMillis = timestamp,
    )

    /**
     * Only exact action orders that shipped as defaults are promoted. A reordered, added, or
     * removed key is a user configuration and is preserved byte-for-byte in its original row.
     */
    private fun List<TerminalExtraKey>.upgradeShippedDefaultDeck(): List<KeyboardAction> =
        if (
            this == TerminalExtraKey.LEGACY_DEFAULT_ORDER ||
            this == TerminalExtraKey.PAGED_DEFAULT_ORDER ||
            this == TerminalExtraKey.PREVIOUS_DEFAULT_ORDER ||
            this == TerminalExtraKey.DEFAULT_ORDER
        ) {
            KeyboardAction.DEFAULT_ORDER
        } else {
            map { KeyboardAction.fromWireCode(it.toStableActionCode()) }
        }

    private fun SavedSshIdentity.toEntity(timestamp: Long) = SshKeyIdentityEntity(
        id = LegacyIds.keyIdentity(id),
        name = label,
        algorithmCode = keyType,
        fingerprint = fingerprint,
        publicKey = null,
        provenanceCode = SshKeyOrigin.IMPORTED.wireCode,
        isPassphraseProtected = passphraseRequired,
        comment = null,
        privateSecretId = LegacyIds.privateKeySecret(id),
        createdAtEpochMillis = timestamp,
        updatedAtEpochMillis = timestamp,
    )

    private fun LegacySecretMigrationResult.toSecretEntity(
        expectedId: String,
        kind: CredentialSecretKind,
        legacyId: String,
        migratedAtEpochMillis: Long,
        unavailableWarning: String,
        warnings: MutableList<String>,
    ): EncryptedSecretEntity = when (this) {
        is LegacySecretMigrationResult.Ready -> {
            require(record.secretId == expectedId) { "Ready secret ID does not match its legacy owner." }
            require(record.kindCode == kind.wireCode) { "Ready secret kind does not match its legacy owner." }
            require(record.envelopeVersion > 0) { "Ready secret envelope version must be positive." }
            require(record.keyVersion > 0) { "Ready secret key version must be positive." }
            val nonce = record.copyNonce()
            val ciphertext = record.copyCiphertext()
            require(nonce.isNotEmpty()) { "Ready secret nonce must not be empty." }
            require(ciphertext.isNotEmpty()) { "Ready secret ciphertext must not be empty." }
            EncryptedSecretEntity(
                id = expectedId,
                kindCode = kind.wireCode,
                envelopeVersion = record.envelopeVersion,
                keyVersion = record.keyVersion,
                nonce = nonce,
                ciphertext = ciphertext,
                stateCode = SECRET_STATE_READY,
                failureCode = null,
                // Recovery metadata belongs only to an unavailable legacy payload. A READY row
                // must satisfy the same invariant as every newly written credential envelope.
                legacyId = null,
                createdAtEpochMillis = migratedAtEpochMillis,
                updatedAtEpochMillis = migratedAtEpochMillis,
            )
        }
        is LegacySecretMigrationResult.Unavailable -> {
            warnings += "$unavailableWarning:${reason.wireCode}"
            EncryptedSecretEntity(
                id = expectedId,
                kindCode = kind.wireCode,
                envelopeVersion = 0,
                keyVersion = 0,
                nonce = null,
                ciphertext = null,
                stateCode = SECRET_STATE_LEGACY_UNAVAILABLE,
                failureCode = reason.wireCode,
                legacyId = legacyId,
                createdAtEpochMillis = migratedAtEpochMillis,
                updatedAtEpochMillis = migratedAtEpochMillis,
            )
        }
    }

    private fun completion(
        sourceCode: String,
        sourceDigestSha256: String,
        sourceVersion: Int?,
        warnings: List<String>,
        migratedAtEpochMillis: Long,
    ): LegacyMigrationStateEntity {
        val stableWarnings = warnings.distinct().sorted()
        return LegacyMigrationStateEntity(
            sourceCode = sourceCode,
            sourceDigestSha256 = sourceDigestSha256,
            sourceVersion = sourceVersion,
            stateCode = if (stableWarnings.isEmpty()) STATE_COMPLETE else STATE_COMPLETE_WITH_WARNINGS,
            errorCode = null,
            warningCodes = stableWarnings.joinToString(separator = ";"),
            lastAttemptAtEpochMillis = migratedAtEpochMillis,
            completedAtEpochMillis = migratedAtEpochMillis,
        )
    }

    private fun sourceWithoutDataCompletion(
        sourceCode: String,
        stateCode: String,
        migratedAtEpochMillis: Long,
    ) = LegacyMigrationStateEntity(
        sourceCode = sourceCode,
        sourceDigestSha256 = null,
        sourceVersion = null,
        stateCode = stateCode,
        errorCode = null,
        warningCodes = "",
        lastAttemptAtEpochMillis = migratedAtEpochMillis,
        completedAtEpochMillis = migratedAtEpochMillis,
    )

    private fun requireSourceWithoutDataTerminalState(stateCode: String) {
        require(stateCode == STATE_ABSENT || stateCode == STATE_DISCARDED_AFTER_RECOVERY) {
            "A source-without-data terminal batch must be absent or discarded after recovery."
        }
    }

    private fun validateSettingsVersion(settings: UserSettings, sourceVersion: Int) {
        require(sourceVersion >= 2 || settings.identities.isEmpty()) {
            "Legacy settings v1 cannot contain SSH identities."
        }
        require(sourceVersion >= 3 || settings.profiles.none(SavedSshProfile::hasSavedPassword)) {
            "Legacy settings before v3 cannot reference saved passwords."
        }
    }

    private fun <T> List<T>.uniqueByLegacyId(
        id: (T) -> Long,
        type: String,
    ): Map<Long, T> {
        forEach { require(id(it) > 0) { "Legacy $type IDs must be positive." } }
        val result = associateBy(id)
        require(result.size == size) { "Duplicate legacy $type secret input." }
        return result
    }

    private fun requireExactSecretInputs(expected: Set<Long>, actual: Set<Long>, type: String) {
        require(expected == actual) { "Legacy $type secret inputs must exactly match settings references." }
    }

    private fun requireDistinctPositiveIds(ids: List<Long>, type: String) {
        require(ids.all { it > 0 }) { "Legacy $type IDs must be positive." }
        require(ids.distinct().size == ids.size) { "Duplicate legacy $type ID." }
    }

    private fun requireTimestamp(timestamp: Long) {
        require(timestamp in 0..253_402_300_799_999L) { "Migration timestamp is outside the supported range." }
    }

    private fun requireDigest(digest: String) {
        require(SHA256.matches(digest)) { "Legacy source digest must be lowercase SHA-256 hex." }
    }

    private fun TerminalExtraKey.toStableActionCode(): String = when (this) {
        TerminalExtraKey.ESC -> KeyboardAction.ESCAPE.wireCode
        TerminalExtraKey.CTRL -> KeyboardAction.CONTROL.wireCode
        TerminalExtraKey.ALT -> KeyboardAction.ALT.wireCode
        TerminalExtraKey.TAB -> KeyboardAction.TAB.wireCode
        TerminalExtraKey.ENTER -> KeyboardAction.ENTER.wireCode
        TerminalExtraKey.INSERT -> KeyboardAction.INSERT.wireCode
        TerminalExtraKey.UP -> KeyboardAction.ARROW_UP.wireCode
        TerminalExtraKey.DOWN -> KeyboardAction.ARROW_DOWN.wireCode
        TerminalExtraKey.LEFT -> KeyboardAction.ARROW_LEFT.wireCode
        TerminalExtraKey.RIGHT -> KeyboardAction.ARROW_RIGHT.wireCode
        TerminalExtraKey.PAGE_UP -> KeyboardAction.PAGE_UP.wireCode
        TerminalExtraKey.PAGE_DOWN -> KeyboardAction.PAGE_DOWN.wireCode
        TerminalExtraKey.HOME -> KeyboardAction.HOME.wireCode
        TerminalExtraKey.END -> KeyboardAction.END.wireCode
        TerminalExtraKey.DELETE -> KeyboardAction.DELETE.wireCode
        TerminalExtraKey.CTRL_C -> KeyboardAction.CTRL_C.wireCode
        TerminalExtraKey.CTRL_D -> KeyboardAction.CTRL_D.wireCode
        TerminalExtraKey.CTRL_A -> KeyboardAction.CTRL_A.wireCode
        TerminalExtraKey.CTRL_B -> KeyboardAction.CTRL_B.wireCode
        TerminalExtraKey.CTRL_E -> KeyboardAction.CTRL_E.wireCode
        TerminalExtraKey.CTRL_R -> KeyboardAction.CTRL_R.wireCode
        TerminalExtraKey.CTRL_W -> KeyboardAction.CTRL_W.wireCode
        TerminalExtraKey.CTRL_L -> KeyboardAction.CTRL_L.wireCode
        TerminalExtraKey.CTRL_U -> KeyboardAction.CTRL_U.wireCode
        TerminalExtraKey.SLASH -> KeyboardAction.SLASH.wireCode
        TerminalExtraKey.PIPE -> KeyboardAction.PIPE.wireCode
        TerminalExtraKey.DASH -> KeyboardAction.HYPHEN.wireCode
        TerminalExtraKey.TILDE -> KeyboardAction.TILDE.wireCode
        TerminalExtraKey.BACKTICK -> KeyboardAction.BACKTICK.wireCode
        TerminalExtraKey.BACKSLASH -> KeyboardAction.BACKSLASH.wireCode
        TerminalExtraKey.AT -> KeyboardAction.AT_SIGN.wireCode
        TerminalExtraKey.UNDERSCORE -> KeyboardAction.UNDERSCORE.wireCode
        TerminalExtraKey.F1 -> KeyboardAction.F1.wireCode
        TerminalExtraKey.F2 -> KeyboardAction.F2.wireCode
        TerminalExtraKey.F3 -> KeyboardAction.F3.wireCode
        TerminalExtraKey.F4 -> KeyboardAction.F4.wireCode
        TerminalExtraKey.F5 -> KeyboardAction.F5.wireCode
        TerminalExtraKey.F6 -> KeyboardAction.F6.wireCode
        TerminalExtraKey.F7 -> KeyboardAction.F7.wireCode
        TerminalExtraKey.F8 -> KeyboardAction.F8.wireCode
        TerminalExtraKey.F9 -> KeyboardAction.F9.wireCode
        TerminalExtraKey.F10 -> KeyboardAction.F10.wireCode
        TerminalExtraKey.F11 -> KeyboardAction.F11.wireCode
        TerminalExtraKey.F12 -> KeyboardAction.F12.wireCode
        TerminalExtraKey.HIDE_KEYBOARD -> KeyboardAction.HIDE_KEYBOARD.wireCode
        TerminalExtraKey.BACKSPACE -> KeyboardAction.BACKSPACE.wireCode
        TerminalExtraKey.CTRL_Z -> KeyboardAction.CTRL_Z.wireCode
        TerminalExtraKey.CTRL_K -> KeyboardAction.CTRL_K.wireCode
        TerminalExtraKey.COLON -> KeyboardAction.COLON.wireCode
        TerminalExtraKey.SEMICOLON -> KeyboardAction.SEMICOLON.wireCode
        TerminalExtraKey.HASH -> KeyboardAction.HASH.wireCode
        TerminalExtraKey.DOLLAR -> KeyboardAction.DOLLAR.wireCode
        TerminalExtraKey.EQUALS -> KeyboardAction.EQUALS.wireCode
        TerminalExtraKey.SPACE -> KeyboardAction.SPACE.wireCode
        TerminalExtraKey.EXCLAMATION -> KeyboardAction.EXCLAMATION.wireCode
        TerminalExtraKey.QUESTION -> KeyboardAction.QUESTION.wireCode
        TerminalExtraKey.ASTERISK -> KeyboardAction.ASTERISK.wireCode
        TerminalExtraKey.PLUS -> KeyboardAction.PLUS.wireCode
        TerminalExtraKey.PERIOD -> KeyboardAction.PERIOD.wireCode
        TerminalExtraKey.COMMA -> KeyboardAction.COMMA.wireCode
        TerminalExtraKey.LEFT_PAREN -> KeyboardAction.LEFT_PAREN.wireCode
        TerminalExtraKey.RIGHT_PAREN -> KeyboardAction.RIGHT_PAREN.wireCode
        TerminalExtraKey.LEFT_BRACKET -> KeyboardAction.LEFT_BRACKET.wireCode
        TerminalExtraKey.RIGHT_BRACKET -> KeyboardAction.RIGHT_BRACKET.wireCode
        TerminalExtraKey.LEFT_BRACE -> KeyboardAction.LEFT_BRACE.wireCode
        TerminalExtraKey.RIGHT_BRACE -> KeyboardAction.RIGHT_BRACE.wireCode
        TerminalExtraKey.SINGLE_QUOTE -> KeyboardAction.SINGLE_QUOTE.wireCode
        TerminalExtraKey.DOUBLE_QUOTE -> KeyboardAction.DOUBLE_QUOTE.wireCode
        TerminalExtraKey.LESS_THAN -> KeyboardAction.LESS_THAN.wireCode
        TerminalExtraKey.GREATER_THAN -> KeyboardAction.GREATER_THAN.wireCode
        TerminalExtraKey.AMPERSAND -> KeyboardAction.AMPERSAND.wireCode
        TerminalExtraKey.CARET -> KeyboardAction.CARET.wireCode
        TerminalExtraKey.PERCENT -> KeyboardAction.PERCENT.wireCode
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}
