package com.yanjiyu.terminalspike.ui.connections

import androidx.annotation.StringRes
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.connection.HostIdentityPrompt
import com.yanjiyu.terminalspike.connection.KeyboardInteractiveChallenge
import com.yanjiyu.terminalspike.core.data.repository.CatalogSecretAvailability
import com.yanjiyu.terminalspike.core.data.repository.TerminalDataCatalog
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.ModelLimits
import com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.core.model.ReconnectPolicy
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.ui.UiText
import com.yanjiyu.terminalspike.ui.uiText
import java.util.UUID

internal enum class HostAuthenticationMethod {
    PASSWORD,
    PRIVATE_KEY,
    KEYBOARD_INTERACTIVE,
}

internal enum class HostKeepaliveMode {
    INHERIT,
    OFF,
    CUSTOM,
}

internal enum class HostReconnectMode {
    INHERIT,
    OFF,
    AUTOMATIC,
}

internal enum class MoshPortMode {
    AUTOMATIC,
    SINGLE,
    RANGE,
}

/** In-memory editor values. Passwords and passphrases stay in separate wipeable dialog memory. */
internal data class HostEditorDraft(
    val persistentId: String? = null,
    val displayName: String = "",
    val protocol: ConnectionProtocol = ConnectionProtocol.SSH,
    val hostname: String = "",
    val port: String = "22",
    val username: String = "",
    val authenticationMethod: HostAuthenticationMethod = HostAuthenticationMethod.PASSWORD,
    val keyIdentityId: String? = null,
    val retainSavedSecret: Boolean = false,
    val terminalProfileId: String? = null,
    val keyboardProfileId: String? = null,
    val isFavourite: Boolean = false,
    val group: String = "",
    val tag: String = "",
    val startupCommand: String = "",
    val keepaliveMode: HostKeepaliveMode = HostKeepaliveMode.INHERIT,
    val keepaliveSeconds: String = "30",
    val reconnectMode: HostReconnectMode = HostReconnectMode.INHERIT,
    val moshPortMode: MoshPortMode = MoshPortMode.AUTOMATIC,
    val moshPort: String = "",
    val moshRangeFirst: String = "60000",
    val moshRangeLast: String = "60010",
    val moshServerCommand: String = "mosh-server",
    val moshLocale: String = "",
    val moshFallbackPolicy: MoshFallbackPolicy = MoshFallbackPolicy.NEVER,
)

internal data class HostEditorErrors(
    val displayName: UiText? = null,
    val hostname: UiText? = null,
    val port: UiText? = null,
    val username: UiText? = null,
    val authentication: UiText? = null,
    val group: UiText? = null,
    val tag: UiText? = null,
    val startupCommand: UiText? = null,
    val keepalive: UiText? = null,
    val moshPort: UiText? = null,
    val moshServerCommand: UiText? = null,
    val moshLocale: UiText? = null,
) {
    val isEmpty: Boolean
        get() = listOf(
            displayName,
            hostname,
            port,
            username,
            authentication,
            group,
            tag,
            startupCommand,
            keepalive,
            moshPort,
            moshServerCommand,
            moshLocale,
        ).all { it == null }
}

internal data class ValidatedHostEditor(
    val persistentId: String?,
    val displayName: String,
    val protocol: ConnectionProtocol,
    val hostname: String,
    val port: Int,
    val username: String,
    val authenticationMethod: HostAuthenticationMethod,
    val keyIdentityId: String?,
    val retainSavedSecret: Boolean,
    val terminalProfileId: String?,
    val keyboardProfileId: String?,
    val isFavourite: Boolean,
    val group: String?,
    val tag: String?,
    val startupCommand: String?,
    val keepaliveIntervalSeconds: Int?,
    val reconnectPolicy: ReconnectPolicy?,
    val moshPort: Int?,
    val moshPortRange: MoshPortRange?,
    val moshServerCommand: String?,
    val moshLocale: String? = null,
    val moshFallbackPolicy: MoshFallbackPolicy = MoshFallbackPolicy.NEVER,
) {
    fun toProfile(
        id: String = persistentId ?: UUID.randomUUID().toString(),
        nowEpochMillis: Long,
    ): HostProfile = HostProfile(
        id = id,
        displayName = displayName,
        hostname = hostname,
        port = port,
        username = username,
        protocol = protocol,
        credentialId = null,
        terminalProfileId = terminalProfileId,
        keyboardProfileId = keyboardProfileId,
        isFavorite = isFavourite,
        group = group,
        tag = tag,
        startupCommand = startupCommand,
        keepaliveIntervalSeconds = keepaliveIntervalSeconds,
        reconnectPolicy = reconnectPolicy,
        moshPort = moshPort,
        moshPortRange = moshPortRange,
        moshServerCommand = moshServerCommand,
        moshLocale = moshLocale,
        moshFallbackPolicy = moshFallbackPolicy,
        createdAtEpochMillis = nowEpochMillis,
        updatedAtEpochMillis = nowEpochMillis,
    )
}

