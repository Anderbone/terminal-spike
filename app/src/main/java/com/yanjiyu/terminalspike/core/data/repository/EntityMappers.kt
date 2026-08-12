package com.yanjiyu.terminalspike.core.data.repository

import com.yanjiyu.terminalspike.core.data.db.HostProfileEntity
import com.yanjiyu.terminalspike.core.data.db.CustomTerminalThemeEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileKeyEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileWithKeys
import com.yanjiyu.terminalspike.core.data.db.KnownHostEntity
import com.yanjiyu.terminalspike.core.data.db.RecentSessionEntity
import com.yanjiyu.terminalspike.core.data.db.SnippetEntity
import com.yanjiyu.terminalspike.core.data.db.SshCredentialEntity
import com.yanjiyu.terminalspike.core.data.db.SshKeyIdentityEntity
import com.yanjiyu.terminalspike.core.data.db.TerminalProfileEntity
import com.yanjiyu.terminalspike.core.model.BellSettings
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.CustomTerminalTheme
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.KnownHost
import com.yanjiyu.terminalspike.core.model.LinkBehavior
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.core.model.RecentSession
import com.yanjiyu.terminalspike.core.model.ReconnectPolicy
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.core.model.ScrollBehavior
import com.yanjiyu.terminalspike.core.model.SessionState
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal fun SshCredentialEntity.toDomainModel(): SshCredential = decodeStored(
    RepositoryRecordType.SSH_CREDENTIAL,
    id,
) {
    val authentication = when (SshCredentialKind.fromWireCode(kindCode)) {
        SshCredentialKind.PASSWORD -> {
            require(keyIdentityId == null) { "A password credential cannot reference a key identity." }
            SshAuthentication.Password(secretId)
        }
        SshCredentialKind.PRIVATE_KEY -> {
            requireNotNull(keyIdentityId) { "A private-key credential must reference a key identity." }
            SshAuthentication.PrivateKey(
                keyIdentityId = keyIdentityId,
                passphraseSecretReferenceId = secretId,
            )
        }
        SshCredentialKind.KEYBOARD_INTERACTIVE -> {
            require(keyIdentityId == null) {
                "A keyboard-interactive credential cannot reference a key identity."
            }
            SshAuthentication.KeyboardInteractive(secretId)
        }
    }
    SshCredential(
        id = id,
        displayName = name,
        authentication = authentication,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

internal fun SshCredential.toEntity(): SshCredentialEntity = encodeDomain(
    RepositoryRecordType.SSH_CREDENTIAL,
    id,
) {
    val valid = copy()
    val authentication = valid.authentication
    SshCredentialEntity(
        id = valid.id,
        name = valid.displayName,
        kindCode = authentication.kind.wireCode,
        secretId = when (authentication) {
            is SshAuthentication.Password -> authentication.secretReferenceId
            is SshAuthentication.PrivateKey -> authentication.passphraseSecretReferenceId
            is SshAuthentication.KeyboardInteractive -> authentication.reusableResponseSecretReferenceId
        },
        keyIdentityId = (authentication as? SshAuthentication.PrivateKey)?.keyIdentityId,
        createdAtEpochMillis = valid.createdAtEpochMillis,
        updatedAtEpochMillis = valid.updatedAtEpochMillis,
    )
}

internal fun SshKeyIdentityEntity.toDomainModel(): SshKeyIdentity = decodeStored(
    RepositoryRecordType.SSH_KEY_IDENTITY,
    id,
) {
    val canonicalFingerprint = fingerprint.requireCanonicalSha256Fingerprint()
    val publicKeyText = publicKey?.let { keyBlob ->
        require(keyBlob.isNotEmpty()) { "A stored public-key blob must not be empty." }
        require(keyBlob.readSshPublicKeyAlgorithm() == algorithmCode) {
            "The stored public-key algorithm does not match its identity."
        }
        require(keyBlob.sha256Fingerprint() == canonicalFingerprint) {
            "The stored public-key fingerprint does not match its payload."
        }
        "$algorithmCode ${Base64.getEncoder().encodeToString(keyBlob)}"
    }
    SshKeyIdentity(
        id = id,
        name = name,
        algorithm = algorithmCode,
        publicKeyFingerprint = canonicalFingerprint,
        publicKey = publicKeyText,
        privateKeySecretReferenceId = privateSecretId,
        origin = SshKeyOrigin.fromWireCode(provenanceCode),
        isPassphraseProtected = isPassphraseProtected,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        comment = comment,
    )
}

internal fun SshKeyIdentity.toEntity(): SshKeyIdentityEntity = encodeDomain(
    RepositoryRecordType.SSH_KEY_IDENTITY,
    id,
) {
    val valid = copy()
    val canonicalFingerprint = valid.publicKeyFingerprint.requireCanonicalSha256Fingerprint()
    val publicKeyBlob = valid.publicKey?.decodeOpenSshPublicKey(valid.algorithm)
    if (publicKeyBlob != null) {
        require(publicKeyBlob.sha256Fingerprint() == canonicalFingerprint) {
            "The public-key fingerprint must match its payload."
        }
    }
    SshKeyIdentityEntity(
        id = valid.id,
        name = valid.name,
        algorithmCode = valid.algorithm,
        fingerprint = canonicalFingerprint,
        publicKey = publicKeyBlob,
        provenanceCode = valid.origin.wireCode,
        isPassphraseProtected = valid.isPassphraseProtected,
        comment = valid.comment,
        privateSecretId = valid.privateKeySecretReferenceId,
        createdAtEpochMillis = valid.createdAtEpochMillis,
        updatedAtEpochMillis = valid.updatedAtEpochMillis,
    )
}

internal fun HostProfileEntity.toDomainModel(): HostProfile = decodeStored(
    RepositoryRecordType.HOST_PROFILE,
    id,
) {
    val mosh = decodeMoshPorts(moshPortStart, moshPortEnd)
    HostProfile(
        id = id,
        displayName = displayName,
        hostname = hostname,
        port = port,
        username = username,
        protocol = ConnectionProtocol.fromWireCode(protocolCode),
        credentialId = credentialId,
        terminalProfileId = terminalProfileId,
        keyboardProfileId = keyboardProfileId,
        isFavorite = isFavorite,
        group = groupName,
        tag = tag,
        startupCommand = startupCommand,
        keepaliveIntervalSeconds = keepaliveIntervalSeconds,
        reconnectPolicy = reconnectPolicyCode?.let(ReconnectPolicy::fromWireCode),
        moshPort = mosh.singlePort,
        moshPortRange = mosh.range,
        moshServerCommand = moshServerCommand,
        moshLocale = moshLocale,
        moshFallbackPolicy = MoshFallbackPolicy.fromWireCode(moshFallbackPolicyCode),
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

internal fun HostProfile.toEntity(): HostProfileEntity = encodeDomain(
    RepositoryRecordType.HOST_PROFILE,
    id,
) {
    val valid = copy()
    val moshStart = valid.moshPort ?: valid.moshPortRange?.first
    // A null end distinguishes a single port from a one-element range, making both lossless.
    val moshEnd = valid.moshPortRange?.last
    HostProfileEntity(
        id = valid.id,
        displayName = valid.displayName,
        hostname = valid.hostname,
        port = valid.port,
        username = valid.username,
        protocolCode = valid.protocol.wireCode,
        credentialId = valid.credentialId,
        terminalProfileId = valid.terminalProfileId,
        keyboardProfileId = valid.keyboardProfileId,
        isFavorite = valid.isFavorite,
        groupName = valid.group,
        tag = valid.tag,
        startupCommand = valid.startupCommand,
        keepaliveIntervalSeconds = valid.keepaliveIntervalSeconds,
        reconnectPolicyCode = valid.reconnectPolicy?.wireCode,
        moshPortStart = moshStart,
        moshPortEnd = moshEnd,
        moshServerCommand = valid.moshServerCommand,
        moshLocale = valid.moshLocale,
        moshFallbackPolicyCode = valid.moshFallbackPolicy.wireCode,
        createdAtEpochMillis = valid.createdAtEpochMillis,
        updatedAtEpochMillis = valid.updatedAtEpochMillis,
    )
}

internal fun TerminalProfileEntity.toDomainModel(): TerminalProfile = decodeStored(
    RepositoryRecordType.TERMINAL_PROFILE,
    id,
) {
    TerminalProfile(
        id = id,
        name = name,
        themeId = themeId,
        fontId = fontId,
        fontSizeSp = fontSizeSp,
        lineHeightMultiplier = lineHeightMultiplier,
        letterSpacingEm = letterSpacingEm,
        cursorStyle = CursorStyle.fromWireCode(cursorStyleCode),
        cursorBlinkEnabled = cursorBlink,
        scrollbackLines = scrollbackLines,
        bell = BellSettings(
            visualBellEnabled = visualBellEnabled,
            vibrationBellEnabled = vibrationBellEnabled,
            audibleBellEnabled = audibleBellEnabled,
        ),
        scroll = ScrollBehavior(
            touchMode = TouchScrollMode.fromWireCode(touchScrollModeCode),
            twoFingerLocalScrollOverride = twoFingerLocalScrollOverride,
            jumpToBottomOnKeyboardInput = jumpToBottomOnKeyboardInput,
            keepViewportPositionOnOutput = keepViewportPositionOnOutput,
        ),
        links = LinkBehavior(
            detectPlainTextUrls = detectPlainTextUrls,
            osc8HyperlinksEnabled = osc8HyperlinksEnabled,
            remoteClipboardMode = RemoteClipboardMode.fromWireCode(remoteClipboardModeCode),
            copyOnSelection = copyOnSelection,
        ),
        termValue = termType,
        preserveAlternateScreenHistory = retainAlternateScreenHistory,
        boldRenderingEnabled = boldRenderingEnabled,
        ligaturesEnabled = ligaturesEnabled,
        pinchZoomEnabled = pinchZoomEnabled,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

internal fun TerminalProfile.toEntity(): TerminalProfileEntity = encodeDomain(
    RepositoryRecordType.TERMINAL_PROFILE,
    id,
) {
    val valid = copy()
    TerminalProfileEntity(
        id = valid.id,
        name = valid.name,
        themeId = valid.themeId,
        fontId = valid.fontId,
        fontSizeSp = valid.fontSizeSp,
        lineHeightMultiplier = valid.lineHeightMultiplier,
        letterSpacingEm = valid.letterSpacingEm,
        cursorStyleCode = valid.cursorStyle.wireCode,
        cursorBlink = valid.cursorBlinkEnabled,
        scrollbackLines = valid.scrollbackLines,
        visualBellEnabled = valid.bell.visualBellEnabled,
        vibrationBellEnabled = valid.bell.vibrationBellEnabled,
        audibleBellEnabled = valid.bell.audibleBellEnabled,
        touchScrollModeCode = valid.scroll.touchMode.wireCode,
        twoFingerLocalScrollOverride = valid.scroll.twoFingerLocalScrollOverride,
        jumpToBottomOnKeyboardInput = valid.scroll.jumpToBottomOnKeyboardInput,
        keepViewportPositionOnOutput = valid.scroll.keepViewportPositionOnOutput,
        detectPlainTextUrls = valid.links.detectPlainTextUrls,
        osc8HyperlinksEnabled = valid.links.osc8HyperlinksEnabled,
        remoteClipboardModeCode = valid.links.remoteClipboardMode.wireCode,
        termType = valid.termValue,
        retainAlternateScreenHistory = valid.preserveAlternateScreenHistory,
        boldRenderingEnabled = valid.boldRenderingEnabled,
        ligaturesEnabled = valid.ligaturesEnabled,
        pinchZoomEnabled = valid.pinchZoomEnabled,
        copyOnSelection = valid.links.copyOnSelection,
        createdAtEpochMillis = valid.createdAtEpochMillis,
        updatedAtEpochMillis = valid.updatedAtEpochMillis,
    )
}

internal fun CustomTerminalThemeEntity.toDomainModel(): CustomTerminalTheme = decodeStored(
    RepositoryRecordType.CUSTOM_TERMINAL_THEME,
    id,
) {
    CustomTerminalTheme(
        id = id,
        name = name,
        foregroundArgb = foregroundArgb,
        backgroundArgb = backgroundArgb,
        cursorArgb = cursorArgb,
        selectionArgb = selectionArgb,
        ansi16Argb = listOf(
            ansi0Argb,
            ansi1Argb,
            ansi2Argb,
            ansi3Argb,
            ansi4Argb,
            ansi5Argb,
            ansi6Argb,
            ansi7Argb,
            ansi8Argb,
            ansi9Argb,
            ansi10Argb,
            ansi11Argb,
            ansi12Argb,
            ansi13Argb,
            ansi14Argb,
            ansi15Argb,
        ),
        boldUsesBrightColours = boldUsesBrightColours,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

internal fun CustomTerminalTheme.toEntity(): CustomTerminalThemeEntity = encodeDomain(
    RepositoryRecordType.CUSTOM_TERMINAL_THEME,
    id,
) {
    val valid = copy(ansi16Argb = ansi16Argb.toList())
    CustomTerminalThemeEntity(
        id = valid.id,
        name = valid.name,
        foregroundArgb = valid.foregroundArgb,
        backgroundArgb = valid.backgroundArgb,
        cursorArgb = valid.cursorArgb,
        selectionArgb = valid.selectionArgb,
        ansi0Argb = valid.ansi16Argb[0],
        ansi1Argb = valid.ansi16Argb[1],
        ansi2Argb = valid.ansi16Argb[2],
        ansi3Argb = valid.ansi16Argb[3],
        ansi4Argb = valid.ansi16Argb[4],
        ansi5Argb = valid.ansi16Argb[5],
        ansi6Argb = valid.ansi16Argb[6],
        ansi7Argb = valid.ansi16Argb[7],
        ansi8Argb = valid.ansi16Argb[8],
        ansi9Argb = valid.ansi16Argb[9],
        ansi10Argb = valid.ansi16Argb[10],
        ansi11Argb = valid.ansi16Argb[11],
        ansi12Argb = valid.ansi16Argb[12],
        ansi13Argb = valid.ansi16Argb[13],
        ansi14Argb = valid.ansi16Argb[14],
        ansi15Argb = valid.ansi16Argb[15],
        boldUsesBrightColours = valid.boldUsesBrightColours,
        createdAtEpochMillis = valid.createdAtEpochMillis,
        updatedAtEpochMillis = valid.updatedAtEpochMillis,
    )
}

internal fun KeyboardProfileWithKeys.toDomainModel(): KeyboardProfile = decodeStored(
    RepositoryRecordType.KEYBOARD_PROFILE,
    profile.id,
) {
    val orderedKeys = keys.sortedBy(KeyboardProfileKeyEntity::position)
    require(orderedKeys.isNotEmpty()) { "A persisted keyboard profile must have at least one key." }
    orderedKeys.forEachIndexed { expectedPosition, key ->
        require(key.profileId == profile.id) { "A keyboard key references a different profile." }
        require(key.position == expectedPosition) { "Keyboard key positions must be contiguous from zero." }
    }
    val layout = KeyboardLayout.entries.singleOrNull { it.rowCount == profile.rowCount }
        ?: throw IllegalArgumentException("Unknown keyboard row count.")
    KeyboardProfile(
        id = profile.id,
        name = profile.name,
        orderedActions = orderedKeys.map { KeyboardAction.fromWireCode(it.actionCode) },
        layout = layout,
        modifierBehavior = ModifierBehavior.fromWireCode(profile.modifierPolicyCode),
        hapticFeedbackEnabled = profile.hapticEnabled,
        keyRepeatEnabled = profile.keyRepeatEnabled,
        inputMode = TerminalInputMode.fromWireCode(profile.inputModeCode),
        tmuxPrefix = profile.tmuxPrefix,
        createdAtEpochMillis = profile.createdAtEpochMillis,
        updatedAtEpochMillis = profile.updatedAtEpochMillis,
    )
}

internal data class KeyboardProfileRows(
    val profile: KeyboardProfileEntity,
    val keys: List<KeyboardProfileKeyEntity>,
)

internal fun KeyboardProfile.toRows(): KeyboardProfileRows = encodeDomain(
    RepositoryRecordType.KEYBOARD_PROFILE,
    id,
) {
    val valid = copy(orderedActions = orderedActions.toList())
    KeyboardProfileRows(
        profile = KeyboardProfileEntity(
            id = valid.id,
            name = valid.name,
            rowCount = valid.layout.rowCount,
            modifierPolicyCode = valid.modifierBehavior.wireCode,
            hapticEnabled = valid.hapticFeedbackEnabled,
            keyRepeatEnabled = valid.keyRepeatEnabled,
            inputModeCode = valid.inputMode.wireCode,
            tmuxPrefix = valid.tmuxPrefix,
            createdAtEpochMillis = valid.createdAtEpochMillis,
            updatedAtEpochMillis = valid.updatedAtEpochMillis,
        ),
        keys = valid.orderedActions.mapIndexed { position, action ->
            KeyboardProfileKeyEntity(
                profileId = valid.id,
                position = position,
                actionCode = action.wireCode,
            )
        },
    )
}

internal fun KnownHostEntity.toDomainModel(): KnownHost = decodeStored(
    RepositoryRecordType.KNOWN_HOST,
    id,
) {
    require((firstSeenAtEpochMillis == null) == (lastSeenAtEpochMillis == null)) {
        "Known-host observation timestamps must either both be present or both be absent."
    }
    require(publicKey.isNotEmpty()) { "The stored public host key must not be empty." }
    require(fingerprint == publicKey.knownHostSha256Fingerprint()) {
        "The stored host-key fingerprint does not match its payload."
    }
    KnownHost(
        id = id,
        host = host,
        port = port,
        keyAlgorithm = algorithmCode,
        fingerprint = fingerprint,
        publicHostKey = Base64.getEncoder().encodeToString(publicKey),
        firstSeenAtEpochMillis = firstSeenAtEpochMillis,
        lastSeenAtEpochMillis = lastSeenAtEpochMillis,
    )
}

internal fun KnownHost.toEntity(): KnownHostEntity = encodeDomain(
    RepositoryRecordType.KNOWN_HOST,
    id,
) {
    val valid = copy()
    val decodedKey = try {
        Base64.getDecoder().decode(valid.publicHostKey)
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("The public host key must be valid Base64.", failure)
    }
    require(decodedKey.isNotEmpty()) { "The public host key must not decode to an empty value." }
    require(valid.fingerprint == decodedKey.knownHostSha256Fingerprint()) {
        "The host-key fingerprint must be canonical and match its payload."
    }
    KnownHostEntity(
        id = valid.id,
        host = valid.host,
        port = valid.port,
        algorithmCode = valid.keyAlgorithm,
        fingerprint = valid.fingerprint,
        publicKey = decodedKey,
        firstSeenAtEpochMillis = valid.firstSeenAtEpochMillis,
        lastSeenAtEpochMillis = valid.lastSeenAtEpochMillis,
    )
}

private fun ByteArray.knownHostSha256Fingerprint(): String = "SHA256:" +
    Base64.getEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(this),
    )

internal fun SnippetEntity.toDomainModel(): Snippet = decodeStored(
    RepositoryRecordType.SNIPPET,
    id,
) {
    Snippet(
        id = id,
        name = name,
        group = groupName,
        command = command,
        tapAction = SnippetTapAction.fromWireCode(actionCode),
        appendEnter = appendEnter,
        confirmMultilineExecution = confirmMultiline,
        isFavorite = isFavorite,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

internal fun Snippet.toEntity(): SnippetEntity = encodeDomain(
    RepositoryRecordType.SNIPPET,
    id,
) {
    val valid = copy()
    SnippetEntity(
        id = valid.id,
        name = valid.name,
        groupName = valid.group,
        command = valid.command,
        actionCode = valid.tapAction.wireCode,
        appendEnter = valid.appendEnter,
        confirmMultiline = valid.confirmMultilineExecution,
        isFavorite = valid.isFavorite,
        createdAtEpochMillis = valid.createdAtEpochMillis,
        updatedAtEpochMillis = valid.updatedAtEpochMillis,
    )
}

internal fun RecentSessionEntity.toDomainModel(): RecentSession = decodeStored(
    RepositoryRecordType.RECENT_SESSION,
    id,
) {
    RecentSession(
        id = id,
        hostProfileId = hostProfileId,
        hostDisplayName = hostDisplayName,
        protocol = ConnectionProtocol.fromWireCode(protocolCode),
        state = SessionState.fromWireCode(stateCode),
        startedAtEpochMillis = startedAtEpochMillis,
        lastActivityAtEpochMillis = lastActivityAtEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
        terminalTitle = terminalTitle,
        endpointIdentityToken = endpointIdentityToken,
    )
}

internal fun RecentSession.toEntity(): RecentSessionEntity = encodeDomain(
    RepositoryRecordType.RECENT_SESSION,
    id,
) {
    val valid = copy()
    RecentSessionEntity(
        id = valid.id,
        hostProfileId = valid.hostProfileId,
        hostDisplayName = valid.hostDisplayName,
        protocolCode = valid.protocol.wireCode,
        stateCode = valid.state.wireCode,
        startedAtEpochMillis = valid.startedAtEpochMillis,
        lastActivityAtEpochMillis = valid.lastActivityAtEpochMillis,
        endedAtEpochMillis = valid.endedAtEpochMillis,
        terminalTitle = valid.terminalTitle,
        endpointIdentityToken = valid.endpointIdentityToken,
    )
}

private data class DecodedMoshPorts(
    val singlePort: Int?,
    val range: MoshPortRange?,
)

private fun decodeMoshPorts(start: Int?, end: Int?): DecodedMoshPorts = when {
    start == null && end == null -> DecodedMoshPorts(singlePort = null, range = null)
    start != null && end == null -> DecodedMoshPorts(singlePort = start, range = null)
    start != null && end != null -> DecodedMoshPorts(singlePort = null, range = MoshPortRange(start, end))
    else -> throw IllegalArgumentException("A Mosh range end cannot exist without a start.")
}

private fun String.decodeOpenSshPublicKey(expectedAlgorithm: String): ByteArray {
    val separator = indexOf(' ')
    require(separator > 0 && separator == lastIndexOf(' ') && separator < lastIndex) {
        "The public key must use canonical '<algorithm> <base64>' OpenSSH text."
    }
    require(substring(0, separator) == expectedAlgorithm) {
        "The public-key algorithm must match the identity algorithm."
    }
    val encodedPayload = substring(separator + 1)
    val decoded = try {
        Base64.getDecoder().decode(encodedPayload)
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("The public-key payload must be valid Base64.", failure)
    }
    require(decoded.isNotEmpty()) { "The public-key payload must not be empty." }
    require(Base64.getEncoder().encodeToString(decoded) == encodedPayload) {
        "The public-key payload must use canonical Base64."
    }
    require(decoded.readSshPublicKeyAlgorithm() == expectedAlgorithm) {
        "The encoded public-key algorithm must match the identity algorithm."
    }
    return decoded
}

private fun ByteArray.readSshPublicKeyAlgorithm(): String {
    require(size >= 5) { "The public-key blob is truncated." }
    val length = ((this[0].toLong() and 0xffL) shl 24) or
        ((this[1].toLong() and 0xffL) shl 16) or
        ((this[2].toLong() and 0xffL) shl 8) or
        (this[3].toLong() and 0xffL)
    require(length in 1..(size - 4).toLong()) { "The public-key algorithm field is invalid." }
    val algorithmBytes = copyOfRange(4, 4 + length.toInt())
    val algorithm = algorithmBytes.toString(StandardCharsets.UTF_8)
    require(algorithm.toByteArray(StandardCharsets.UTF_8).contentEquals(algorithmBytes)) {
        "The public-key algorithm is not valid UTF-8."
    }
    return algorithm
}

private fun ByteArray.sha256Fingerprint(): String = "SHA256:" +
    Base64.getEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(this),
    )

private fun String.requireCanonicalSha256Fingerprint(): String {
    require(startsWith(SHA256_FINGERPRINT_PREFIX)) {
        "The public-key fingerprint must use SHA256 format."
    }
    val encodedDigest = removePrefix(SHA256_FINGERPRINT_PREFIX)
    require(encodedDigest.isNotEmpty() && '=' !in encodedDigest) {
        "The public-key fingerprint must use unpadded Base64."
    }
    val digest = try {
        Base64.getDecoder().decode(encodedDigest)
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("The public-key fingerprint must use valid Base64.", failure)
    }
    require(digest.size == SHA256_DIGEST_BYTES) {
        "The public-key fingerprint must contain a SHA-256 digest."
    }
    require(Base64.getEncoder().withoutPadding().encodeToString(digest) == encodedDigest) {
        "The public-key fingerprint must use canonical unpadded Base64."
    }
    return this
}

private const val SHA256_FINGERPRINT_PREFIX = "SHA256:"
private const val SHA256_DIGEST_BYTES = 32

private inline fun <T> decodeStored(
    recordType: RepositoryRecordType,
    recordKey: String,
    block: () -> T,
): T = try {
    block()
} catch (failure: IllegalArgumentException) {
    throw CorruptStoredDataException(recordType, recordKey, failure)
}

private inline fun <T> encodeDomain(
    recordType: RepositoryRecordType,
    recordKey: String,
    block: () -> T,
): T = try {
    block()
} catch (failure: IllegalArgumentException) {
    throw InvalidRepositoryInputException(recordType, recordKey, failure)
}