internal data class HostEditorValidation(
    val value: ValidatedHostEditor?,
    val errors: HostEditorErrors,
)

internal data class HostEditorSubmission(
    val value: ValidatedHostEditor,
    /** A transient password, keyboard-interactive response, or key passphrase. */
    val secret: CharArray,
    val savePassword: Boolean,
) {
    fun wipe() = secret.fill('\u0000')
}

internal data class HostEditorSeed(
    val draft: HostEditorDraft,
    val savedSecretAvailable: Boolean,
)

internal data class KeyEditorSeed(
    val persistentId: String,
    val name: String,
    val algorithm: String,
    val fingerprint: String,
    val publicKey: String?,
    val origin: SshKeyOrigin,
    val passphraseProtected: Boolean,
    val privateKeyAvailable: Boolean,
    val comment: String?,
    /** Number of credential records that currently reference this identity. */
    val referenceCount: Int = 0,
)

internal data class CatalogProfileOption(
    val id: String,
    val name: String,
)

internal data class ConnectionsEditorCatalog(
    val hosts: List<HostEditorSeed> = emptyList(),
    val keys: List<KeyEditorSeed> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val terminalProfiles: List<CatalogProfileOption> = emptyList(),
    val keyboardProfiles: List<CatalogProfileOption> = emptyList(),
    val defaultTerminalProfileId: String? = null,
    val defaultKeyboardProfileId: String? = null,
) {
    fun newHostDraft(): HostEditorDraft = HostEditorDraft(
        terminalProfileId = defaultTerminalProfileId,
        keyboardProfileId = defaultKeyboardProfileId,
    )
}

internal fun TerminalDataCatalog.toConnectionsEditorCatalog(): ConnectionsEditorCatalog {
    val credentialsById = credentials.associateBy { it.id }
    val identityReferenceCounts = credentials
        .mapNotNull { credential ->
            (credential.authentication as? SshAuthentication.PrivateKey)?.keyIdentityId
        }
        .groupingBy { it }
        .eachCount()
    return ConnectionsEditorCatalog(
        hosts = hosts.map { catalogHost ->
            val profile = catalogHost.profile
            val credential = profile.credentialId?.let(credentialsById::get)
            val authentication = credential?.authentication
            HostEditorSeed(
                draft = HostEditorDraft(
                    persistentId = profile.id,
                    displayName = profile.displayName,
                    protocol = profile.protocol,
                    hostname = profile.hostname,
                    port = profile.port.toString(),
                    username = profile.username,
                    authenticationMethod = when (authentication) {
                        is SshAuthentication.PrivateKey -> HostAuthenticationMethod.PRIVATE_KEY
                        is SshAuthentication.KeyboardInteractive ->
                            HostAuthenticationMethod.KEYBOARD_INTERACTIVE
                        is SshAuthentication.Password, null -> HostAuthenticationMethod.PASSWORD
                    },
                    keyIdentityId = (authentication as? SshAuthentication.PrivateKey)?.keyIdentityId,
                    retainSavedSecret = credential?.savedSecretAvailability ==
                        CatalogSecretAvailability.AVAILABLE,
                    terminalProfileId = profile.terminalProfileId,
                    keyboardProfileId = profile.keyboardProfileId,
                    isFavourite = profile.isFavorite,
                    group = profile.group.orEmpty(),
                    tag = profile.tag.orEmpty(),
                    startupCommand = profile.startupCommand.orEmpty(),
                    keepaliveMode = when (profile.keepaliveIntervalSeconds) {
                        null -> HostKeepaliveMode.INHERIT
                        0 -> HostKeepaliveMode.OFF
                        else -> HostKeepaliveMode.CUSTOM
                    },
                    keepaliveSeconds = profile.keepaliveIntervalSeconds
                        ?.takeIf { it > 0 }
                        ?.toString()
                        ?: "30",
                    reconnectMode = when (profile.reconnectPolicy) {
                        null -> HostReconnectMode.INHERIT
                        ReconnectPolicy.DISABLED -> HostReconnectMode.OFF
                        ReconnectPolicy.AUTOMATIC -> HostReconnectMode.AUTOMATIC
                    },
                    moshPortMode = when {
                        profile.moshPort != null -> MoshPortMode.SINGLE
                        profile.moshPortRange != null -> MoshPortMode.RANGE
                        else -> MoshPortMode.AUTOMATIC
                    },
                    moshPort = profile.moshPort?.toString().orEmpty(),
                    moshRangeFirst = profile.moshPortRange?.first?.toString() ?: "60000",
                    moshRangeLast = profile.moshPortRange?.last?.toString() ?: "60010",
                    moshServerCommand = profile.moshServerCommand ?: "mosh-server",
                    moshLocale = profile.moshLocale.orEmpty(),
                    moshFallbackPolicy = profile.moshFallbackPolicy,
                ),
                savedSecretAvailable = credential?.savedSecretAvailability ==
                    CatalogSecretAvailability.AVAILABLE,
            )
        },
        keys = identities.map { identity ->
            KeyEditorSeed(
                persistentId = identity.id,
                name = identity.name,
                algorithm = identity.algorithm,
                fingerprint = identity.publicKeyFingerprint,
                publicKey = identity.publicKey,
                origin = identity.origin,
                passphraseProtected = identity.isPassphraseProtected,
                privateKeyAvailable = identity.privateKeyAvailability == CatalogSecretAvailability.AVAILABLE,
                comment = identity.comment,
                referenceCount = identityReferenceCounts[identity.id] ?: 0,
            )
        },
        snippets = snippets.map { it.snippet.copy() },
        terminalProfiles = terminalProfiles.map { CatalogProfileOption(it.id, it.name) },
        keyboardProfiles = keyboardProfiles.map { CatalogProfileOption(it.id, it.name) },
        defaultTerminalProfileId = defaultTerminalProfileId,
        defaultKeyboardProfileId = defaultKeyboardProfileId,
    )
}

internal fun validateHostEditor(
    draft: HostEditorDraft,
    availableKeyIds: Set<String>,
): HostEditorValidation {
    val displayName = draft.displayName.trim()
    val hostname = draft.hostname.trim().removeSurrounding("[", "]")
    val username = draft.username.trim()
    val port = draft.port.toIntOrNull()
    val group = draft.group.trim().takeIf(String::isNotEmpty)
    val tag = draft.tag.trim().takeIf(String::isNotEmpty)
    val startupCommand = draft.startupCommand.takeIf(String::isNotBlank)
    val displayNameError = validatePlainText(
        value = displayName,
        labelRes = R.string.connections_field_friendly_name,
        maximumLength = ModelLimits.MAX_DISPLAY_NAME_LENGTH,
    )
    val hostnameError = validateHostname(hostname)
    val portError = if (port == null || port !in ModelLimits.MIN_PORT..ModelLimits.MAX_PORT) {
        uiText(R.string.connections_validation_port)
    } else {
        null
    }
    val usernameError = validateUsername(username)
    val authenticationError = when {
        draft.authenticationMethod == HostAuthenticationMethod.PRIVATE_KEY &&
            draft.keyIdentityId == null -> uiText(R.string.connections_validation_choose_key)
        draft.authenticationMethod == HostAuthenticationMethod.PRIVATE_KEY &&
            draft.keyIdentityId !in availableKeyIds ->
                uiText(R.string.connections_validation_key_unavailable)
        else -> null
    }
    val groupError = group?.let {
        validatePlainText(it, R.string.connections_field_group, ModelLimits.MAX_GROUP_LENGTH)
    }
    val tagError = tag?.let {
        validatePlainText(it, R.string.connections_field_tag, ModelLimits.MAX_TAG_LENGTH)
    }
    val startupError = startupCommand?.let {
        validateCommand(it, R.string.connections_field_startup_command, ModelLimits.MAX_COMMAND_LENGTH)
    }
    val keepalive = when (draft.keepaliveMode) {
        HostKeepaliveMode.INHERIT -> null
        HostKeepaliveMode.OFF -> 0
        HostKeepaliveMode.CUSTOM -> draft.keepaliveSeconds.toIntOrNull()
    }
    val keepaliveError = if (
        draft.keepaliveMode == HostKeepaliveMode.CUSTOM &&
        (keepalive == null ||
            keepalive !in ModelLimits.MIN_KEEPALIVE_SECONDS..ModelLimits.MAX_KEEPALIVE_SECONDS)
    ) {
        uiText(R.string.connections_validation_keepalive)
    } else {
        null
    }
    val reconnect = when (draft.reconnectMode) {
        HostReconnectMode.INHERIT -> null
        HostReconnectMode.OFF -> ReconnectPolicy.DISABLED
        HostReconnectMode.AUTOMATIC -> ReconnectPolicy.AUTOMATIC
    }
    var moshPort: Int? = null
    var moshRange: MoshPortRange? = null
    val moshPortError = if (draft.protocol == ConnectionProtocol.SSH) {
        null
    } else {
        when (draft.moshPortMode) {
            MoshPortMode.AUTOMATIC -> null
            MoshPortMode.SINGLE -> {
                val parsed = draft.moshPort.toIntOrNull()
                if (parsed == null || parsed !in ModelLimits.MIN_PORT..ModelLimits.MAX_PORT) {
                    uiText(R.string.connections_validation_mosh_port)
                } else {
                    moshPort = parsed
                    null
                }
            }
            MoshPortMode.RANGE -> {
                val first = draft.moshRangeFirst.toIntOrNull()
                val last = draft.moshRangeLast.toIntOrNull()
                val parsed = if (first == null || last == null) {
                    null
                } else {
                    runCatching { MoshPortRange(first, last) }.getOrNull()
                }
                if (parsed == null) {
                    uiText(R.string.connections_validation_mosh_range)
                } else {
                    moshRange = parsed
                    null
                }
            }
        }
    }
    val normalizedMoshCommand = draft.moshServerCommand.trim()
    val moshCommand = if (draft.protocol == ConnectionProtocol.MOSH) {
        normalizedMoshCommand.takeUnless { it.isEmpty() || it == "mosh-server" }
    } else {
        null
    }
    val moshCommandError = if (
        draft.protocol == ConnectionProtocol.MOSH &&
        !isSafeMoshExecutable(normalizedMoshCommand)
    ) {
        uiText(R.string.connections_validation_mosh_command)
    } else {
        null
    }
    val normalizedMoshLocale = draft.moshLocale.trim()
    val moshLocale = normalizedMoshLocale
        .takeIf { draft.protocol == ConnectionProtocol.MOSH && it.isNotEmpty() }
    val moshLocaleError = if (
        moshLocale != null && !isValidMoshLocale(moshLocale)
    ) {
        uiText(R.string.connections_validation_mosh_locale)
    } else {
        null
    }
    val errors = HostEditorErrors(
        displayName = displayNameError,
        hostname = hostnameError,
        port = portError,
        username = usernameError,
        authentication = authenticationError,
        group = groupError,
        tag = tagError,
        startupCommand = startupError,
        keepalive = keepaliveError,
        moshPort = moshPortError,
        moshServerCommand = moshCommandError,
        moshLocale = moshLocaleError,
    )
    if (!errors.isEmpty) return HostEditorValidation(null, errors)
    return HostEditorValidation(
        value = ValidatedHostEditor(
            persistentId = draft.persistentId,
            displayName = displayName,
            protocol = draft.protocol,
            hostname = hostname,
            port = requireNotNull(port),
            username = username,
            authenticationMethod = draft.authenticationMethod,
            keyIdentityId = draft.keyIdentityId,
            retainSavedSecret = draft.retainSavedSecret,
            terminalProfileId = draft.terminalProfileId,
            keyboardProfileId = draft.keyboardProfileId,
            isFavourite = draft.isFavourite,
            group = group,
            tag = tag,
            startupCommand = startupCommand,
            keepaliveIntervalSeconds = keepalive,
            reconnectPolicy = reconnect,
            moshPort = moshPort,
            moshPortRange = moshRange,
            moshServerCommand = moshCommand,
            moshLocale = moshLocale,
            moshFallbackPolicy = if (draft.protocol == ConnectionProtocol.MOSH) {
                draft.moshFallbackPolicy
            } else {
                MoshFallbackPolicy.NEVER
            },
        ),
        errors = errors,
    )
}

private fun isValidMoshLocale(value: String): Boolean =
    value.length in "C.UTF-8".length..ModelLimits.MAX_MOSH_LOCALE_LENGTH &&
        value.first().isLetterOrDigit() && value.endsWith(".UTF-8") &&
        value.all { character ->
            character.code in 0x21..0x7e &&
                (character.isLetterOrDigit() || character in "_.@-")
        }

private fun validateHostname(hostname: String): UiText? = runCatching {
    HostProfile(
        id = VALIDATION_UUID,
        displayName = VALIDATION_DISPLAY_NAME,
        hostname = hostname,
        port = 22,
        username = VALIDATION_USERNAME,
        protocol = ConnectionProtocol.SSH,
        credentialId = null,
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
    )
}.exceptionOrNull()?.let { uiText(R.string.connections_validation_hostname) }

private fun validateUsername(username: String): UiText? = runCatching {
    HostProfile(
        id = VALIDATION_UUID,
        displayName = VALIDATION_DISPLAY_NAME,
        hostname = VALIDATION_HOSTNAME,
        port = 22,
        username = username,
        protocol = ConnectionProtocol.SSH,
        credentialId = null,
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
    )
}.exceptionOrNull()?.let { uiText(R.string.connections_validation_username) }

private fun validatePlainText(
    value: String,
    @StringRes labelRes: Int,
    maximumLength: Int,
): UiText? = when {
    value.isEmpty() -> uiText(R.string.connections_validation_required, uiText(labelRes))
    value.length > maximumLength -> uiText(
        R.string.connections_validation_max_characters,
        uiText(labelRes),
        maximumLength,
    )
    value.any(Char::isISOControl) || value.hasUnpairedSurrogateForEditor() ->
        uiText(R.string.connections_validation_unsupported_characters, uiText(labelRes))
    else -> null
}

private fun validateCommand(
    value: String,
    @StringRes labelRes: Int,
    maximumLength: Int,
): UiText? = when {
    value.isBlank() -> uiText(R.string.connections_validation_required, uiText(labelRes))
    value.length > maximumLength -> uiText(
        R.string.connections_validation_max_characters,
        uiText(labelRes),
        maximumLength,
    )
    value.any { it.isISOControl() && it !in "\r\n\t" } || value.hasUnpairedSurrogateForEditor() ->
        uiText(R.string.connections_validation_unsupported_control_characters, uiText(labelRes))
    else -> null
}

private fun isSafeMoshExecutable(value: String): Boolean =
    value.length in 1..ModelLimits.MAX_MOSH_SERVER_COMMAND_LENGTH &&
        value.none(Char::isWhitespace) &&
        value.none(Char::isISOControl) &&
        value.all { it.isLetterOrDigit() || it in "_./+@%:=,-" }

private fun String.hasUnpairedSurrogateForEditor(): Boolean {
    var index = 0
    while (index < length) {
        when {
            this[index].isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return true
                index += 2
            }
            this[index].isLowSurrogate() -> return true
            else -> index += 1
        }
    }
    return false
}

internal data class SnippetEditorDraft(
    val persistentId: String? = null,
    val name: String = "",
    val group: String = "",
    val command: String = "",
    val tapAction: SnippetTapAction = SnippetTapAction.INSERT,
    val appendEnter: Boolean = false,
    val confirmMultilineExecution: Boolean = true,
    val isFavourite: Boolean = false,
)

internal data class SnippetEditorErrors(
    val name: UiText? = null,
    val group: UiText? = null,
    val command: UiText? = null,
) {
    val isEmpty: Boolean get() = name == null && group == null && command == null
}

internal data class SnippetEditorValidation(
    val snippet: Snippet?,
    val errors: SnippetEditorErrors,
)

internal fun validateSnippetEditor(
    draft: SnippetEditorDraft,
    nowEpochMillis: Long,
): SnippetEditorValidation {
    val name = draft.name.trim()
    val group = draft.group.trim().takeIf(String::isNotEmpty)
    val nameError = validatePlainText(
        name,
        R.string.connections_field_snippet_name,
        ModelLimits.MAX_DISPLAY_NAME_LENGTH,
    )
    val groupError = group?.let {
        validatePlainText(it, R.string.connections_field_group, ModelLimits.MAX_GROUP_LENGTH)
    }
    val commandError = validateCommand(
        draft.command,
        R.string.connections_field_command,
        ModelLimits.MAX_COMMAND_LENGTH,
    )
    val errors = SnippetEditorErrors(nameError, groupError, commandError)
    if (!errors.isEmpty) return SnippetEditorValidation(null, errors)
    val multiline = draft.command.any { it == '\r' || it == '\n' }
    return SnippetEditorValidation(
        snippet = Snippet(
            id = draft.persistentId ?: UUID.randomUUID().toString(),
            name = name,
            group = group,
            command = draft.command,
            tapAction = draft.tapAction,
            appendEnter = draft.appendEnter,
            confirmMultilineExecution = if (draft.tapAction == SnippetTapAction.SEND_IMMEDIATELY && multiline) {
                true
            } else {
                draft.confirmMultilineExecution
            },
            isFavorite = draft.isFavourite,
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
        ),
        errors = errors,
    )
}

internal fun Snippet.toEditorDraft(): SnippetEditorDraft = SnippetEditorDraft(
    persistentId = id,
    name = name,
    group = group.orEmpty(),
    command = command,
    tapAction = tapAction,
    appendEnter = appendEnter,
    confirmMultilineExecution = confirmMultilineExecution,
    isFavourite = isFavorite,
)

internal enum class ConnectionTestStage {
    DNS,
    TCP,
    SSH_NEGOTIATION,
    HOST_KEY,
    AUTHENTICATION,
    SHELL,
}

internal sealed interface HostConnectionTestResult {
    data class Success(
        val completedStages: Set<ConnectionTestStage>,
        /** True means Test Connection intentionally avoided arbitrary saved remote input. */
        val startupCommandSkipped: Boolean = false,
    ) : HostConnectionTestResult

    data class HostKeyApprovalRequired(
        val prompt: HostIdentityPrompt,
        val completedStages: Set<ConnectionTestStage>,
    ) : HostConnectionTestResult

    data class KeyboardInteractiveRequired(
        val challenge: KeyboardInteractiveChallenge,
        val completedStages: Set<ConnectionTestStage>,
    ) : HostConnectionTestResult

    data class Failed(
        val stage: ConnectionTestStage,
        val message: UiText,
        val completedStages: Set<ConnectionTestStage>,
    ) : HostConnectionTestResult {
        /** Test/probe bridge for externally supplied safe detail. */
        constructor(
            stage: ConnectionTestStage,
            message: String,
            completedStages: Set<ConnectionTestStage>,
        ) : this(stage, UiText.Dynamic(message), completedStages)
    }
}

/** Non-secret presentation for the exact ViewModel-owned Test Connection transport. */
internal sealed interface HostConnectionTestUiState {
    val operationToken: Long?

    data object Idle : HostConnectionTestUiState {
        override val operationToken: Long? = null
    }

    data class Running(
        override val operationToken: Long,
    ) : HostConnectionTestUiState

    data class AwaitingHostIdentity(
        override val operationToken: Long,
        val prompt: HostIdentityPrompt,
    ) : HostConnectionTestUiState

    data class AwaitingKeyboardInteractive(
        override val operationToken: Long,
        val challenge: KeyboardInteractiveChallenge,
    ) : HostConnectionTestUiState

    data class Complete(
        override val operationToken: Long,
        val result: HostConnectionTestResult,
    ) : HostConnectionTestUiState
}

internal data class HostConnectRequest(
    val persistentHostId: String,
    val secret: CharArray = CharArray(0),
    val forceSsh: Boolean = false,
) {
    fun wipe() = secret.fill('\u0000')
}

internal data class KeyGenerationRequest(
    val name: String,
    val algorithm: com.yanjiyu.terminalspike.core.data.repository.SshKeyGenerationAlgorithm,
    val passphrase: CharArray,
    val comment: String?,
) {
    fun wipe() = passphrase.fill('\u0000')
}

private const val VALIDATION_UUID = "00000000-0000-4000-8000-000000000001"
private const val VALIDATION_DISPLAY_NAME = "Host"
private const val VALIDATION_USERNAME = "user"
private const val VALIDATION_HOSTNAME = "example.invalid"
