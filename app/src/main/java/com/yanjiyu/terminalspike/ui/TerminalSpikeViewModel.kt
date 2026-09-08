package com.yanjiyu.terminalspike.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.yanjiyu.terminalspike.AndroidLocalAppDataResetter
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.DuplicateSshSessionResult
import com.yanjiyu.terminalspike.connection.HostIdentityDecision
import com.yanjiyu.terminalspike.connection.HostIdentityPrompt
import com.yanjiyu.terminalspike.connection.KeyboardInteractiveChallenge
import com.yanjiyu.terminalspike.connection.KnownHostSummary
import com.yanjiyu.terminalspike.connection.MoshBootstrapRequest
import com.yanjiyu.terminalspike.connection.DEFAULT_MOSH_LOCALE
import com.yanjiyu.terminalspike.connection.RemoteSessionConnectionRequest
import com.yanjiyu.terminalspike.connection.RemoteSessionConnectionSeed
import com.yanjiyu.terminalspike.connection.RemoteClipboardWriteRequestEvent
import com.yanjiyu.terminalspike.connection.RemoteSessionStartRequest
import com.yanjiyu.terminalspike.connection.RemoteSessionTerminalConfiguration
import com.yanjiyu.terminalspike.connection.RemoteSessionReliabilityPolicy
import com.yanjiyu.terminalspike.connection.SessionNotificationVisibility
import com.yanjiyu.terminalspike.connection.SshConnectionConfig
import com.yanjiyu.terminalspike.connection.SshAuthentication
import com.yanjiyu.terminalspike.connection.SshSessionSnapshot
import com.yanjiyu.terminalspike.connection.StartSshSessionResult
import com.yanjiyu.terminalspike.connection.TmuxSessionCatalog
import com.yanjiyu.terminalspike.connection.isTmuxSessionId
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionStatus
import com.yanjiyu.terminalspike.core.data.repository.TerminalDataCatalog
import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeDataState
import com.yanjiyu.terminalspike.core.data.repository.CatalogSecretAvailability
import com.yanjiyu.terminalspike.core.data.repository.HostAuthenticationUpdate
import com.yanjiyu.terminalspike.core.data.repository.JschPrivateKeyMetadataInspector
import com.yanjiyu.terminalspike.core.data.repository.JschSshKeyMaterialGenerator
import com.yanjiyu.terminalspike.core.data.repository.TerminalDataCatalogLoadResult
import com.yanjiyu.terminalspike.core.data.repository.TerminalDataCommittedRefreshException
import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.BellSettings
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.ModelLimits
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy
import com.yanjiyu.terminalspike.core.model.RecentHostActivity
import com.yanjiyu.terminalspike.core.model.RecentSession
import com.yanjiyu.terminalspike.core.model.ReconnectPolicy
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.core.model.SessionState
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.model.CustomTerminalTheme
import com.yanjiyu.terminalspike.core.model.SshKeyIdentity
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.core.model.SshAuthentication as StoredSshAuthentication
import com.yanjiyu.terminalspike.core.security.AppLogEvent
import com.yanjiyu.terminalspike.core.security.AppLogger
import com.yanjiyu.terminalspike.core.security.credential.CredentialStoreException
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.settings.CustomTerminalFontStore
import com.yanjiyu.terminalspike.settings.SavedHostConnectionCompatibility
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.SavedSshIdentity
import com.yanjiyu.terminalspike.settings.SettingsLoadFailure
import com.yanjiyu.terminalspike.settings.SettingsLoadResult
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.connection.SftpClient
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.TerminalTranscriptSnapshot
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import com.yanjiyu.terminalspike.terminal.model.TerminalThemes
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import com.yanjiyu.terminalspike.terminal.view.AccessoryModifierSnapshot
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryAction
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryDispatch
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryModifier
import com.yanjiyu.terminalspike.terminal.view.TerminalKeySequences
import com.yanjiyu.terminalspike.terminal.view.PastedImageSizeLimitInputStream
import com.yanjiyu.terminalspike.terminal.view.PastedImageTooLargeException
import com.yanjiyu.terminalspike.terminal.view.TerminalImagePasteSource
import com.yanjiyu.terminalspike.terminal.view.TerminalTypefaceRegistry
import com.yanjiyu.terminalspike.terminal.view.accessoryModifierState
import com.yanjiyu.terminalspike.terminal.view.encodeTerminalChord
import com.yanjiyu.terminalspike.terminal.view.resolve
import com.yanjiyu.terminalspike.terminal.view.pastedImageExtension
import com.yanjiyu.terminalspike.terminal.view.toAccessoryAction
import com.yanjiyu.terminalspike.ui.connections.ConnectionsLoadState
import com.yanjiyu.terminalspike.ui.connections.ConnectionsInteractionAction
import com.yanjiyu.terminalspike.ui.connections.ConnectionsInteractionState
import com.yanjiyu.terminalspike.ui.connections.ConnectionsTab
import com.yanjiyu.terminalspike.ui.connections.ConnectionsUiState
import com.yanjiyu.terminalspike.ui.connections.ConnectionTestStage
import com.yanjiyu.terminalspike.ui.connections.HostSort
import com.yanjiyu.terminalspike.ui.connections.HostAuthenticationMethod
import com.yanjiyu.terminalspike.ui.connections.HostConnectRequest
import com.yanjiyu.terminalspike.ui.connections.HostConnectionTestCoordinator
import com.yanjiyu.terminalspike.ui.connections.HostConnectionTestResult
import com.yanjiyu.terminalspike.ui.connections.HostConnectionTestInteraction
import com.yanjiyu.terminalspike.ui.connections.HostConnectionTestSpec
import com.yanjiyu.terminalspike.ui.connections.HostConnectionTestUiState
import com.yanjiyu.terminalspike.ui.connections.HostEditorDraft
import com.yanjiyu.terminalspike.ui.connections.HostEditorSubmission
import com.yanjiyu.terminalspike.ui.connections.JschHostConnectionTester
import com.yanjiyu.terminalspike.ui.connections.KeyGenerationRequest
import com.yanjiyu.terminalspike.ui.connections.PreparedHostConnectionTest
import com.yanjiyu.terminalspike.ui.connections.buildConnectionsReadyState
import com.yanjiyu.terminalspike.ui.settings.toRuntimeExtraKeysOrNull
import com.yanjiyu.terminalspike.ui.settings.toRuntimeAccessoryActionsOrNull
import com.yanjiyu.terminalspike.ui.settings.defaultRuntimeAccessoryActions
import com.yanjiyu.terminalspike.ui.settings.upgradeShippedKeyboardDeck
import com.yanjiyu.terminalspike.ui.sftp.SftpSecretKind
import com.yanjiyu.terminalspike.ui.sftp.SftpSessionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.io.InputStream
import java.nio.CharBuffer
import java.util.Locale
import java.util.UUID
import java.net.InetAddress

internal const val LOCAL_TERMINAL_SESSION_ID = 0L
internal const val WORKSPACE_SSH_SESSION_FALLBACK = "SSH session"
internal const val WORKSPACE_MOSH_SESSION_FALLBACK = "Mosh session"
private const val WORKSPACE_RECENT_CONNECTION_LIMIT = 8
internal const val DEFAULT_MOSH_SERVER_EXECUTABLE = "mosh-server"

/** Non-secret transport options passed from connection UI into one fresh runtime. */
internal data class RemoteConnectionOptions(
    val protocol: ConnectionProtocol = ConnectionProtocol.SSH,
    val moshPort: Int? = null,
    val moshPortRange: MoshPortRange? = null,
    /** Null means the pinned default [DEFAULT_MOSH_SERVER_EXECUTABLE]. */
    val moshServerCommand: String? = null,
    val moshLocale: String? = null,
    val moshFallbackPolicy: MoshFallbackPolicy = MoshFallbackPolicy.NEVER,
) {
    init {
        require(moshPort == null || moshPort in 1..65_535)
        require(moshPort == null || moshPortRange == null)
        if (protocol == ConnectionProtocol.SSH) {
            require(
                moshPort == null && moshPortRange == null && moshServerCommand == null &&
                    moshLocale == null && moshFallbackPolicy == MoshFallbackPolicy.NEVER,
            )
        }
        moshServerCommand?.let { require(isSafeMoshServerExecutable(it)) }
        moshLocale?.let { locale ->
            require(
                locale.length in "C.UTF-8".length..ModelLimits.MAX_MOSH_LOCALE_LENGTH &&
                    locale.first().isLetterOrDigit() && locale.endsWith(".UTF-8") &&
                    locale.all { character ->
                        character.code in 0x21..0x7e &&
                            (character.isLetterOrDigit() || character in "_.@-")
                    },
            )
        }
    }

    companion object {
        val SSH = RemoteConnectionOptions()
    }
}

internal data class MoshOptionsParseResult(
    val options: RemoteConnectionOptions?,
    val error: UiText?,
)

/**
 * Parses the deliberately small Mosh advanced surface. The executable is one argv value, never a
 * shell fragment; invalid persisted legacy fragments are handled separately and never executed.
 */
internal fun parseMoshConnectionOptions(
    udpPortOrRange: String,
    serverExecutable: String,
): MoshOptionsParseResult {
    val normalizedPort = udpPortOrRange.trim()
    val parsedPort: Int?
    val parsedRange: MoshPortRange?
    when {
        normalizedPort.isEmpty() -> {
            parsedPort = null
            parsedRange = null
        }
        ':' !in normalizedPort -> {
            val port = normalizedPort.toIntOrNull()
            if (port == null || port !in 1..65_535) {
                return MoshOptionsParseResult(null, uiText(R.string.mosh_udp_port_invalid))
            }
            parsedPort = port
            parsedRange = null
        }
        else -> {
            val parts = normalizedPort.split(':')
            val first = parts.getOrNull(0)?.toIntOrNull()
            val last = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || first == null || last == null) {
                return MoshOptionsParseResult(null, uiText(R.string.mosh_udp_port_or_range_invalid))
            }
            val range = runCatching { MoshPortRange(first, last) }.getOrNull()
                ?: return MoshOptionsParseResult(
                    null,
                    uiText(R.string.mosh_udp_range_invalid, ModelLimits.MAX_MOSH_PORT_RANGE_SIZE),
                )
            parsedPort = null
            parsedRange = range
        }
    }

    val normalizedExecutable = serverExecutable.trim()
    if (normalizedExecutable.isNotEmpty() && !isSafeMoshServerExecutable(normalizedExecutable)) {
        return MoshOptionsParseResult(
            null,
            uiText(R.string.mosh_server_executable_invalid),
        )
    }
    val storedExecutable = normalizedExecutable
        .takeUnless { it.isEmpty() || it == DEFAULT_MOSH_SERVER_EXECUTABLE }
    return MoshOptionsParseResult(
        options = RemoteConnectionOptions(
            protocol = ConnectionProtocol.MOSH,
            moshPort = parsedPort,
            moshPortRange = parsedRange,
            moshServerCommand = storedExecutable,
        ),
        error = null,
    )
}

private fun isSafeMoshServerExecutable(value: String): Boolean =
    value.length in 1..ModelLimits.MAX_MOSH_SERVER_COMMAND_LENGTH &&
        value == value.trim() && value.none(Char::isWhitespace) && value.none(Char::isISOControl) &&
        value.all { character -> character.isLetterOrDigit() || character in "_./+@%:=,-" }

private fun SavedSshProfile.remoteConnectionOptionsOrNull(): RemoteConnectionOptions? = runCatching {
    when {
        connectionCompatibility in setOf(
            SavedHostConnectionCompatibility.SSH_PASSWORD,
            SavedHostConnectionCompatibility.PROFILE_OPTIONS_REQUIRE_FULL_UI,
        ) &&
            protocol == ConnectionProtocol.SSH -> RemoteConnectionOptions.SSH
        connectionCompatibility in setOf(
            SavedHostConnectionCompatibility.MOSH_PASSWORD,
            SavedHostConnectionCompatibility.PROFILE_OPTIONS_REQUIRE_FULL_UI,
        ) &&
            protocol == ConnectionProtocol.MOSH -> RemoteConnectionOptions(
            protocol = ConnectionProtocol.MOSH,
            moshPort = moshPort,
            moshPortRange = moshPortRange,
            // Older development builds could persist a whole command line. Never execute it: an
            // invalid value falls back to the pinned, separately quoted mosh-server executable.
            moshServerCommand = moshServerCommand
                ?.takeIf(::isSafeMoshServerExecutable)
                ?.takeUnless { it == DEFAULT_MOSH_SERVER_EXECUTABLE },
        )
        else -> null
    }
}.getOrNull()

/** Authoritative password hosts can use the full runtime even when they carry profile overrides. */
internal val SavedSshProfile.canConnectFromQuickUi: Boolean
    get() = canConnectFromCompatibilityUi ||
        (persistentId != null &&
            connectionCompatibility == SavedHostConnectionCompatibility.PROFILE_OPTIONS_REQUIRE_FULL_UI)

internal fun SavedSshProfile.connectionSeed(): SshConnectionSeed = SshConnectionSeed(
    host = host,
    port = port,
    username = username,
    sourceProfileId = id,
    connectionOptions = remoteConnectionOptionsOrNull() ?: when (protocol) {
        ConnectionProtocol.SSH -> RemoteConnectionOptions.SSH
        ConnectionProtocol.MOSH -> RemoteConnectionOptions(protocol = ConnectionProtocol.MOSH)
    },
)

internal fun RemoteConnectionOptions.toMoshBootstrapRequest(
    sshConfig: SshConnectionConfig,
): MoshBootstrapRequest {
    require(protocol == ConnectionProtocol.MOSH) { "Only Mosh options can build a Mosh bootstrap request." }
    return MoshBootstrapRequest(
        ssh = sshConfig,
        serverCommand = moshServerCommand,
        udpPort = moshPort,
        udpPortRange = moshPortRange,
        locale = moshLocale ?: DEFAULT_MOSH_LOCALE,
    )
}

private fun ConnectionProtocol.workspaceFallbackName(): String = when (this) {
    ConnectionProtocol.SSH -> WORKSPACE_SSH_SESSION_FALLBACK
    ConnectionProtocol.MOSH -> WORKSPACE_MOSH_SESSION_FALLBACK
}

/**
 * Mosh API v1 has no TERM/startup-input fields. Surface mismatches without putting the command
 * itself into presentation state; the SSH bootstrap remains solely the fixed mosh-server launch.
 */
internal fun moshRuntimeLimitNotice(
    protocol: ConnectionProtocol,
    selectedTerminalType: String?,
    startupCommandConfigured: Boolean,
): UiText? {
    if (protocol != ConnectionProtocol.MOSH) return null
    return buildList {
        if (selectedTerminalType != TerminalProfile.DEFAULT_TERM_VALUE) {
            add(
                uiText(R.string.notice_mosh_fixed_term, TerminalProfile.DEFAULT_TERM_VALUE),
            )
        }
        if (startupCommandConfigured) {
            add(uiText(R.string.notice_mosh_startup_ssh_only))
        }
    }.toNotice()
}

private fun List<UiText>.toNotice(): UiText? = when (size) {
    0 -> null
    1 -> single()
    else -> reduce { first, second -> uiText(R.string.notice_pair, first, second) }
}

/**
 * The minimum non-secret information needed to open the connection dialog again.
 *
 * Authentication is deliberately absent: ad-hoc sessions must ask again, while a still-matching
 * saved profile may resolve its encrypted credential through the authoritative data repository.
 */
internal data class SshConnectionSeed(
    val host: String,
    val port: Int,
    val username: String,
    val sourceProfileId: Long? = null,
    val connectionOptions: RemoteConnectionOptions = RemoteConnectionOptions.SSH,
) {
    fun matches(profile: SavedSshProfile): Boolean =
        sourceProfileId == profile.id &&
            profile.canConnectFromQuickUi &&
            profile.host.equals(host, ignoreCase = true) &&
            profile.port == port &&
            profile.username == username &&
            profile.remoteConnectionOptionsOrNull() == connectionOptions
}

internal enum class SshConnectPurpose {
    NEW,
    RECONNECT,
    DUPLICATE,
}

internal data class SshConnectDialogRequest(
    val purpose: SshConnectPurpose,
    val seed: SshConnectionSeed? = null,
    val replacementSessionId: Long? = null,
) {
    init {
        require((purpose == SshConnectPurpose.RECONNECT) == (replacementSessionId != null)) {
            "Only reconnect requests may replace an existing session."
        }
        require(purpose == SshConnectPurpose.NEW || seed != null) {
            "Session actions require connection details."
        }
    }
}

data class SessionTabUi(
    val id: Long,
    val title: String,
    val connectionState: ConnectionState,
    val isLocalTerminal: Boolean = false,
    val isLocalArch: Boolean = false,
    val workspaceName: String = WORKSPACE_SSH_SESSION_FALLBACK,
    val protocol: ConnectionProtocol = ConnectionProtocol.SSH,
    val terminalTitle: String? = null,
    val taskStatus: com.yanjiyu.terminalspike.terminal.TerminalTaskStatus =
        com.yanjiyu.terminalspike.terminal.TerminalTaskStatus(),
    val lastActivityAtEpochMillis: Long = 0,
    val recentSessionId: String? = null,
    val endpointIdentityToken: String? = null,
    val sourceProfileId: Long? = null,
    /** Null follows the app default; non-null is the saved host's explicit override. */
    val terminalProfileId: String? = null,
    /** Null follows the app default; non-null is the saved host's explicit override. */
    val keyboardProfileId: String? = null,
    val moshFallbackPolicy: MoshFallbackPolicy = MoshFallbackPolicy.NEVER,
)

/** Privacy-safe metadata for one transient OSC 52 decision dialog. */
data class RemoteClipboardPromptUi(
    val sessionId: Long,
    val requestId: Long,
)

internal enum class WorkspaceSessionStatus {
    CONNECTING,
    CONNECTED,
    AWAITING_APPROVAL,
    DISCONNECTED,
    FAILED,
}

internal data class WorkspaceActiveSessionUi(
    val id: Long,
    val isLocalArch: Boolean = false,
    val friendlyName: String,
    val protocol: ConnectionProtocol,
    val status: WorkspaceSessionStatus,
    val terminalTitle: String?,
    val lastActivityAtEpochMillis: Long,
    val canReconnect: Boolean,
    val canDisconnect: Boolean,
    val canDuplicate: Boolean,
)

internal data class WorkspacePinnedHostUi(
    val profileId: Long,
    val friendlyName: String,
    val protocol: ConnectionProtocol,
    val canConnect: Boolean,
    val endpoint: String = "",
)

internal data class WorkspaceRecentSessionUi(
    val id: String,
    val friendlyName: String,
    val protocol: ConnectionProtocol,
    val status: WorkspaceSessionStatus,
    val terminalTitle: String?,
    val lastActivityAtEpochMillis: Long,
    val sourceProfileId: Long?,
)

internal data class WorkspaceUiState(
    val activeSessions: List<WorkspaceActiveSessionUi> = emptyList(),
    val pinnedHosts: List<WorkspacePinnedHostUi> = emptyList(),
    val recentConnections: List<WorkspaceRecentSessionUi> = emptyList(),
)

/**
 * A Recent row represents a connection endpoint, not one historical terminal process. Saved
 * profiles are deliberately allowed to duplicate an endpoint for different options, so the
 * presentation key is derived from the actual protocol/user/host/port tuple rather than UUID.
 */
private sealed interface WorkspaceRecentIdentity {
    data class EndpointToken(val value: String) : WorkspaceRecentIdentity

    data class Connection(
        val protocol: ConnectionProtocol,
        val normalizedHost: String,
        val port: Int,
        val username: String,
    ) : WorkspaceRecentIdentity

    /** Unsaved/deleted endpoints do not retain enough private metadata to merge safely. */
    data class Session(val id: String) : WorkspaceRecentIdentity
}

private fun SavedSshProfile.workspaceConnectionKey(): WorkspaceRecentIdentity.Connection =
    WorkspaceRecentIdentity.Connection(
        protocol = protocol,
        normalizedHost = host.lowercase(Locale.ROOT),
        port = port,
        username = username,
    )

data class TerminalSpikeUiState(
    val ctrlArmed: Boolean = false,
    val altArmed: Boolean = false,
    val shiftArmed: Boolean = false,
    val ctrlLocked: Boolean = false,
    val altLocked: Boolean = false,
    val shiftLocked: Boolean = false,
    val sessions: List<SessionTabUi> = initialTerminalTabs(),
    val activeSessionId: Long = LOCAL_TERMINAL_SESSION_ID,
    val notice: UiText? = null,
    val profiles: List<SavedSshProfile> = emptyList(),
    val snippets: List<CommandSnippet> = emptyList(),
    val identities: List<SavedSshIdentity> = emptyList(),
    val knownHosts: List<KnownHostSummary> = emptyList(),
    val extraKeys: List<TerminalExtraKey> = TerminalExtraKey.DEFAULT_ORDER,
    val accessoryActions: List<TerminalAccessoryAction> =
        defaultRuntimeAccessoryActions(),
    val keyboardRuntimeCompatible: Boolean = true,
    val runtimeProfilesReady: Boolean = false,
    val keyboardLayout: KeyboardLayout = KeyboardLayout.TWO_ROWS,
    val modifierBehavior: ModifierBehavior = ModifierBehavior.ONE_SHOT,
    val keyboardHapticsEnabled: Boolean = false,
    val keyRepeatEnabled: Boolean = true,
    val terminalInputMode: TerminalInputMode = TerminalInputMode.RAW,
    val keepScreenOnWhileTerminalVisible: Boolean = false,
    val notificationPermissionPromptVisible: Boolean = false,
    val remoteClipboardPrompt: RemoteClipboardPromptUi? = null,
    val multilinePasteConfirmationEnabled: Boolean = false,
    val voiceInputLanguageTag: String = "",
    val settingsReady: Boolean = false,
    val settingsRecoveryFailure: SettingsLoadFailure? = null,
    val settingsRecoveryInProgress: Boolean = false,
    val pendingSnippetSendId: Long? = null,
    val pendingSnippetTargetSessionId: Long? = null,
    val pendingSnippetSendsImmediately: Boolean? = null,
    val recentSessions: List<RecentSession> = emptyList(),
    val moshExtension: MoshExtensionUiState = MoshExtensionStatus.Checking.toUiState(),
) {
    val activeSession: SessionTabUi
        get() = sessions.firstOrNull { it.id == activeSessionId } ?: sessions.first()
    val sshMode: Boolean get() = !activeSession.isLocalTerminal
    val connectionState: ConnectionState get() = activeSession.connectionState
    val canAddSshSession: Boolean get() = true
    val canSendTerminalInput: Boolean
        get() = activeSession.isLocalTerminal || activeSession.connectionState is ConnectionState.Connected
    internal val workspace: WorkspaceUiState
        get() {
            val profilesByPersistentId = profiles.mapNotNull { profile ->
                profile.persistentId?.let { persistentId -> persistentId to profile }
            }.toMap()
            val profilesByPresentationId = profiles.associateBy(SavedSshProfile::id)
            val openRecentSessionIds = sessions.mapNotNull(SessionTabUi::recentSessionId).toSet()
            val openEndpointIdentityTokens =
                sessions.mapNotNull(SessionTabUi::endpointIdentityToken).toSet()
            val openConnectionKeys = sessions.mapNotNull { session ->
                session.sourceProfileId
                    ?.let(profilesByPresentationId::get)
                    ?.workspaceConnectionKey()
            }.toSet()
            return WorkspaceUiState(
                activeSessions = sessions.filterNot(SessionTabUi::isLocalTerminal).map { session ->
                    session.toWorkspaceActiveSession(canAddSshSession)
                },
                pinnedHosts = profiles
                    .sortedWith(
                        compareByDescending<SavedSshProfile>(SavedSshProfile::isFavorite)
                            .thenBy { it.label.lowercase(Locale.ROOT) }
                            .thenBy(SavedSshProfile::id),
                    )
                    .map { profile ->
                        WorkspacePinnedHostUi(
                            profileId = profile.id,
                            friendlyName = privacySafeWorkspaceFriendlyName(
                                candidate = profile.label,
                                protocol = profile.protocol,
                                sensitiveValues = listOf(profile.username, profile.host),
                            ),
                            endpoint = profile.host + if (profile.port == 22) "" else ":${profile.port}",
                            protocol = profile.protocol,
                            // Room-backed hosts launch through the authoritative catalog path,
                            // which supports private keys, keyboard-interactive auth, and full
                            // host options. Legacy rows keep the narrower compatibility gate.
                            canConnect = profile.persistentId != null ||
                                (profile.canConnectFromCompatibilityUi &&
                                    (profile.protocol != ConnectionProtocol.MOSH ||
                                        moshExtension.isAvailable)),
                        )
                    },
                recentConnections = recentSessions
                    .sortedWith(
                        compareByDescending<RecentSession>(RecentSession::lastActivityAtEpochMillis)
                            .thenBy(RecentSession::id),
                    )
                    .filterNot { session -> session.id in openRecentSessionIds }
                    .filterNot { session ->
                        session.endpointIdentityToken?.let { it in openEndpointIdentityTokens } == true
                    }
                    .map { session ->
                        session to session.hostProfileId
                            ?.let(profilesByPersistentId::get)
                            ?.workspaceConnectionKey()
                    }
                    .filterNot { (_, connectionKey) -> connectionKey in openConnectionKeys }
                    .distinctBy { (session, connectionKey) ->
                        session.endpointIdentityToken
                            ?.let { WorkspaceRecentIdentity.EndpointToken(it) }
                            ?: connectionKey
                            ?: WorkspaceRecentIdentity.Session(session.id)
                    }
                    .take(WORKSPACE_RECENT_CONNECTION_LIMIT)
                    .map { (session, _) ->
                        val sourceProfile = session.hostProfileId?.let(profilesByPersistentId::get)
                        WorkspaceRecentSessionUi(
                            id = session.id,
                            friendlyName = privacySafeWorkspaceFriendlyName(
                                candidate = session.hostDisplayName,
                                protocol = session.protocol,
                                sensitiveValues = listOfNotNull(
                                    sourceProfile?.username,
                                    sourceProfile?.host,
                                ),
                            ),
                            protocol = session.protocol,
                            status = session.state.toWorkspaceStatus(),
                            terminalTitle = sanitizeWorkspaceTerminalTitle(
                                raw = session.terminalTitle,
                                sensitiveValues = listOfNotNull(
                                    sourceProfile?.username,
                                    sourceProfile?.host,
                                ),
                            ),
                            lastActivityAtEpochMillis = session.lastActivityAtEpochMillis,
                            sourceProfileId = sourceProfile?.id,
                        )
                    },
            )
        }
}

internal fun TerminalSpikeUiState.preferredTerminalEntrySessionId(
    lastActiveRemoteSessionId: Long?,
): Long? {
    val activeRemoteSession = sessions.firstOrNull {
        it.id == activeSessionId && !it.isLocalTerminal
    }
    if (activeRemoteSession != null) return activeRemoteSession.id

    val rememberedRemoteSession = sessions.firstOrNull {
        it.id == lastActiveRemoteSessionId && !it.isLocalTerminal
    }
    return rememberedRemoteSession?.id ?: sessions.lastOrNull { !it.isLocalTerminal }?.id
}

internal fun SessionTabUi.toWorkspaceActiveSession(canAddSession: Boolean): WorkspaceActiveSessionUi {
    val canStartAgain = connectionState is ConnectionState.Disconnected ||
        connectionState is ConnectionState.Failed
    return WorkspaceActiveSessionUi(
        id = id,
        isLocalArch = isLocalArch,
        friendlyName = privacySafeWorkspaceFriendlyName(
            candidate = workspaceName,
            protocol = protocol,
            sensitiveValues = listOf(title),
        ),
        protocol = protocol,
        status = connectionState.toWorkspaceStatus(),
        terminalTitle = sanitizeWorkspaceTerminalTitle(
            raw = terminalTitle,
            sensitiveValues = listOf(title),
        ),
        lastActivityAtEpochMillis = lastActivityAtEpochMillis,
        canReconnect = canStartAgain && !isLocalArch,
        canDisconnect = connectionState is ConnectionState.Connecting ||
            connectionState is ConnectionState.Connected ||
            connectionState is ConnectionState.Reconnecting ||
            connectionState is ConnectionState.AwaitingApproval,
        canDuplicate = canAddSession,
    )
}

internal fun nextDefaultSessionName(
    protocol: ConnectionProtocol,
    sessions: List<SessionTabUi>,
): String {
    val prefix = protocol.name.lowercase(Locale.ROOT)
    val used = sessions.asSequence()
        .filterNot(SessionTabUi::isLocalTerminal)
        .map { it.workspaceName.lowercase(Locale.ROOT) }
        .toSet()
    var suffix = 1
    while ("$prefix-$suffix" in used) suffix += 1
    return "$prefix-$suffix"
}

internal fun TerminalSpikeUiState.canStartSshSession(replacementSessionId: Long?): Boolean {
    if (replacementSessionId == null) return canAddSshSession
    val replacement = sessions.firstOrNull { it.id == replacementSessionId && !it.isLocalTerminal }
        ?: return false
    return replacement.connectionState is ConnectionState.Disconnected ||
        replacement.connectionState is ConnectionState.Failed
}

internal enum class WorkspaceProfileLaunchDecision {
    START_WITH_SAVED_PASSWORD,
    REQUEST_AUTHENTICATION,
    SETTINGS_UNAVAILABLE,
    KEYBOARD_RUNTIME_UNAVAILABLE,
    PROFILE_MISSING,
    PROFILE_UNAVAILABLE,
    MOSH_EXTENSION_UNAVAILABLE,
    SESSION_LIMIT_REACHED,
}

internal enum class WorkspaceStoredAuthenticationDecision {
    START_DIRECTLY,
    REQUEST_AUTHENTICATION,
    UNAVAILABLE,
}

/**
 * Resolves only non-secret catalog metadata. Secret bytes remain owned by the repository and are
 * copied, claimed, and wiped later through [HostConnectRequest].
 */
internal fun TerminalDataCatalog.workspaceStoredAuthenticationDecision(
    persistentHostId: String,
): WorkspaceStoredAuthenticationDecision {
    val profile = hosts.firstOrNull { it.profile.id == persistentHostId }?.profile
        ?: return WorkspaceStoredAuthenticationDecision.UNAVAILABLE
    val credential = profile.credentialId?.let { credentialId ->
        credentials.firstOrNull { it.id == credentialId }
    }
    return when (val authentication = credential?.authentication) {
        is StoredSshAuthentication.Password, null -> {
            if (credential?.savedSecretAvailability == CatalogSecretAvailability.AVAILABLE) {
                WorkspaceStoredAuthenticationDecision.START_DIRECTLY
            } else {
                WorkspaceStoredAuthenticationDecision.REQUEST_AUTHENTICATION
            }
        }
        is StoredSshAuthentication.KeyboardInteractive ->
            // The SSH challenge dialog safely collects responses when no reusable response exists.
            WorkspaceStoredAuthenticationDecision.START_DIRECTLY
        is StoredSshAuthentication.PrivateKey -> {
            val identity = identities.firstOrNull { it.id == authentication.keyIdentityId }
            when {
                identity?.privateKeyAvailability != CatalogSecretAvailability.AVAILABLE ->
                    WorkspaceStoredAuthenticationDecision.UNAVAILABLE
                !identity.isPassphraseProtected ->
                    WorkspaceStoredAuthenticationDecision.START_DIRECTLY
                credential.savedSecretAvailability == CatalogSecretAvailability.AVAILABLE ->
                    WorkspaceStoredAuthenticationDecision.START_DIRECTLY
                else -> WorkspaceStoredAuthenticationDecision.REQUEST_AUTHENTICATION
            }
        }
    }
}

/**
 * Maps the legacy Quick Connect controls onto the authoritative authentication aggregate. A key
 * passphrase is intentionally absent: it remains owned by the one connection attempt.
 */
internal fun quickConnectAuthenticationUpdate(
    persistentIdentityId: String?,
    savePassword: Boolean,
    password: ByteArray,
    retainSavedPassword: Boolean,
): HostAuthenticationUpdate = when {
    persistentIdentityId != null -> {
        require(!savePassword) { "A private-key connection cannot save a password." }
        HostAuthenticationUpdate.PrivateKey(persistentIdentityId)
    }
    savePassword -> {
        require(password.isNotEmpty()) { "A saved password must not be empty." }
        HostAuthenticationUpdate.SavePassword(password.copyOf())
    }
    retainSavedPassword -> HostAuthenticationUpdate.RetainSavedPassword
    else -> HostAuthenticationUpdate.PromptPassword
}

internal data class QuickConnectPasswordStart(
    val password: ByteArray?,
    val savedPasswordProfile: SavedSshProfile?,
)

/**
 * Switches the first connection to the encrypted credential loader as soon as a requested password
 * save commits. This also wipes the now-unneeded one-shot copy before the transport starts.
 */
internal fun resolveQuickConnectPasswordStart(
    savedPasswordCommitted: Boolean,
    password: ByteArray?,
    persistedProfile: SavedSshProfile?,
    previouslySavedProfile: SavedSshProfile?,
): QuickConnectPasswordStart {
    if (!savedPasswordCommitted) {
        return QuickConnectPasswordStart(password, previouslySavedProfile)
    }
    val savedProfile = requireNotNull(persistedProfile) {
        "A committed saved password must have a persisted host profile."
    }
    require(savedProfile.hasSavedPassword) {
        "A committed saved password must be reflected by its host profile."
    }
    password?.fill(0)
    return QuickConnectPasswordStart(password = null, savedPasswordProfile = savedProfile)
}

/** Builds the complete non-secret host aggregate used by Quick Connect's Save host option. */
internal fun SavedSshProfile.toAuthoritativeQuickConnectProfile(
    catalog: TerminalDataCatalog,
    existing: HostProfile?,
    connectionOptions: RemoteConnectionOptions,
    nowEpochMillis: Long,
): HostProfile =
    HostProfile(
        id = requireNotNull(persistentId) { "A saved host requires a persistent ID." },
        displayName = label,
        hostname = host,
        port = port,
        username = username,
        protocol = connectionOptions.protocol,
        credentialId = existing?.credentialId,
        terminalProfileId = if (existing == null) {
            catalog.defaultTerminalProfileId
        } else {
            existing.terminalProfileId
        },
        keyboardProfileId = if (existing == null) {
            catalog.defaultKeyboardProfileId
        } else {
            existing.keyboardProfileId
        },
        isFavorite = existing?.isFavorite ?: isFavorite,
        group = existing?.group,
        tag = existing?.tag,
        startupCommand = existing?.startupCommand,
        keepaliveIntervalSeconds = existing?.keepaliveIntervalSeconds,
        reconnectPolicy = existing?.reconnectPolicy,
        moshPort = connectionOptions.moshPort,
        moshPortRange = connectionOptions.moshPortRange,
        moshServerCommand = connectionOptions.moshServerCommand,
        moshLocale = connectionOptions.moshLocale,
        moshFallbackPolicy = connectionOptions.moshFallbackPolicy,
        createdAtEpochMillis = existing?.createdAtEpochMillis ?: nowEpochMillis,
        updatedAtEpochMillis = maxOf(nowEpochMillis, existing?.updatedAtEpochMillis ?: 0L),
    )

/**
 * Decides whether a Workspace host can honour a single-tap launch without ever exposing or
 * copying its stored credential into presentation state.
 */
internal fun TerminalSpikeUiState.workspaceProfileLaunchDecision(
    profileId: Long,
): WorkspaceProfileLaunchDecision {
    if (!settingsReady) return WorkspaceProfileLaunchDecision.SETTINGS_UNAVAILABLE
    if (!keyboardRuntimeCompatible) {
        return WorkspaceProfileLaunchDecision.KEYBOARD_RUNTIME_UNAVAILABLE
    }
    val profile = profiles.firstOrNull { it.id == profileId }
        ?: return WorkspaceProfileLaunchDecision.PROFILE_MISSING
    if (!profile.canConnectFromCompatibilityUi) {
        return WorkspaceProfileLaunchDecision.PROFILE_UNAVAILABLE
    }
    if (profile.remoteConnectionOptionsOrNull() == null) {
        return WorkspaceProfileLaunchDecision.PROFILE_UNAVAILABLE
    }
    if (profile.protocol == ConnectionProtocol.MOSH && !moshExtension.isAvailable) {
        return WorkspaceProfileLaunchDecision.MOSH_EXTENSION_UNAVAILABLE
    }
    if (!canStartSshSession(replacementSessionId = null)) {
        return WorkspaceProfileLaunchDecision.SESSION_LIMIT_REACHED
    }
    return if (profile.hasSavedPassword) {
        WorkspaceProfileLaunchDecision.START_WITH_SAVED_PASSWORD
    } else {
        WorkspaceProfileLaunchDecision.REQUEST_AUTHENTICATION
    }
}

internal sealed interface WorkspaceProfileLaunchResult {
    data object Started : WorkspaceProfileLaunchResult
    data class AuthenticationRequired(
        val profileId: Long,
        val persistentHostId: String? = null,
    ) : WorkspaceProfileLaunchResult
    data object Rejected : WorkspaceProfileLaunchResult
}

internal sealed interface SessionDuplicateResult {
    data object Started : SessionDuplicateResult
    data object AuthenticationRequired : SessionDuplicateResult
    data object Rejected : SessionDuplicateResult
}

private fun WorkspaceProfileLaunchDecision.workspaceLaunchRejectionNotice(): UiText = when (this) {
    WorkspaceProfileLaunchDecision.SETTINGS_UNAVAILABLE ->
        uiText(R.string.notice_workspace_settings_unavailable)
    WorkspaceProfileLaunchDecision.KEYBOARD_RUNTIME_UNAVAILABLE ->
        uiText(R.string.notice_keyboard_runtime_unavailable)
    WorkspaceProfileLaunchDecision.PROFILE_MISSING ->
        uiText(R.string.notice_saved_host_unavailable)
    WorkspaceProfileLaunchDecision.PROFILE_UNAVAILABLE ->
        uiText(R.string.notice_saved_host_unsupported)
    WorkspaceProfileLaunchDecision.MOSH_EXTENSION_UNAVAILABLE ->
        uiText(R.string.notice_mosh_extension_unavailable)
    WorkspaceProfileLaunchDecision.SESSION_LIMIT_REACHED ->
        uiText(R.string.notice_session_limit)
    WorkspaceProfileLaunchDecision.START_WITH_SAVED_PASSWORD,
    WorkspaceProfileLaunchDecision.REQUEST_AUTHENTICATION,
    -> error("Successful Workspace launch decisions do not have a rejection notice.")
}

private val MoshExtensionUiState.isAvailable: Boolean
    get() = kind == MoshExtensionUiKind.AVAILABLE

internal fun TerminalSpikeUiState.withStartedSshSession(
    session: SessionTabUi,
    replacementSessionId: Long?,
): TerminalSpikeUiState {
    val updatedSessions = if (replacementSessionId == null) {
        sessions + session
    } else {
        require(session.id == replacementSessionId) { "A reconnect must retain the session ID." }
        require(sessions.any { it.id == replacementSessionId && !it.isLocalTerminal }) {
            "The reconnect target is no longer open."
        }
        sessions.map { existing -> if (existing.id == replacementSessionId) session else existing }
    }
    return copy(
        sessions = updatedSessions,
        activeSessionId = session.id,
    )
}

internal fun TerminalSpikeUiState.withRemoteSessionSnapshots(
    remoteSessions: List<SshSessionSnapshot>,
    preferredSessionId: Long? = null,
): TerminalSpikeUiState {
    val localSessions = sessions.filter { it.isLocalTerminal || it.isLocalArch }
        .ifEmpty { initialTerminalTabs() }
    val remoteTabs = remoteSessions.map { snapshot ->
        SessionTabUi(
            id = snapshot.id,
            title = snapshot.title,
            connectionState = snapshot.connectionState,
            workspaceName = snapshot.workspaceName,
            protocol = snapshot.protocol,
            terminalTitle = snapshot.terminalTitle,
            taskStatus = snapshot.taskStatus,
            lastActivityAtEpochMillis = snapshot.lastActivityAtEpochMillis,
            recentSessionId = snapshot.recentSessionId,
            endpointIdentityToken = snapshot.endpointIdentityToken,
            sourceProfileId = snapshot.sourceProfileId,
            terminalProfileId = snapshot.terminalProfileId,
            keyboardProfileId = snapshot.keyboardProfileId,
            moshFallbackPolicy = snapshot.moshFallbackPolicy,
        )
    }
    val updatedSessions = localSessions + remoteTabs
    val validIds = updatedSessions.mapTo(mutableSetOf(), SessionTabUi::id)
    val active = when {
        preferredSessionId != null && preferredSessionId in validIds -> preferredSessionId
        activeSessionId in validIds -> activeSessionId
        remoteTabs.isNotEmpty() -> remoteTabs.last().id
        else -> LOCAL_TERMINAL_SESSION_ID
    }
    val retainedClipboardPrompt = remoteClipboardPrompt?.takeIf { prompt ->
        remoteTabs.any { tab ->
            tab.id == prompt.sessionId && tab.connectionState is ConnectionState.Connected
        }
    }
    return copy(
        sessions = updatedSessions,
        activeSessionId = active,
        remoteClipboardPrompt = retainedClipboardPrompt,
    )
}

private fun RemoteSessionConnectionSeed.toUiConnectionSeed(): SshConnectionSeed =
    SshConnectionSeed(
        host = host,
        port = port,
        username = username,
        sourceProfileId = sourceProfileId,
        connectionOptions = if (protocol == ConnectionProtocol.MOSH) {
            RemoteConnectionOptions(
                protocol = ConnectionProtocol.MOSH,
                moshPort = moshPort,
                moshPortRange = moshPortRange,
                moshServerCommand = moshServerCommand,
            )
        } else {
            RemoteConnectionOptions.SSH
        },
    )

internal fun ConnectionState.toWorkspaceStatus(): WorkspaceSessionStatus = when (this) {
    ConnectionState.Connecting -> WorkspaceSessionStatus.CONNECTING
    is ConnectionState.Reconnecting -> WorkspaceSessionStatus.CONNECTING
    ConnectionState.Connected -> WorkspaceSessionStatus.CONNECTED
    is ConnectionState.AwaitingApproval -> WorkspaceSessionStatus.AWAITING_APPROVAL
    ConnectionState.Disconnected -> WorkspaceSessionStatus.DISCONNECTED
    is ConnectionState.Failed -> WorkspaceSessionStatus.FAILED
}

private fun SessionState.toWorkspaceStatus(): WorkspaceSessionStatus = when (this) {
    SessionState.CONNECTING, SessionState.RECONNECTING -> WorkspaceSessionStatus.CONNECTING
    SessionState.CONNECTED -> WorkspaceSessionStatus.CONNECTED
    SessionState.DISCONNECTED -> WorkspaceSessionStatus.DISCONNECTED
    SessionState.FAILED -> WorkspaceSessionStatus.FAILED
}

internal fun sanitizeWorkspaceTerminalTitle(
    raw: String?,
    sensitiveValues: Iterable<String> = emptyList(),
): String? {
    val normalized = normalizeWorkspaceText(raw, maximumLength = 128) ?: return null
    val folded = normalized.lowercase(Locale.ROOT)
    val revealsKnownEndpoint = sensitiveValues
        .flatMap(::workspaceSensitiveFragments)
        .any { fragment -> folded.contains(fragment.lowercase(Locale.ROOT)) }
    return normalized.takeUnless { revealsKnownEndpoint }
}

internal fun TerminalSpikeUiState.withSessionTerminalTitle(
    sessionId: Long,
    rawTitle: String?,
    sensitiveValues: Iterable<String> = emptyList(),
): TerminalSpikeUiState {
    val terminalTitle = sanitizeWorkspaceTerminalTitle(rawTitle, sensitiveValues)
    val current = sessions.firstOrNull { session -> session.id == sessionId } ?: return this
    if (current.terminalTitle == terminalTitle) return this
    return copy(
        sessions = sessions.map { session ->
            if (session.id == sessionId) session.copy(terminalTitle = terminalTitle) else session
        },
    )
}

internal fun TerminalSpikeUiState.toConnectionsUiState(
    loadResult: TerminalDataCatalogLoadResult?,
    interaction: ConnectionsInteractionState = ConnectionsInteractionState(),
    recentHostActivity: List<RecentHostActivity> = emptyList(),
    hostConnectionTest: HostConnectionTestUiState = HostConnectionTestUiState.Idle,
): ConnectionsUiState {
    if (loadResult == null) {
        return interaction.withLoadState(ConnectionsLoadState.Loading, hostConnectionTest)
    }
    val catalog = loadResult.catalog
        ?: return interaction.withLoadState(
            ConnectionsLoadState.Error(
                message = uiText(R.string.connections_data_unavailable),
            ),
            hostConnectionTest,
        )
    val openHistory = sessions
        .asSequence()
        .filterNot(SessionTabUi::isLocalTerminal)
        .mapNotNull { session -> session.toConnectionsHistory(catalog) }
        .toList()
    val openHistoryIds = openHistory.mapTo(mutableSetOf(), RecentSession::id)
    return interaction.withLoadState(
        buildConnectionsReadyState(
            catalog = catalog,
            sessions = recentSessions.filterNot { recent -> recent.id in openHistoryIds } + openHistory,
            recentHostActivity = recentHostActivity,
        ),
        hostConnectionTest,
    )
}

private fun SessionTabUi.toConnectionsHistory(catalog: TerminalDataCatalog): RecentSession? {
    val profileId = sourceProfileId?.let(catalog::persistentHostId) ?: return null
    val historyId = recentSessionId ?: return null
    val activityAt = lastActivityAtEpochMillis.coerceAtLeast(0)
    val state = connectionState.toPersistedSessionState()
    return RecentSession(
        id = historyId,
        hostProfileId = profileId,
        hostDisplayName = workspaceName,
        protocol = protocol,
        state = state,
        startedAtEpochMillis = activityAt,
        lastActivityAtEpochMillis = activityAt,
        endedAtEpochMillis = if (state == SessionState.DISCONNECTED || state == SessionState.FAILED) {
            activityAt
        } else {
            null
        },
        terminalTitle = sanitizeWorkspaceTerminalTitle(terminalTitle),
        endpointIdentityToken = endpointIdentityToken,
    )
}

internal fun privacySafeWorkspaceFriendlyName(
    candidate: String?,
    protocol: ConnectionProtocol,
    sensitiveValues: Iterable<String> = emptyList(),
): String {
    val fallback = when (protocol) {
        ConnectionProtocol.SSH -> WORKSPACE_SSH_SESSION_FALLBACK
        ConnectionProtocol.MOSH -> WORKSPACE_MOSH_SESSION_FALLBACK
    }
    val normalized = normalizeWorkspaceText(
        raw = candidate,
        maximumLength = UserSettings.MAX_LABEL_LENGTH,
    )
        ?: return fallback
    val folded = normalized.lowercase(Locale.ROOT)
    val revealsKnownEndpoint = sensitiveValues
        .flatMap(::workspaceSensitiveFragments)
        .any { fragment -> folded.contains(fragment.lowercase(Locale.ROOT)) }
    val resemblesUnlinkedEndpoint = '@' in normalized ||
        WORKSPACE_IPV4_PATTERN.containsMatchIn(normalized) ||
        WORKSPACE_HOSTNAME_PATTERN.containsMatchIn(normalized) ||
        normalized.count { it == ':' } >= 2
    return if (revealsKnownEndpoint || resemblesUnlinkedEndpoint) fallback else normalized
}

private fun workspaceSensitiveFragments(value: String): List<String> {
    val normalized = normalizeWorkspaceText(value, maximumLength = WORKSPACE_SENSITIVE_VALUE_LIMIT)
        ?.removeSurrounding("[", "]")
        ?: return emptyList()
    return buildList {
        add(normalized)
        val atIndex = normalized.lastIndexOf('@')
        if (atIndex > 0 && atIndex < normalized.lastIndex) {
            add(normalized.substring(0, atIndex))
            val endpoint = normalized.substring(atIndex + 1)
            add(endpoint)
            when {
                endpoint.startsWith('[') && ']' in endpoint ->
                    add(endpoint.substringAfter('[').substringBefore(']'))
                endpoint.count { it == ':' } == 1 -> add(endpoint.substringBefore(':'))
            }
        }
    }.filter(String::isNotBlank)
}

/**
 * Normalizes untrusted terminal metadata without allowing invisible direction changes to survive.
 *
 * FORMAT code points include the bidi embedding/override/isolate controls, directional marks,
 * zero-width joiners, and the byte-order mark. They are removed rather than replaced so they
 * cannot split a username or host and evade the endpoint-fragment check that follows.
 */
private fun normalizeWorkspaceText(raw: String?, maximumLength: Int): String? {
    if (raw == null) return null
    val normalized = buildString(raw.length.coerceAtMost(maximumLength)) {
        var offset = 0
        while (offset < raw.length) {
            val codePoint = Character.codePointAt(raw, offset)
            val category = Character.getType(codePoint)
            when {
                category == Character.FORMAT.toInt() ||
                    category == Character.SURROGATE.toInt() -> Unit
                category == Character.CONTROL.toInt() ||
                    Character.isWhitespace(codePoint) ||
                    Character.isSpaceChar(codePoint) -> append(' ')
                else -> appendCodePoint(codePoint)
            }
            offset += Character.charCount(codePoint)
        }
    }
        .trim()
        .replace(WORKSPACE_WHITESPACE_PATTERN, " ")
        .takeWholeCodePoints(maximumLength)
    return normalized.takeIf(String::isNotBlank)
}

private fun String.takeWholeCodePoints(maximumLength: Int): String {
    if (length <= maximumLength) return this
    val safeEnd = if (Character.isHighSurrogate(this[maximumLength - 1])) {
        maximumLength - 1
    } else {
        maximumLength
    }
    return substring(0, safeEnd)
}

private val WORKSPACE_IPV4_PATTERN = Regex(
    "(?:^|\\s|\\[)(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?(?:$|\\s|\\])",
)
private val WORKSPACE_HOSTNAME_PATTERN = Regex(
    "(?i)(?:^|\\s|\\[)(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d+)?(?:$|\\s|\\])",
)
private val WORKSPACE_WHITESPACE_PATTERN = Regex(" +")
private const val WORKSPACE_SENSITIVE_VALUE_LIMIT = 512

internal enum class SnippetDispatchOutcome {
    INSERTED,
    REQUIRES_CONFIRMATION,
    SENT,
    REJECTED,
}

internal class LatestValuePublicationGate<T>(
    private val loadLatest: suspend () -> T,
) {
    private val mutex = Mutex()

    suspend fun publish(block: suspend (T) -> Unit) {
        mutex.withLock {
            block(loadLatest())
        }
    }
}

internal fun dispatchSnippet(
    snippet: CommandSnippet,
    confirmed: Boolean,
    enqueue: (appendEnter: Boolean) -> Boolean,
): SnippetDispatchOutcome {
    if (
        snippet.confirmMultilineExecution &&
        snippet.command.requiresMultilineConfirmation() &&
        !confirmed
    ) {
        return SnippetDispatchOutcome.REQUIRES_CONFIRMATION
    }
    if (!enqueue(snippet.appendEnter)) {
        return SnippetDispatchOutcome.REJECTED
    }
    return if (snippet.sendsImmediately) {
        SnippetDispatchOutcome.SENT
    } else {
        SnippetDispatchOutcome.INSERTED
    }
}

internal fun TerminalSpikeUiState.afterNoticePresented(presentedNotice: UiText): TerminalSpikeUiState =
    if (notice == presentedNotice) copy(notice = null) else this

internal fun shouldShowNotificationPermissionEducation(
    visibility: SessionNotificationVisibility,
    alreadyConsumed: Boolean,
): Boolean = visibility == SessionNotificationVisibility.LIMITED_BY_PERMISSION && !alreadyConsumed

internal fun TerminalSpikeUiState.afterSettingsLoad(
    loaded: SettingsLoadResult,
    knownHosts: List<KnownHostSummary>,
    retry: Boolean,
): TerminalSpikeUiState = copy(
    profiles = loaded.settings.profiles,
    snippets = loaded.settings.snippets,
    identities = loaded.settings.identities,
    knownHosts = knownHosts,
    extraKeys = if (runtimeProfilesReady) extraKeys else loaded.settings.extraKeys,
    keyboardRuntimeCompatible = if (runtimeProfilesReady) {
        keyboardRuntimeCompatible
    } else {
        loaded.settings.keyboardRuntimeCompatible
    },
    settingsReady = loaded.failure == null,
    settingsRecoveryFailure = loaded.failure,
    settingsRecoveryInProgress = false,
    notice = when {
        loaded.warning != null -> loaded.warning.toSettingsWarningUiText()
        retry && loaded.failure == null -> uiText(R.string.notice_settings_available_again)
        else -> notice
    },
)

internal fun TerminalSpikeUiState.bufferedInputValidationError(sessionId: Long, text: String): UiText? {
    val targetSession = sessions.firstOrNull { it.id == sessionId }
    return when {
        text.isEmpty() -> uiText(R.string.notice_buffer_enter_text)
        text.length > MAX_BUFFERED_INPUT_CHARACTERS ->
            uiText(R.string.notice_buffer_limit, MAX_BUFFERED_INPUT_CHARACTERS)
        text.hasUnpairedSurrogate() -> uiText(R.string.notice_buffer_invalid_unicode)
        text.any { it.isISOControl() && it !in "\r\n\t" } ->
            uiText(R.string.notice_buffer_unsupported_control)
        targetSession == null -> uiText(R.string.notice_terminal_session_unavailable)
        !targetSession.isLocalTerminal && targetSession.connectionState !is ConnectionState.Connected ->
            uiText(R.string.notice_connect_before_buffered_input)
        else -> null
    }
}

internal fun TerminalSpikeUiState.canConfirmSnippetOnActiveSession(snippetId: Long): Boolean =
    pendingSnippetSendId == snippetId &&
        pendingSnippetTargetSessionId != null &&
        pendingSnippetTargetSessionId == activeSessionId

internal fun TerminalSpikeUiState.afterStoredCredentialUnavailable(
    profileId: Long? = null,
    identityId: Long? = null,
): TerminalSpikeUiState = copy(
    profiles = profiles.map { profile ->
        if (profile.id == profileId) profile.copy(hasSavedPassword = false) else profile
    },
    identities = identities.map { identity ->
        if (identity.id == identityId) identity.copy(isAvailable = false) else identity
    },
    notice = if (identityId != null) {
        uiText(R.string.notice_private_key_unavailable_reimport)
    } else {
        uiText(R.string.notice_password_unavailable_reenter)
    },
)

private fun String.toSettingsWarningUiText(): UiText {
    if (this == "Saved connections and settings could not be unlocked. The encrypted data was preserved for recovery.") {
        return uiText(R.string.notice_settings_locked_preserved)
    }
    if (this == "Some legacy records need review after migration; preserved source data was not deleted.") {
        return uiText(R.string.notice_legacy_records_review)
    }
    val migratedCount = substringBefore(' ').toIntOrNull()
    if (migratedCount != null && contains("migrated credential")) {
        return quantityText(
            R.plurals.notice_migrated_credentials_unavailable,
            migratedCount,
            migratedCount,
        )
    }
    // Repository warnings are bounded, sanitized provider detail rather than product-authored copy.
    return UiText.Dynamic(this)
}

internal fun String.hasUnpairedSurrogate(): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            character.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return true
                index += 2
            }
            character.isLowSurrogate() -> return true
            else -> index += 1
        }
    }
    return false
}

/** Encodes a transient UI secret without first materialising it as an immutable [String]. */
private fun CharArray.toUtf8Secret(): ByteArray {
    if (isEmpty()) return ByteArray(0)
    val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(this))
    return try {
        ByteArray(encoded.remaining()).also(encoded::get)
    } finally {
        if (encoded.hasArray()) encoded.array().fill(0)
    }
}

/** Takes and destroys the editor-owned characters before any coroutine can be scheduled. */
internal fun HostEditorSubmission.prepareHostConnectionTest(): PreparedHostConnectionTest {
    val validated = value
    val encoded = try {
        secret.toUtf8Secret()
    } finally {
        wipe()
    }
    var ownershipTransferred = false
    return try {
        PreparedHostConnectionTest(validated, encoded).also { ownershipTransferred = true }
    } finally {
        if (!ownershipTransferred) encoded.fill(0)
    }
}

internal fun resolveIdentityRecoveryTarget(
    identities: List<SavedSshIdentity>,
    recoveryToken: String,
): SavedSshIdentity? = identities.singleOrNull { it.recoveryToken == recoveryToken }

internal data class KnownHostRefreshResult(
    val knownHosts: List<KnownHostSummary>,
    val refreshed: Boolean,
)

internal fun refreshKnownHostsOrFallback(
    fallback: List<KnownHostSummary>,
    load: () -> List<KnownHostSummary>,
): KnownHostRefreshResult = runCatching(load).fold(
    onSuccess = { KnownHostRefreshResult(it, refreshed = true) },
    onFailure = { KnownHostRefreshResult(fallback, refreshed = false) },
)

private data class RuntimeSettingsSnapshot(
    val appSettings: AppSettings,
    val terminalProfile: TerminalProfile?,
    val keyboardProfile: KeyboardProfile?,
    val terminalProfiles: List<TerminalProfile>,
    val customTerminalThemes: List<CustomTerminalTheme>,
    val keyboardProfiles: List<KeyboardProfile>,
)

private class SessionRuntimeProfileSelection(
    val terminalProfileId: String?,
    val keyboardProfileId: String?,
    /** Explicit host profile when selected, otherwise the current application default. */
    val terminalProfile: TerminalProfile?,
    val keyboardProfile: KeyboardProfile?,
    val terminalType: String?,
    val startupCommand: String?,
    val remoteClipboardMode: RemoteClipboardMode,
    val keepaliveIntervalSeconds: Int,
    val reliabilityPolicy: RemoteSessionReliabilityPolicy,
)

/** Secret-bearing selection used only across the synchronous session-start boundary. */
private sealed interface KeyboardInteractiveStart {
    class SessionOnly(val initialResponse: ByteArray?) : KeyboardInteractiveStart

    class ReusableResponse(
        val loadResponse: suspend () -> ByteArray,
    ) : KeyboardInteractiveStart
}

/**
 * Holds bytes only until a lazy coroutine has entered its guarded try/finally. Completion wipes an
 * unclaimed pair; after [claim] the coroutine or transport is the sole owner.
 */
internal class DeferredSecretPair(
    first: ByteArray?,
    second: ByteArray? = null,
) {
    data class Claimed(
        val first: ByteArray?,
        val second: ByteArray?,
    )

    private val lock = Any()
    private var firstSecret = first
    private var secondSecret = second
    private var claimed = false

    fun claim(): Claimed = synchronized(lock) {
        check(!claimed) { "Deferred secret bytes were already claimed." }
        claimed = true
        Claimed(
            first = firstSecret.also { firstSecret = null },
            second = secondSecret.also { secondSecret = null },
        )
    }

    fun wipeUnclaimed() {
        val pending = synchronized(lock) {
            if (claimed) return
            claimed = true
            Claimed(
                first = firstSecret.also { firstSecret = null },
                second = secondSecret.also { secondSecret = null },
            )
        }
        pending.first?.fill(0)
        pending.second?.fill(0)
    }
}

private data class RuntimeReliabilityDefaults(
    val keepaliveIntervalSeconds: Int = 30,
    val reconnectEnabled: Boolean = false,
    val reconnectMaxAttempts: Int = 5,
)

internal data class ResolvedRemoteReliability(
    val keepaliveIntervalSeconds: Int,
    val reconnectEnabled: Boolean,
    val reconnectMaxAttempts: Int,
)

internal fun resolveRemoteReliability(
    globalKeepaliveIntervalSeconds: Int,
    globalReconnectEnabled: Boolean,
    globalReconnectMaxAttempts: Int,
    hostKeepaliveIntervalSeconds: Int?,
    hostReconnectPolicy: ReconnectPolicy?,
): ResolvedRemoteReliability = ResolvedRemoteReliability(
    keepaliveIntervalSeconds = hostKeepaliveIntervalSeconds ?: globalKeepaliveIntervalSeconds,
    reconnectEnabled = when (hostReconnectPolicy) {
        ReconnectPolicy.AUTOMATIC -> true
        ReconnectPolicy.DISABLED -> false
        null -> globalReconnectEnabled
    },
    reconnectMaxAttempts = globalReconnectMaxAttempts,
)

private fun TerminalProfile.toRendererProfile(
    customFonts: CustomTerminalFontStore,
    customThemes: Iterable<CustomTerminalTheme> = emptyList(),
): TerminalRendererProfile = TerminalRendererProfile(
    theme = TerminalThemes.find(themeId, customThemes),
    fontId = fontId,
    customFontPath = if (TerminalRendererProfile.isBundledFontId(fontId)) {
        null
    } else {
        customFonts.resolve(fontId)?.also(TerminalTypefaceRegistry::register)?.absolutePath
    },
    fontSizeSp = fontSizeSp,
    lineHeightMultiplier = lineHeightMultiplier,
    letterSpacingEm = letterSpacingEm,
    boldRenderingEnabled = boldRenderingEnabled,
    ligaturesEnabled = ligaturesEnabled,
    pinchZoomEnabled = pinchZoomEnabled,
    cursorStyle = cursorStyle,
    cursorBlinkEnabled = cursorBlinkEnabled,
    touchScrollMode = scroll.touchMode,
    twoFingerLocalScrollOverride = scroll.twoFingerLocalScrollOverride,
    jumpToBottomOnKeyboardInput = scroll.jumpToBottomOnKeyboardInput,
    keepViewportPositionOnOutput = scroll.keepViewportPositionOnOutput,
    preserveAlternateScreenHistory = preserveAlternateScreenHistory,
    detectPlainTextUrls = true,
    osc8HyperlinksEnabled = true,
    copyOnSelection = links.copyOnSelection,
)

class TerminalSpikeViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    val controller = TerminalController()
    val bufferedInputDraftState = BufferedInputDraftState()
    private val _uiState = MutableStateFlow(TerminalSpikeUiState())
    val uiState: StateFlow<TerminalSpikeUiState> = _uiState.asStateFlow()
    private var pendingRestoredActiveSessionId =
        savedStateHandle.get<Long>(ACTIVE_TERMINAL_SESSION_ID)
    private var pendingRestoredLocalSessionId = pendingRestoredActiveSessionId?.takeIf { it < 0 }
    internal val terminalBuildFeature = createTerminalBuildFeature(viewModelScope, controller)
    private val appLogger = AppLogger()
    private val appContainer = (application as TerminalSpikeApplication).container
    private val localAppDataResetter = AndroidLocalAppDataResetter(application)
    private val pendingTranscriptExport = PendingTerminalTranscriptExportOwner()
    private val localNetworkPendingAction = LocalNetworkPendingActionOwner()
    private val _localNetworkActionDispatch =
        MutableStateFlow<LocalNetworkActionDispatch?>(null)
    internal val localNetworkActionDispatch: StateFlow<LocalNetworkActionDispatch?> =
        _localNetworkActionDispatch.asStateFlow()
    private var localNetworkResolutionJob: Job? = null
    private var nextLocalNetworkDispatchId = 1L
    private val customTerminalFonts = CustomTerminalFontStore(application)
    @Volatile
    private var activeRendererProfile = TerminalRendererProfile()
    @Volatile
    private var activeScrollbackLines = DEFAULT_RUNTIME_SCROLLBACK_LINES
    @Volatile
    private var activeRemoteClipboardMode = RemoteClipboardMode.ASK
    @Volatile
    private var notificationPermissionEducationConsumed = false
    @Volatile
    private var tmuxSessionSelectorEnabled = true
    private var terminalProfilesById = emptyMap<String, TerminalProfile>()
    private var customTerminalThemesById = emptyMap<String, CustomTerminalTheme>()
    private var keyboardProfilesById = emptyMap<String, KeyboardProfile>()
    private var defaultTerminalProfile: TerminalProfile? = null
    private var defaultKeyboardProfile: KeyboardProfile? = null
    @Volatile
    private var runtimeReliabilityDefaults = RuntimeReliabilityDefaults()
    private var lastCtrlTapNanos = 0L
    private var lastAltTapNanos = 0L
    private var lastShiftTapNanos = 0L
    private val moshExtensionClient = appContainer.moshExtension
    private val localSessions = appContainer.localSessionRepository
    internal val localArchState = localSessions.environment.state
    internal val localArchRuntime = localSessions.runtime
    private val remoteSessions = appContainer.sshSessionRepository
    private val terminalDataRepository = appContainer.terminalDataRepository
    private val recentSessionRepository = appContainer.recentSessions
    private val knownHostManager = appContainer.knownHostManager
    internal val sftp = SftpSessionController(viewModelScope) { SftpClient(knownHostManager) }
    private val hostConnectionTester by lazy(LazyThreadSafetyMode.NONE) {
        JschHostConnectionTester(knownHostManager)
    }
    private val hostConnectionTests = HostConnectionTestCoordinator(
        scope = viewModelScope,
        runner = { prepared, interaction ->
            runConnectionsHostTest(prepared, interaction)
        },
    )
    // Same-process editor restoration only. Endpoint and startup-command text must not be written
    // to SavedState; this map disappears with the ViewModel/process and never contains credentials.
    private val connectionsHostEditorDrafts = mutableMapOf<String, HostEditorDraft>()
    private val connectionsCatalogLoad = MutableStateFlow<TerminalDataCatalogLoadResult?>(null)
    private val connectionsInteraction = MutableStateFlow(ConnectionsInteractionState())
    private val connectionsRecentHostActivity = MutableStateFlow<List<RecentHostActivity>>(emptyList())
    private val connectionsHostTest = hostConnectionTests.state
    internal val connectionsUiState: StateFlow<ConnectionsUiState> = combine(
        _uiState,
        connectionsCatalogLoad,
        connectionsInteraction,
        connectionsRecentHostActivity,
        connectionsHostTest,
    ) { state, catalogLoad, interaction, recentHostActivity, hostTest ->
        state.toConnectionsUiState(catalogLoad, interaction, recentHostActivity, hostTest)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ConnectionsUiState(),
    )
    private val catalogPublicationGate = LatestValuePublicationGate(terminalDataRepository::loadCatalog)
    private val knownHostPublicationMutex = Mutex()
    private val imagePasteMutex = Mutex()
    private val recentSessionObservationStarted = AtomicBoolean(false)
    private val connectionsCatalogRetryInProgress = AtomicBoolean(false)
    private val moshStatusRequestInProgress = AtomicBoolean(false)
    private var previousRemoteSessionStates = emptyMap<Long, ConnectionState>()
    private val sessionsAwaitingFreshShellNotice = mutableSetOf<Long>()
    private var pendingRemoteClipboardRequest: RemoteClipboardWriteRequestEvent? = null

    init {
        observeRemoteSessions()
        observeLocalSessions()
        observeRemoteClipboardRequests()
        viewModelScope.launch {
            uiState
                .map { state -> state.activeSessionId }
                .distinctUntilChanged()
                .collect { sessionId ->
                    savedStateHandle[ACTIVE_TERMINAL_SESSION_ID] = sessionId
                    if (sessionId != LOCAL_TERMINAL_SESSION_ID) {
                        savedStateHandle[LAST_ACTIVE_REMOTE_SESSION_ID] = sessionId
                    }
                }
        }
        viewModelScope.launch {
            moshExtensionClient.status.collect { status ->
                _uiState.update { state -> state.copy(moshExtension = status.toUiState()) }
            }
        }
        requestMoshExtensionStatus(refresh = false)
        observeAuthoritativeCatalog()
        observeRuntimeProfiles()
    }

    private fun observeAuthoritativeCatalog() {
        viewModelScope.launch(Dispatchers.IO) {
            appContainer.authoritativeData.state
                .filterIsInstance<AuthoritativeDataState.Ready>()
                .map { ready -> ready.generation }
                .distinctUntilChanged()
                .collect { loadLocalSettings(retry = false) }
        }
    }

    private fun observeRuntimeProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                appContainer.authoritativeData.awaitReady()
                appContainer.startLegacyMigrationForCutover().await()
                val initialSettings = appContainer.settings.settings.first()
                appContainer.keyboardProfiles.get(initialSettings.defaultKeyboardProfileId)
                    ?.upgradeShippedKeyboardDeck(System.currentTimeMillis())
                    ?.let { upgraded ->
                        appContainer.keyboardProfiles.update(
                            upgraded,
                        )
                    }
                combine(
                    appContainer.settings.settings,
                    appContainer.terminalProfiles.observeAll(),
                    appContainer.keyboardProfiles.observeAll(),
                    appContainer.customTerminalThemes.observeAll(),
                ) { _, _, _, _ -> Unit }.collect {
                    // Flow emissions may be delivered while a cross-store restore is committing.
                    // Re-read both stores under the shared authority lock rather than publishing
                    // values captured on opposite sides of that transaction boundary.
                    val snapshot = appContainer.authoritativeData.withRead {
                        val settings = appContainer.settings.settings.first()
                        val terminalProfiles = appContainer.terminalProfiles.observeAll().first()
                        val keyboardProfiles = appContainer.keyboardProfiles.observeAll().first()
                        val customTerminalThemes = appContainer.customTerminalThemes.observeAll().first()
                        RuntimeSettingsSnapshot(
                            appSettings = settings,
                            terminalProfile = terminalProfiles.firstOrNull {
                                it.id == settings.defaultTerminalProfileId
                            } ?: terminalProfiles.firstOrNull(),
                            keyboardProfile = keyboardProfiles.firstOrNull {
                                it.id == settings.defaultKeyboardProfileId
                            } ?: keyboardProfiles.firstOrNull(),
                            terminalProfiles = terminalProfiles,
                            customTerminalThemes = customTerminalThemes,
                            keyboardProfiles = keyboardProfiles,
                        )
                    }
                    val rendererProfiles = snapshot.terminalProfiles.associate { profile ->
                        val rendererProfile = profile.toRendererProfile(
                            customTerminalFonts,
                            snapshot.customTerminalThemes,
                        )
                        TerminalTypefaceRegistry.registerProfileFont(
                            context = getApplication(),
                            fontId = rendererProfile.fontId,
                            customFontPath = rendererProfile.customFontPath,
                        )
                        profile.id to rendererProfile
                    }
                    val rendererProfile = snapshot.terminalProfile
                        ?.let { profile -> rendererProfiles[profile.id] }
                        ?: TerminalRendererProfile()
                    withContext(Dispatchers.Main.immediate) {
                        terminalProfilesById = snapshot.terminalProfiles.associateBy(TerminalProfile::id)
                        customTerminalThemesById = snapshot.customTerminalThemes.associateBy(
                            CustomTerminalTheme::id,
                        )
                        keyboardProfilesById = snapshot.keyboardProfiles.associateBy(KeyboardProfile::id)
                        defaultTerminalProfile = snapshot.terminalProfile
                        defaultKeyboardProfile = snapshot.keyboardProfile
                        runtimeReliabilityDefaults = RuntimeReliabilityDefaults(
                            keepaliveIntervalSeconds = snapshot.appSettings.keepaliveIntervalSeconds,
                            reconnectEnabled = snapshot.appSettings.reconnectEnabled,
                            reconnectMaxAttempts = snapshot.appSettings.reconnectMaxAttempts,
                        )
                        notificationPermissionEducationConsumed =
                            notificationPermissionEducationConsumed ||
                            snapshot.appSettings.notificationPermissionEducationConsumed
                        tmuxSessionSelectorEnabled =
                            !snapshot.appSettings.tmuxSessionSelectorDisabled
                        activeRendererProfile = rendererProfile
                        activeScrollbackLines = resolveRuntimeScrollbackLines(
                            snapshot.terminalProfile?.scrollbackLines
                                ?: DEFAULT_RUNTIME_SCROLLBACK_LINES,
                        )
                        activeRemoteClipboardMode = snapshot.terminalProfile
                            ?.links
                            ?.remoteClipboardMode
                            ?: RemoteClipboardMode.ASK
                        controller.updateRendererProfile(rendererProfile)
                        localSessions.updateRendererProfile(rendererProfile)
                        remoteSessions.updateRendererProfiles(
                            defaultProfile = rendererProfile,
                            overridesById = rendererProfiles,
                        )
                        _uiState.update { state ->
                            withRuntimeKeyboardProfile(
                                state = state,
                                keyboard = keyboardProfileForSession(state, state.activeSessionId),
                            ).copy(
                                runtimeProfilesReady = true,
                                keepScreenOnWhileTerminalVisible =
                                    snapshot.appSettings.keepScreenOnWhileTerminalVisible,
                                multilinePasteConfirmationEnabled =
                                    snapshot.appSettings.multilinePasteConfirmationEnabled,
                                voiceInputLanguageTag = snapshot.appSettings.voiceInputLanguageTag,
                            )
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appLogger.warning(AppLogEvent.SETTINGS_READ_FAILED, error) {
                    "Live terminal profiles could not be observed."
                }
            }
        }
    }

    private fun keyboardProfileForSession(
        state: TerminalSpikeUiState,
        sessionId: Long,
    ): KeyboardProfile? {
        val overrideId = state.sessions.firstOrNull { it.id == sessionId }?.keyboardProfileId
        return overrideId?.let(keyboardProfilesById::get) ?: defaultKeyboardProfile
    }

    private fun withRuntimeKeyboardProfile(
        state: TerminalSpikeUiState,
        keyboard: KeyboardProfile?,
    ): TerminalSpikeUiState {
        val runtimeActions = keyboard?.toRuntimeAccessoryActionsOrNull()
        val runtimeKeys = keyboard?.toRuntimeExtraKeysOrNull()
        val modifierBehavior = keyboard?.modifierBehavior ?: ModifierBehavior.ONE_SHOT
        val modifierBehaviorChanged = state.modifierBehavior != modifierBehavior
        if (modifierBehaviorChanged) {
            lastCtrlTapNanos = 0L
            lastAltTapNanos = 0L
            lastShiftTapNanos = 0L
        }
        return state.copy(
            extraKeys = runtimeKeys ?: TerminalExtraKey.DEFAULT_ORDER,
            accessoryActions = runtimeActions
                ?: defaultRuntimeAccessoryActions(),
            keyboardRuntimeCompatible = runtimeActions != null,
            keyboardLayout = keyboard?.layout ?: KeyboardLayout.TWO_ROWS,
            modifierBehavior = modifierBehavior,
            ctrlArmed = if (modifierBehaviorChanged) false else state.ctrlArmed,
            altArmed = if (modifierBehaviorChanged) false else state.altArmed,
            shiftArmed = if (modifierBehaviorChanged) false else state.shiftArmed,
            ctrlLocked = if (modifierBehaviorChanged) false else state.ctrlLocked,
            altLocked = if (modifierBehaviorChanged) false else state.altLocked,
            shiftLocked = if (modifierBehaviorChanged) false else state.shiftLocked,
            keyboardHapticsEnabled = keyboard?.hapticFeedbackEnabled ?: false,
            keyRepeatEnabled = keyboard?.keyRepeatEnabled ?: true,
            terminalInputMode = keyboard?.inputMode ?: TerminalInputMode.RAW,
        )
    }

    private fun observeLocalSessions() {
        viewModelScope.launch {
            var previousError: String? = null
            localSessions.runtime.collect { runtime ->
                val newError = runtime.error?.takeIf { it != previousError }
                previousError = runtime.error
                val restoredId = pendingRestoredLocalSessionId
                pendingRestoredLocalSessionId = null
                _uiState.update { state ->
                    state.withLocalSessionSnapshots(runtime.sessions, restoredId).let { projected ->
                        newError?.let { projected.copy(notice = UiText.Dynamic(it)) } ?: projected
                    }
                }
            }
        }
    }

    internal fun openLocalArch(): Boolean {
        val id = localSessions.start(RemoteSessionTerminalConfiguration(
            scrollbackLines = activeScrollbackLines,
            rendererProfile = activeRendererProfile,
        )) ?: return false
        _uiState.update { it.withLocalSessionSnapshots(localSessions.runtime.value.sessions, id) }
        return true
    }

    internal fun installLocalArch() = localSessions.installOrReset()

    private fun observeRemoteSessions() {
        viewModelScope.launch {
            remoteSessions.sessions.collect { snapshots ->
                val restoredActiveSessionId = pendingRestoredActiveSessionId
                val pendingClipboard = pendingRemoteClipboardRequest
                if (
                    pendingClipboard != null &&
                    snapshots.none { snapshot ->
                        snapshot.id == pendingClipboard.sessionId &&
                            snapshot.connectionState is ConnectionState.Connected
                    }
                ) {
                    discardRemoteClipboardRequest(pendingClipboard)
                }
                val previous = previousRemoteSessionStates
                val newApproval = snapshots.firstOrNull { snapshot ->
                    snapshot.connectionState is ConnectionState.AwaitingApproval &&
                        previous[snapshot.id] !is ConnectionState.AwaitingApproval
                }
                val reachedConnected = snapshots.any { snapshot ->
                    snapshot.connectionState is ConnectionState.Connected &&
                        previous[snapshot.id] !is ConnectionState.Connected
                }
                snapshots.filter { it.connectionState is ConnectionState.Reconnecting }
                    .forEach { sessionsAwaitingFreshShellNotice += it.id }
                val openedFreshShellIds = snapshots.filterTo(mutableListOf()) { snapshot ->
                    snapshot.connectionState is ConnectionState.Connected &&
                        snapshot.id in sessionsAwaitingFreshShellNotice
                }.mapTo(mutableSetOf()) { it.id }
                sessionsAwaitingFreshShellNotice.removeAll { sessionId ->
                    val current = snapshots.firstOrNull { it.id == sessionId }?.connectionState
                    sessionId in openedFreshShellIds || current == null ||
                        current is ConnectionState.Disconnected || current is ConnectionState.Failed
                }
                val openedFreshShell = openedFreshShellIds.isNotEmpty()
                previousRemoteSessionStates = snapshots.associate { snapshot ->
                    snapshot.id to snapshot.connectionState
                }
                _uiState.update { state ->
                    val previousActive = state.sessions.firstOrNull {
                        it.id == state.activeSessionId
                    }
                    val projected = state.withRemoteSessionSnapshots(
                        remoteSessions = snapshots,
                        preferredSessionId = newApproval?.id ?: restoredActiveSessionId,
                    )
                    val projectedActive = projected.sessions.firstOrNull {
                        it.id == projected.activeSessionId
                    }
                    val failureNotice = projectedActive?.let { active ->
                        newConnectionFailureNotice(
                            current = active.connectionState,
                            previous = previous[active.id],
                        )
                    }
                    val withKeyboard = if (
                        previousActive?.id != projectedActive?.id ||
                        previousActive?.keyboardProfileId != projectedActive?.keyboardProfileId
                    ) {
                        withRuntimeKeyboardProfile(
                            state = projected,
                            keyboard = keyboardProfileForSession(projected, projected.activeSessionId),
                        )
                    } else {
                        projected
                    }
                    if (failureNotice != null) {
                        withKeyboard.copy(notice = failureNotice)
                    } else if (openedFreshShell) {
                        withKeyboard.copy(
                            notice = uiText(R.string.notice_reconnected_new_shell),
                        )
                    } else {
                        withKeyboard
                    }
                }
                pendingRestoredActiveSessionId = null
                if (reachedConnected) refreshKnownHostsAfterRemoteConnection()
            }
        }
    }

    private fun observeRemoteClipboardRequests() {
        viewModelScope.launch {
            remoteSessions.remoteClipboardRequests.collect { request ->
                if (!remoteSessions.isRemoteClipboardRequestLive(request)) {
                    remoteSessions.discardRemoteClipboardRequest(request)
                    return@collect
                }
                pendingRemoteClipboardRequest?.let(remoteSessions::discardRemoteClipboardRequest)
                pendingRemoteClipboardRequest = request
                _uiState.update { state ->
                    state.copy(
                        remoteClipboardPrompt = RemoteClipboardPromptUi(
                            sessionId = request.sessionId,
                            requestId = request.requestId,
                        ),
                    )
                }
            }
        }
    }

    private fun discardRemoteClipboardRequest(request: RemoteClipboardWriteRequestEvent) {
        if (pendingRemoteClipboardRequest === request) pendingRemoteClipboardRequest = null
        remoteSessions.discardRemoteClipboardRequest(request)
        _uiState.update { state ->
            if (
                state.remoteClipboardPrompt?.sessionId == request.sessionId &&
                state.remoteClipboardPrompt.requestId == request.requestId
            ) {
                state.copy(remoteClipboardPrompt = null)
            } else {
                state
            }
        }
    }

    /** Returns text only for the exact dialog the user explicitly allowed. */
    internal fun allowRemoteClipboardRequest(prompt: RemoteClipboardPromptUi): String? {
        val request = pendingRemoteClipboardRequest
        if (
            request == null || request.sessionId != prompt.sessionId ||
            request.requestId != prompt.requestId
        ) {
            return null
        }
        pendingRemoteClipboardRequest = null
        _uiState.update { state ->
            if (state.remoteClipboardPrompt == prompt) {
                state.copy(remoteClipboardPrompt = null)
            } else {
                state
            }
        }
        return remoteSessions.consumeRemoteClipboardRequest(request)
    }

    internal fun denyRemoteClipboardRequest(prompt: RemoteClipboardPromptUi) {
        val request = pendingRemoteClipboardRequest
        if (
            request == null || request.sessionId != prompt.sessionId ||
            request.requestId != prompt.requestId
        ) {
            return
        }
        discardRemoteClipboardRequest(request)
    }

    private fun refreshKnownHostsAfterRemoteConnection() {
        viewModelScope.launch(Dispatchers.IO) {
            knownHostPublicationMutex.withLock {
                val current = _uiState.value.knownHosts
                val refresh = refreshKnownHostsOrFallback(current, knownHostManager::list)
                if (refresh.refreshed) {
                    _uiState.update { it.copy(knownHosts = refresh.knownHosts) }
                } else {
                    appLogger.warning(AppLogEvent.SETTINGS_READ_FAILED) {
                        "Remote session connected but the trusted-host list could not be refreshed."
                    }
                    _uiState.update {
                        it.copy(notice = uiText(R.string.notice_trusted_hosts_refresh_failed_connected))
                    }
                }
            }
        }
    }

    internal fun refreshMoshExtension() {
        requestMoshExtensionStatus(refresh = true)
    }

    private fun requestMoshExtensionStatus(refresh: Boolean) {
        if (!moshStatusRequestInProgress.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (refresh) {
                    moshExtensionClient.refresh()
                } else {
                    moshExtensionClient.connect()
                }
            } finally {
                moshStatusRequestInProgress.set(false)
            }
        }
    }

    private suspend fun loadLocalSettings(retry: Boolean) {
        var settingsBecameReady = false
        catalogPublicationGate.publish { repositoryResult ->
            knownHostPublicationMutex.withLock {
                val compatibility = repositoryResult.compatibility
                compatibility.failure?.let { failure ->
                    appLogger.warning(AppLogEvent.SETTINGS_READ_FAILED) { failure.name }
                }
                val knownHostRead = if (compatibility.failure == null) {
                    runCatching { knownHostManager.list() }
                } else {
                    Result.success(emptyList())
                }
                knownHostRead.exceptionOrNull()?.let { error ->
                    appLogger.warning(AppLogEvent.SETTINGS_READ_FAILED, error)
                }
                val loaded = if (knownHostRead.isFailure) {
                    compatibility.copy(
                        warning = null,
                        failure = SettingsLoadFailure.APP_DATA_UNAVAILABLE,
                    )
                } else {
                    compatibility
                }
                connectionsCatalogLoad.value = if (loaded.failure == null) {
                    repositoryResult.copy(compatibility = loaded)
                } else {
                    TerminalDataCatalogLoadResult(compatibility = loaded, catalog = null)
                }
                val knownHosts = knownHostRead.getOrDefault(emptyList())
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { state -> state.afterSettingsLoad(loaded, knownHosts, retry) }
                    settingsBecameReady = loaded.failure == null
                }
            }
        }
        if (settingsBecameReady) {
            startRecentSessionObservation()
            withContext(Dispatchers.Main.immediate) {
                drainPendingIdentityImport()
            }
        }
    }

    private fun startRecentSessionObservation() {
        if (!recentSessionObservationStarted.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                recentSessionRepository.reconcileStaleActive(System.currentTimeMillis())
            }.onFailure { error ->
                appLogger.warning(AppLogEvent.SETTINGS_WRITE_FAILED, error) {
                    "Stale recent-session metadata could not be reconciled."
                }
            }
            launch {
                // Scan a larger but still bounded history window before presentation de-duplicates
                // endpoint-equivalent profiles. This prevents one frequently used host from
                // crowding every other connection out of the eight visible Recent rows.
                recentSessionRepository.observeEnded(WORKSPACE_RECENT_HISTORY_SCAN_LIMIT)
                    .catch { error ->
                        appLogger.warning(AppLogEvent.SETTINGS_READ_FAILED, error) {
                            "Recent-session metadata could not be observed."
                        }
                    }
                    .collect { sessions ->
                        _uiState.update { state -> state.copy(recentSessions = sessions) }
                    }
            }
            launch {
                recentSessionRepository.observeRecentHostActivity(CONNECTIONS_RECENT_HOST_LIMIT)
                    .catch { error ->
                        appLogger.warning(AppLogEvent.SETTINGS_READ_FAILED, error) {
                            "Recent host activity could not be observed."
                        }
                    }
                    .collect(connectionsRecentHostActivity::emit)
            }
        }
    }

    internal fun selectConnectionsTab(tab: ConnectionsTab) {
        updateConnectionsInteraction(ConnectionsInteractionAction.SelectTab(tab))
    }

    internal fun setConnectionsSearchQuery(query: String) {
        updateConnectionsInteraction(ConnectionsInteractionAction.SetSearchQuery(query))
    }

    internal fun setConnectionsHostSort(sort: HostSort) {
        updateConnectionsInteraction(ConnectionsInteractionAction.SetHostSort(sort))
    }

    internal fun setConnectionsFavouritesOnly(enabled: Boolean) {
        updateConnectionsInteraction(ConnectionsInteractionAction.SetFavouritesOnly(enabled))
    }

    internal fun setConnectionsHostGroup(group: String?) {
        updateConnectionsInteraction(ConnectionsInteractionAction.SetHostGroup(group))
    }

    internal fun clearConnectionsHostFilters() {
        updateConnectionsInteraction(ConnectionsInteractionAction.ClearHostFilters)
    }

    private fun updateConnectionsInteraction(action: ConnectionsInteractionAction) {
        connectionsInteraction.update { state -> state.reduce(action) }
    }

    internal fun retryConnectionsCatalog() {
        val fallback = connectionsCatalogLoad.value ?: return
        if (!connectionsCatalogRetryInProgress.compareAndSet(false, true)) return
        connectionsCatalogLoad.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching { loadLocalSettings(retry = true) }
                    .onFailure { error ->
                        appLogger.warning(AppLogEvent.SETTINGS_READ_FAILED, error) {
                            "Connections catalog refresh failed."
                        }
                        // Do not let a failed retry overwrite a newer catalog publication.
                        connectionsCatalogLoad.compareAndSet(expect = null, update = fallback)
                        withContext(Dispatchers.Main.immediate) {
                            _uiState.update { state ->
                                state.copy(notice = uiText(R.string.notice_connections_unavailable))
                            }
                        }
                    }
            } finally {
                connectionsCatalogRetryInProgress.set(false)
            }
        }
    }

    /** Saves the complete UUID-backed host aggregate; transient editor secrets never enter state. */
    internal suspend fun saveConnectionsHost(submission: HostEditorSubmission): Result<Unit> {
        val catalog = connectionsCatalogLoad.value?.catalog
        if (!_uiState.value.settingsReady || catalog == null) {
            submission.wipe()
            _uiState.update { it.copy(notice = uiText(R.string.notice_local_data_before_save_host)) }
            return Result.failure(IllegalStateException("The local catalog is unavailable."))
        }
        val persistentId = submission.value.persistentId
        if (persistentId != null && catalog.hosts.none { it.profile.id == persistentId }) {
            submission.wipe()
            _uiState.update { it.copy(notice = uiText(R.string.notice_saved_host_missing_reopen)) }
            return Result.failure(IllegalStateException("The saved host no longer exists."))
        }
        if (persistentId == null && catalog.hosts.size >= UserSettings.MAX_PROFILES) {
            submission.wipe()
            _uiState.update { it.copy(notice = uiText(R.string.notice_delete_host_before_add)) }
            return Result.failure(IllegalStateException("The saved-host limit has been reached."))
        }
        var authentication: HostAuthenticationUpdate? = null
        return try {
            val profile = submission.value.toProfile(nowEpochMillis = System.currentTimeMillis())
            authentication = when (submission.value.authenticationMethod) {
                HostAuthenticationMethod.PASSWORD -> when {
                    submission.savePassword && submission.secret.isNotEmpty() ->
                        HostAuthenticationUpdate.SavePassword(submission.secret.toUtf8Secret())
                    submission.savePassword -> HostAuthenticationUpdate.RetainSavedPassword
                    else -> HostAuthenticationUpdate.PromptPassword
                }
                HostAuthenticationMethod.PRIVATE_KEY -> HostAuthenticationUpdate.PrivateKey(
                    identityId = requireNotNull(submission.value.keyIdentityId),
                )
                HostAuthenticationMethod.KEYBOARD_INTERACTIVE ->
                    HostAuthenticationUpdate.KeyboardInteractive
            }
            val selectedAuthentication = requireNotNull(authentication)
            submission.wipe()
            acknowledgedCatalogWrite(
                successNotice = uiText(
                    if (submission.value.persistentId == null) {
                        R.string.notice_host_saved
                    } else {
                        R.string.notice_host_updated
                    },
                ),
                failureMessage = uiText(R.string.notice_host_save_failed),
            ) {
                terminalDataRepository.saveHostProfile(profile, selectedAuthentication)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportCatalogWriteFailure(error, uiText(R.string.notice_host_save_failed))
            Result.failure(error)
        } finally {
            submission.wipe()
            (authentication as? HostAuthenticationUpdate.SavePassword)?.secret?.fill(0)
        }
    }

    internal fun resolveConnectionsHostEditorDraft(
        editorToken: String,
        fallback: HostEditorDraft,
    ): HostEditorDraft = synchronized(connectionsHostEditorDrafts) {
        connectionsHostEditorDrafts.getOrPut(editorToken) { fallback }
    }

    internal fun retainConnectionsHostEditorDraft(editorToken: String, draft: HostEditorDraft) {
        synchronized(connectionsHostEditorDrafts) {
            connectionsHostEditorDrafts[editorToken] = draft
        }
    }

    internal fun clearConnectionsHostEditorDraft(editorToken: String) {
        synchronized(connectionsHostEditorDrafts) {
            connectionsHostEditorDrafts.remove(editorToken)
        }
    }

    internal fun deleteConnectionsHost(persistentId: String) {
        if (!_uiState.value.settingsReady) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { terminalDataRepository.deleteHostProfile(persistentId) }
                .onSuccess { committed ->
                    publishCommittedSettings(committed, uiText(R.string.notice_host_deleted))
                }
                .onFailure { error ->
                    reportCatalogWriteFailure(error, uiText(R.string.notice_host_delete_failed))
                }
        }
    }

    internal suspend fun saveConnectionsSnippet(snippet: Snippet): Result<Unit> {
        val catalog = connectionsCatalogLoad.value?.catalog
        if (!_uiState.value.settingsReady || catalog == null) {
            _uiState.update {
                it.copy(notice = uiText(R.string.notice_local_data_before_save_snippet))
            }
            return Result.failure(IllegalStateException("The local catalog is unavailable."))
        }
        if (catalog.snippets.none { it.snippet.id == snippet.id } &&
            catalog.snippets.size >= UserSettings.MAX_SNIPPETS
        ) {
            _uiState.update { it.copy(notice = uiText(R.string.notice_delete_snippet_before_add)) }
            return Result.failure(IllegalStateException("The saved-snippet limit has been reached."))
        }
        return acknowledgedCatalogWrite(
            successNotice = uiText(R.string.notice_snippet_saved),
            failureMessage = uiText(R.string.notice_snippet_save_failed),
        ) {
            terminalDataRepository.saveSnippet(snippet)
        }
    }

    internal fun deleteConnectionsSnippet(persistentId: String) {
        if (!_uiState.value.settingsReady) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { terminalDataRepository.deleteSnippet(persistentId) }
                .onSuccess { committed ->
                    publishCommittedSettings(committed, uiText(R.string.notice_snippet_deleted))
                }
                .onFailure { error ->
                    reportCatalogWriteFailure(error, uiText(R.string.notice_snippet_delete_failed))
                }
        }
    }

    internal suspend fun renameConnectionsKey(
        persistentId: String,
        name: String,
        comment: String?,
    ): Result<Unit> {
        if (!_uiState.value.settingsReady) {
            _uiState.update {
                it.copy(notice = uiText(R.string.notice_local_data_before_update_key))
            }
            return Result.failure(IllegalStateException("The local catalog is unavailable."))
        }
        return acknowledgedCatalogWrite(
            successNotice = uiText(R.string.notice_ssh_key_updated),
            failureMessage = uiText(R.string.notice_ssh_key_update_failed),
        ) {
            terminalDataRepository.renameIdentity(persistentId, name, comment)
        }
    }

    internal fun deleteConnectionsKey(persistentId: String) {
        if (!_uiState.value.settingsReady) return
        val referenceCount = connectionsCatalogLoad.value?.catalog?.credentials.orEmpty().count { credential ->
            (credential.authentication as? StoredSshAuthentication.PrivateKey)?.keyIdentityId == persistentId
        }
        if (referenceCount > 0) {
            _uiState.update { state ->
                state.copy(
                    notice = quantityText(
                        R.plurals.notice_ssh_key_references,
                        referenceCount,
                        referenceCount,
                    ),
                )
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { terminalDataRepository.deleteIdentity(persistentId) }
                .onSuccess { committed ->
                    publishCommittedSettings(committed, uiText(R.string.notice_private_key_removed))
                }
                .onFailure { error ->
                    reportCatalogWriteFailure(error, uiText(R.string.notice_private_key_remove_failed))
                }
        }
    }

    internal suspend fun generateConnectionsKey(request: KeyGenerationRequest): Result<Unit> {
        val catalog = connectionsCatalogLoad.value?.catalog
        if (!_uiState.value.settingsReady || catalog == null) {
            request.wipe()
            _uiState.update {
                it.copy(notice = uiText(R.string.notice_local_data_before_generate_key))
            }
            return Result.failure(IllegalStateException("The local catalog is unavailable."))
        }
        if (catalog.identities.size >= UserSettings.MAX_IDENTITIES) {
            request.wipe()
            _uiState.update { it.copy(notice = uiText(R.string.notice_delete_key_before_generate)) }
            return Result.failure(IllegalStateException("The SSH-key limit has been reached."))
        }
        var passphrase: ByteArray? = null
        var generated: com.yanjiyu.terminalspike.core.data.repository.GeneratedSshKeyMaterial? = null
        return try {
            passphrase = request.passphrase.takeIf(CharArray::isNotEmpty)?.toUtf8Secret()
            request.wipe()
            acknowledgedCatalogWrite(
                successNotice = uiText(R.string.notice_key_generated, UiText.Dynamic(request.name)),
                failureMessage = uiText(R.string.notice_key_generate_failed),
            ) {
                generated = JschSshKeyMaterialGenerator.generate(request.algorithm, passphrase)
                val material = requireNotNull(generated)
                val metadata = JschPrivateKeyMetadataInspector.inspect(material.privateKey)
                val now = System.currentTimeMillis().coerceAtLeast(0)
                val identity = SshKeyIdentity(
                    id = UUID.randomUUID().toString(),
                    name = request.name,
                    algorithm = metadata.algorithm,
                    publicKeyFingerprint = metadata.fingerprintSha256,
                    publicKey = metadata.openSshPublicKey,
                    privateKeySecretReferenceId = UUID.randomUUID().toString(),
                    origin = SshKeyOrigin.GENERATED,
                    isPassphraseProtected = metadata.isPassphraseProtected,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    comment = request.comment,
                )
                terminalDataRepository.saveIdentity(identity, material.privateKey)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportCatalogWriteFailure(error, uiText(R.string.notice_key_generate_failed))
            Result.failure(error)
        } finally {
            request.wipe()
            passphrase?.fill(0)
            generated?.wipe()
        }
    }

    internal fun connectionsPublicKey(persistentId: String): String? =
        connectionsCatalogLoad.value?.catalog?.identities
            ?.firstOrNull { it.id == persistentId }
            ?.publicKey

    internal fun connectionsSnippetCommand(persistentId: String): String? =
        connectionsCatalogLoad.value?.catalog?.snippets
            ?.firstOrNull { it.snippet.id == persistentId }
            ?.snippet
            ?.command

    internal fun connectionsHostName(persistentHostId: String): String? =
        connectionsCatalogLoad.value?.catalog?.hosts
            ?.firstOrNull { it.profile.id == persistentHostId }
            ?.profile
            ?.hostname

    internal fun insertConnectionsSnippet(persistentId: String, targetSessionId: Long): Boolean {
        val presentationId = connectionsCatalogLoad.value
            ?.catalog
            ?.snippetPresentationId(persistentId)
            ?: return false
        return sendSnippet(
            id = presentationId,
            targetSessionId = targetSessionId,
            sendsImmediatelyOverride = false,
        )
    }

    internal fun runConnectionsSnippet(persistentId: String, targetSessionId: Long): Boolean {
        val snippet = connectionsCatalogLoad.value?.catalog?.snippets
            ?.firstOrNull { it.snippet.id == persistentId }
            ?.snippet
            ?: return false
        val state = _uiState.value
        val target = state.sessions.firstOrNull { it.id == targetSessionId }
        if (target?.connectionState !is ConnectionState.Connected) {
            _uiState.update { it.copy(notice = uiText(R.string.notice_terminal_not_connected)) }
            return false
        }

        val accepted = controllerFor(targetSessionId)
            .sendPaste(snippet.command, appendEnter = snippet.appendEnter)
        _uiState.update {
            it.copy(
                activeSessionId = targetSessionId,
                notice = if (accepted) {
                    uiText(R.string.notice_snippet_sent, UiText.Dynamic(snippet.name))
                } else {
                    uiText(R.string.notice_terminal_input_queue_busy)
                },
            )
        }
        return accepted
    }

    internal fun connectConnectionsHost(request: HostConnectRequest): Boolean {
        val catalog = connectionsCatalogLoad.value?.catalog
        val catalogHost = catalog?.hosts?.firstOrNull { it.profile.id == request.persistentHostId }
        if (!_uiState.value.settingsReady || catalog == null || catalogHost == null) {
            request.wipe()
            _uiState.update { it.copy(notice = uiText(R.string.notice_saved_host_unavailable)) }
            return false
        }
        val profile = catalogHost.profile
        val targetKeyboardProfile = (
            profile.keyboardProfileId ?: catalog.defaultKeyboardProfileId
        ).let { profileId -> catalog.keyboardProfiles.firstOrNull { it.id == profileId } }
        if (targetKeyboardProfile?.toRuntimeAccessoryActionsOrNull() == null) {
            request.wipe()
            _uiState.update {
                it.copy(notice = uiText(R.string.notice_keyboard_profile_unavailable))
            }
            return false
        }
        if (!_uiState.value.canStartSshSession(null)) {
            request.wipe()
            _uiState.update { it.copy(notice = uiText(R.string.notice_session_limit)) }
            return false
        }
        val connectionOptions = if (request.forceSsh) {
            RemoteConnectionOptions.SSH
        } else {
            RemoteConnectionOptions(
                protocol = profile.protocol,
                moshPort = profile.moshPort,
                moshPortRange = profile.moshPortRange,
                moshServerCommand = profile.moshServerCommand,
                moshLocale = profile.moshLocale,
                moshFallbackPolicy = profile.moshFallbackPolicy,
            )
        }
        if (
            connectionOptions.protocol == ConnectionProtocol.MOSH &&
            !_uiState.value.moshExtension.isAvailable &&
            connectionOptions.moshFallbackPolicy != MoshFallbackPolicy.AUTOMATIC
        ) {
            request.wipe()
            _uiState.update {
                it.copy(notice = uiText(R.string.notice_mosh_connect_ssh_instead))
            }
            return false
        }
        val credential = profile.credentialId?.let { id ->
            catalog.credentials.firstOrNull { it.id == id }
        }
        val presentationProfile = catalog.hostPresentationId(profile.id)?.let { presentationId ->
            _uiState.value.profiles.firstOrNull { it.id == presentationId }
        }
        val selectedIdentity = (credential?.authentication as? StoredSshAuthentication.PrivateKey)
            ?.keyIdentityId
            ?.let(catalog::identityPresentationId)
            ?.let { presentationId -> _uiState.value.identities.firstOrNull { it.id == presentationId } }
        val transientSecret = try {
            request.secret.toUtf8Secret()
        } catch (error: Exception) {
            _uiState.update { it.copy(notice = uiText(R.string.notice_secret_prepare_failed)) }
            return false
        } finally {
            request.wipe()
        }
        val deferredSecrets = DeferredSecretPair(transientSecret)
        val job = viewModelScope.launch(
            context = Dispatchers.IO,
            start = CoroutineStart.LAZY,
        ) {
            var claimedSecret: ByteArray? = null
            try {
                claimedSecret = deferredSecrets.claim().first
                val inputSecret = claimedSecret?.takeIf(ByteArray::isNotEmpty)
                val authentication = credential?.authentication
                val savedSecretAvailable = credential?.savedSecretAvailability ==
                    CatalogSecretAvailability.AVAILABLE
                var password: ByteArray? = null
                var passphrase: ByteArray? = null
                var loadSavedPassphrase: (suspend () -> ByteArray)? = null
                var savedPasswordProfile: SavedSshProfile? = null
                var keyboardInteractive: KeyboardInteractiveStart? = null
                when (authentication) {
                    is StoredSshAuthentication.PrivateKey -> {
                        if (selectedIdentity == null || !selectedIdentity.isAvailable) {
                            error("The selected private key is unavailable.")
                        }
                        if (inputSecret == null && savedSecretAvailable) {
                            val credentialId = requireNotNull(credential).id
                            val profileId = presentationProfile?.id
                            loadSavedPassphrase = {
                                try {
                                    terminalDataRepository.copyCredentialSecret(credentialId)
                                } catch (error: CredentialStoreException) {
                                    markStoredCredentialUnavailable(profileId = profileId, error = error)
                                    throw error
                                }
                            }
                        }
                        val identityMetadata = catalog.identities.firstOrNull {
                            it.id == authentication.keyIdentityId
                        }
                        if (identityMetadata?.isPassphraseProtected == true &&
                            inputSecret == null && loadSavedPassphrase == null
                        ) {
                            error("Enter the private-key passphrase.")
                        }
                        passphrase = inputSecret
                    }
                    is StoredSshAuthentication.KeyboardInteractive -> {
                        keyboardInteractive = if (inputSecret != null) {
                            KeyboardInteractiveStart.SessionOnly(inputSecret)
                        } else if (savedSecretAvailable) {
                            val credentialId = requireNotNull(credential).id
                            KeyboardInteractiveStart.ReusableResponse {
                                terminalDataRepository.copyCredentialSecret(credentialId)
                            }
                        } else {
                            KeyboardInteractiveStart.SessionOnly(initialResponse = null)
                        }
                    }
                    is StoredSshAuthentication.Password, null -> {
                        password = inputSecret
                        savedPasswordProfile = if (inputSecret == null && savedSecretAvailable) {
                            presentationProfile
                                ?: error("The saved password profile is unavailable.")
                        } else {
                            null
                        }
                        if (password == null && savedPasswordProfile == null) {
                            error("Enter the host password.")
                        }
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    if (password === claimedSecret || passphrase === claimedSecret) {
                        claimedSecret = null
                    }
                    val sessionResponse =
                        (keyboardInteractive as? KeyboardInteractiveStart.SessionOnly)
                            ?.initialResponse
                    if (sessionResponse === claimedSecret) claimedSecret = null
                    startRemoteSession(
                        host = profile.hostname,
                        port = profile.port,
                        username = profile.username,
                        password = password,
                        selectedIdentity = selectedIdentity,
                        passphrase = passphrase,
                        loadSavedPassphrase = loadSavedPassphrase,
                        savedPasswordProfile = savedPasswordProfile,
                        sourceProfile = presentationProfile,
                        workspaceName = profile.displayName,
                        replacementSessionId = null,
                        connectionOptions = connectionOptions,
                        keyboardInteractive = keyboardInteractive,
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appLogger.warning(AppLogEvent.SETTINGS_READ_FAILED, error) {
                    "Saved host authentication could not be resolved."
                }
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { it.copy(notice = uiText(R.string.notice_host_start_failed)) }
                }
            } finally {
                claimedSecret?.fill(0)
            }
        }
        job.invokeOnCompletion { deferredSecrets.wipeUnclaimed() }
        job.start()
        return true
    }

    internal fun openSftp(persistentHostId: String) {
        openSftp(persistentHostId, transientSecret = null)
    }

    internal fun submitSftpAuthentication(persistentHostId: String, secret: CharArray) {
        val bytes = try {
            secret.toUtf8Secret()
        } finally {
            secret.fill('\u0000')
        }
        openSftp(persistentHostId, bytes)
    }

    private fun openSftp(persistentHostId: String, transientSecret: ByteArray?) {
        val catalog = connectionsCatalogLoad.value?.catalog
        val profile = catalog?.hosts?.firstOrNull { it.profile.id == persistentHostId }?.profile
        val credential = profile?.credentialId?.let { credentialId ->
            catalog.credentials.firstOrNull { it.id == credentialId }
        }
        if (catalog == null || profile == null) {
            transientSecret?.fill(0)
            _uiState.update { it.copy(notice = uiText(R.string.notice_saved_host_unavailable)) }
            return
        }
        val storedAuthentication = credential?.authentication
        val hasSavedSecret = credential?.savedSecretAvailability == CatalogSecretAvailability.AVAILABLE
        val identity = (storedAuthentication as? StoredSshAuthentication.PrivateKey)
            ?.keyIdentityId
            ?.let { id -> catalog.identities.firstOrNull { it.id == id } }
        val requiredSecret = when {
            storedAuthentication is StoredSshAuthentication.PrivateKey &&
                identity?.isPassphraseProtected == true && !hasSavedSecret -> SftpSecretKind.PASSPHRASE
            (storedAuthentication is StoredSshAuthentication.Password || storedAuthentication == null) &&
                !hasSavedSecret -> SftpSecretKind.PASSWORD
            else -> null
        }
        if (requiredSecret != null && transientSecret == null) {
            sftp.requestAuthentication(profile.id, profile.displayName, requiredSecret)
            return
        }
        if (storedAuthentication is StoredSshAuthentication.PrivateKey && identity == null) {
            transientSecret?.fill(0)
            _uiState.update { it.copy(notice = uiText(R.string.notice_choose_available_private_key)) }
            return
        }
        val authentication = when (storedAuthentication) {
            is StoredSshAuthentication.PrivateKey -> com.yanjiyu.terminalspike.connection.SshAuthentication.PrivateKey(
                identityName = requireNotNull(identity).name,
                loadKey = { terminalDataRepository.copyPrivateKey(identity.id) },
                passphrase = transientSecret,
                loadPassphrase = if (transientSecret == null && hasSavedSecret) {
                    { terminalDataRepository.copyCredentialSecret(requireNotNull(credential).id) }
                } else null,
            )
            is StoredSshAuthentication.KeyboardInteractive -> if (transientSecret != null) {
                com.yanjiyu.terminalspike.connection.SshAuthentication.KeyboardInteractive.SessionOnly(
                    transientSecret,
                )
            } else if (hasSavedSecret) {
                com.yanjiyu.terminalspike.connection.SshAuthentication.KeyboardInteractive.ReusableResponse {
                    terminalDataRepository.copyCredentialSecret(requireNotNull(credential).id)
                }
            } else {
                com.yanjiyu.terminalspike.connection.SshAuthentication.KeyboardInteractive.SessionOnly()
            }
            is StoredSshAuthentication.Password, null -> if (transientSecret != null) {
                com.yanjiyu.terminalspike.connection.SshAuthentication.Password(transientSecret)
            } else {
                com.yanjiyu.terminalspike.connection.SshAuthentication.StoredPassword {
                    terminalDataRepository.copyCredentialSecret(requireNotNull(credential).id)
                }
            }
        }
        sftp.connect(
            hostName = profile.displayName,
            config = SshConnectionConfig(
                host = profile.hostname,
                port = profile.port,
                username = profile.username,
                authentication = authentication,
                keepaliveIntervalSeconds = profile.keepaliveIntervalSeconds ?: 30,
            ),
        )
    }

    /**
     * Takes ownership of [submission] and starts one cancellable test operation. Re-testing
     * retires the prior connecting transport before publishing the new operation token.
     */
    internal fun startConnectionsHostTest(submission: HostEditorSubmission): Long {
        val prepared = submission.prepareHostConnectionTest()
        var ownershipTransferred = false
        return try {
            hostConnectionTests.start(prepared).also { ownershipTransferred = true }
        } finally {
            if (!ownershipTransferred) prepared.wipeSecret()
        }
    }

    internal fun answerConnectionsHostIdentity(
        operationToken: Long,
        promptToken: Long,
        decision: HostIdentityDecision,
    ): Boolean = hostConnectionTests.answerHostIdentity(operationToken, promptToken, decision)

    /** Takes ownership of [responses] on every path. */
    internal fun answerConnectionsKeyboardInteractive(
        operationToken: Long,
        challengeToken: Long,
        responses: List<CharArray>,
    ): Boolean = hostConnectionTests.answerKeyboardInteractive(
        operationToken,
        challengeToken,
        responses,
    )

    internal fun cancelConnectionsKeyboardInteractive(
        operationToken: Long,
        challengeToken: Long,
    ): Boolean = hostConnectionTests.cancelKeyboardInteractive(operationToken, challengeToken)

    internal fun cancelConnectionsHostTest(operationToken: Long? = null) {
        hostConnectionTests.cancel(operationToken)
    }

    private suspend fun runConnectionsHostTest(
        prepared: PreparedHostConnectionTest,
        interaction: HostConnectionTestInteraction,
    ): HostConnectionTestResult {
        var loadedPassphrase: ByteArray? = null
        return try {
            val value = prepared.value
            val catalog = connectionsCatalogLoad.value?.catalog
            ?: return HostConnectionTestResult.Failed(
                ConnectionTestStage.AUTHENTICATION,
                uiText(R.string.connections_test_local_data_unavailable),
                emptySet(),
            )
        val terminalProfileId = value.terminalProfileId
            ?: catalog.defaultTerminalProfileId
        val terminalType = catalog.terminalProfiles
            .firstOrNull { profile -> profile.id == terminalProfileId }
            ?.termValue
            ?: return HostConnectionTestResult.Failed(
                ConnectionTestStage.SHELL,
                if (value.terminalProfileId == null) {
                    uiText(R.string.connections_test_default_terminal_profile_unavailable)
                } else {
                    uiText(R.string.connections_test_selected_terminal_profile_unavailable)
                },
                emptySet(),
            )
        val currentProfile = value.persistentId?.let { id ->
            catalog.hosts.firstOrNull { it.profile.id == id }?.profile
        }
        val credential = currentProfile?.credentialId?.let { id ->
            catalog.credentials.firstOrNull { it.id == id }
        }
        val sameEndpoint = currentProfile != null &&
            currentProfile.hostname.equals(value.hostname, ignoreCase = true) &&
            currentProfile.port == value.port &&
            currentProfile.username == value.username
        val inputSecret = prepared.borrowSecret()
        val authentication = when (value.authenticationMethod) {
            HostAuthenticationMethod.PASSWORD -> when {
                inputSecret.isNotEmpty() -> SshAuthentication.Password(inputSecret)
                sameEndpoint && credential?.authentication is StoredSshAuthentication.Password &&
                    credential.savedSecretAvailability == CatalogSecretAvailability.AVAILABLE ->
                    SshAuthentication.StoredPassword {
                        terminalDataRepository.copyCredentialSecret(credential.id)
                    }
                else -> return HostConnectionTestResult.Failed(
                    ConnectionTestStage.AUTHENTICATION,
                    uiText(R.string.connections_test_enter_password),
                    emptySet(),
                )
            }
            HostAuthenticationMethod.KEYBOARD_INTERACTIVE -> when {
                inputSecret.isNotEmpty() ->
                    SshAuthentication.KeyboardInteractive.SessionOnly(inputSecret)
                sameEndpoint && credential?.authentication is StoredSshAuthentication.KeyboardInteractive &&
                    credential.savedSecretAvailability == CatalogSecretAvailability.AVAILABLE ->
                    SshAuthentication.KeyboardInteractive.ReusableResponse {
                        terminalDataRepository.copyCredentialSecret(credential.id)
                    }
                else -> SshAuthentication.KeyboardInteractive.SessionOnly()
            }
            HostAuthenticationMethod.PRIVATE_KEY -> {
                val identityId = requireNotNull(value.keyIdentityId)
                val identity = catalog.identities.firstOrNull { it.id == identityId }
                    ?: return HostConnectionTestResult.Failed(
                        ConnectionTestStage.AUTHENTICATION,
                        uiText(R.string.connections_test_private_key_unavailable),
                        emptySet(),
                    )
                val savedPassphrase = if (
                    inputSecret.isEmpty() && sameEndpoint &&
                    (credential?.authentication as? StoredSshAuthentication.PrivateKey)
                        ?.keyIdentityId == identityId &&
                    credential.savedSecretAvailability == CatalogSecretAvailability.AVAILABLE
                ) {
                    terminalDataRepository.copyCredentialSecret(credential.id)
                        .also { loadedPassphrase = it }
                } else {
                    null
                }
                if (identity.isPassphraseProtected && inputSecret.isEmpty() && savedPassphrase == null) {
                    return HostConnectionTestResult.Failed(
                        ConnectionTestStage.AUTHENTICATION,
                        uiText(R.string.connections_test_enter_key_passphrase),
                        emptySet(),
                    )
                }
                SshAuthentication.PrivateKey(
                    identityName = identity.name,
                    loadKey = { terminalDataRepository.copyPrivateKey(identity.id) },
                    passphrase = inputSecret.takeIf(ByteArray::isNotEmpty) ?: savedPassphrase,
                )
            }
        }
            hostConnectionTester.test(
                HostConnectionTestSpec(
                    host = value.hostname,
                    port = value.port,
                    username = value.username,
                    authentication = authentication,
                    terminalType = terminalType,
                    startupCommandConfigured = value.startupCommand != null,
                ),
                interaction,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            HostConnectionTestResult.Failed(
                stage = ConnectionTestStage.AUTHENTICATION,
                message = error.message?.let { UiText.Dynamic(it) }
                    ?: uiText(R.string.connections_test_could_not_start),
                completedStages = emptySet(),
            )
        } finally {
            loadedPassphrase?.fill(0)
            prepared.wipeSecret()
        }
    }

    fun controllerFor(sessionId: Long): TerminalController =
        localSessions.controllerFor(sessionId) ?: remoteSessions.controllerFor(sessionId) ?: controller

    /** Returns no controller for a stale/closed session ID instead of retargeting to local state. */
    internal fun controllerForExistingSession(sessionId: Long): TerminalController? = when {
        sessionId == LOCAL_TERMINAL_SESSION_ID -> controller
        _uiState.value.sessions.none { it.id == sessionId } -> null
        else -> localSessions.controllerFor(sessionId) ?: remoteSessions.controllerFor(sessionId)
    }

    /** Captures one bounded export while the document picker owns the Activity window. */
    internal suspend fun prepareTerminalTranscriptExport(sessionId: Long): Boolean {
        val sessionController = controllerForExistingSession(sessionId) ?: return false
        val snapshot = withContext(Dispatchers.Default) { sessionController.transcriptSnapshot() }
        if (controllerForExistingSession(sessionId) !== sessionController) return false
        return pendingTranscriptExport.offer(snapshot)
    }

    /** Called for both picker success and cancellation; a snapshot can leave memory only once. */
    internal fun consumeTerminalTranscriptExport(): TerminalTranscriptSnapshot? =
        pendingTranscriptExport.consume()

    internal fun cancelTerminalTranscriptExport() = pendingTranscriptExport.clear()

    /** Resolves the active saved-host override without exposing the full profile to the UI. */
    internal fun bellSettingsForSession(sessionId: Long): BellSettings {
        val explicitProfileId = _uiState.value.sessions
            .firstOrNull { it.id == sessionId }
            ?.terminalProfileId
        return explicitProfileId
            ?.let(terminalProfilesById::get)
            ?.bell
            ?: defaultTerminalProfile?.bell
            ?: BellSettings()
    }

    fun consumeNotice(presentedNotice: UiText) {
        _uiState.update { state -> state.afterNoticePresented(presentedNotice) }
    }

    fun retrySettingsRecovery() {
        val state = _uiState.value
        if (state.settingsRecoveryFailure == null || state.settingsRecoveryInProgress) return
        _uiState.update { it.copy(settingsRecoveryInProgress = true) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { loadLocalSettings(retry = true) }
                .onFailure { error ->
                    appLogger.warning(AppLogEvent.SETTINGS_READ_FAILED, error)
                    _uiState.update {
                        it.copy(
                            settingsRecoveryInProgress = false,
                            notice = uiText(R.string.notice_settings_still_unavailable),
                        )
                    }
                }
        }
    }

    fun resetSettingsAfterRecoveryConfirmation() {
        val state = _uiState.value
        if (state.settingsRecoveryFailure == null || state.settingsRecoveryInProgress) return
        _uiState.update { it.copy(settingsRecoveryInProgress = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val reset = runCatching { localAppDataResetter.requestReset() }
            reset.exceptionOrNull()?.let { error ->
                appLogger.warning(AppLogEvent.SETTINGS_WRITE_FAILED, error)
            }
            if (reset.getOrDefault(false)) {
                // Android clears every private store and stops this process asynchronously. Do not
                // perform another app-owned disk read or write after the request is accepted.
                return@launch
            }
            _uiState.update {
                it.copy(
                    settingsRecoveryInProgress = false,
                    notice = uiText(R.string.notice_app_data_reset_failed),
                )
            }
        }
    }

    internal fun connectSsh(
        host: String,
        portText: String,
        username: String,
        password: CharArray,
        identityId: Long? = null,
        passphrase: CharArray = CharArray(0),
        saveProfile: Boolean = false,
        selectedProfileId: Long? = null,
        savePassword: Boolean = false,
        replacementSessionId: Long? = null,
        connectionOptions: RemoteConnectionOptions = RemoteConnectionOptions.SSH,
        sessionName: String = "",
    ) {
        var pendingPassword: ByteArray? = null
        var pendingPassphrase: ByteArray? = null
        try {
            pendingPassword = password.toUtf8Secret()
            pendingPassphrase = passphrase.toUtf8Secret()
        } catch (error: Exception) {
            pendingPassword?.fill(0)
            pendingPassphrase?.fill(0)
            _uiState.update { it.copy(notice = uiText(R.string.notice_secret_prepare_failed)) }
            return
        } finally {
            password.fill('\u0000')
            passphrase.fill('\u0000')
        }
        val normalizedHost = host.trim().removeSurrounding("[", "]")
        val normalizedUsername = username.trim()
        val port = portText.toIntOrNull()
        val selectedProfile = selectedProfileId?.let { id ->
            _uiState.value.profiles.firstOrNull { profile -> profile.id == id }
        }
        val selectedProfileOptions = selectedProfile?.remoteConnectionOptionsOrNull()
        val matchingProfile = if (selectedProfileId != null) {
            selectedProfile
        } else {
            _uiState.value.profiles.firstOrNull { profile ->
                profile.canConnectFromQuickUi &&
                    profile.remoteConnectionOptionsOrNull() == connectionOptions &&
                    profile.host.equals(normalizedHost, ignoreCase = true) && profile.port == port &&
                    profile.username == normalizedUsername
            }
        }
        val savedPasswordProfile = selectedProfile?.takeIf { profile ->
            profile.hasSavedPassword &&
                selectedProfileOptions == connectionOptions &&
                profile.host.equals(normalizedHost, ignoreCase = true) && profile.port == port &&
                profile.username == normalizedUsername
        }
        val requestedIdentity = _uiState.value.identities.firstOrNull { it.id == identityId }
        val error: UiText? = when {
            !_uiState.value.settingsReady ->
                uiText(R.string.notice_workspace_settings_unavailable)
            selectedProfileId != null && selectedProfile == null ->
                uiText(R.string.notice_saved_host_unavailable)
            selectedProfile?.canConnectFromQuickUi == false ->
                uiText(R.string.notice_saved_host_unsupported)
            selectedProfile != null && selectedProfileOptions != connectionOptions ->
                uiText(R.string.notice_saved_host_settings_changed)
            connectionOptions.protocol == ConnectionProtocol.MOSH &&
                !_uiState.value.moshExtension.isAvailable ->
                uiText(R.string.notice_mosh_extension_unavailable)
            normalizedHost.isBlank() || normalizedHost.length > UserSettings.MAX_HOST_LENGTH ||
                normalizedHost.any(Char::isWhitespace) -> uiText(R.string.notice_invalid_host)
            port == null || port !in 1..65_535 -> uiText(R.string.notice_invalid_port)
            username.isBlank() || username.length > UserSettings.MAX_USERNAME_LENGTH ||
                username.any { it.isWhitespace() || it.isISOControl() } ->
                uiText(R.string.notice_invalid_username)
            identityId == null && requireNotNull(pendingPassword).isEmpty() &&
                savedPasswordProfile == null ->
                uiText(R.string.notice_connection_auth_required)
            requireNotNull(pendingPassword).size > MAX_SECRET_LENGTH ->
                uiText(R.string.notice_password_too_long)
            requireNotNull(pendingPassphrase).size > MAX_SECRET_LENGTH ->
                uiText(R.string.notice_passphrase_too_long)
            identityId != null && requestedIdentity == null ->
                uiText(R.string.notice_choose_available_private_key)
            requestedIdentity?.isAvailable == false ->
                uiText(R.string.notice_reimport_private_key_legacy)
            savePassword && (
                identityId != null || requireNotNull(pendingPassword).isEmpty() || !saveProfile
            ) ->
                uiText(R.string.notice_save_password_requires_host)
            saveProfile && matchingProfile == null && _uiState.value.profiles.size >= UserSettings.MAX_PROFILES ->
                uiText(R.string.notice_delete_profile_before_save)
            replacementSessionId != null && !_uiState.value.canStartSshSession(replacementSessionId) ->
                uiText(R.string.notice_session_reconnect_unavailable)
            else -> null
        }
        if (error != null) {
            pendingPassword.fill(0)
            pendingPassphrase.fill(0)
            _uiState.update { it.copy(notice = error) }
            return
        }
        if (!_uiState.value.canStartSshSession(replacementSessionId)) {
            pendingPassword.fill(0)
            pendingPassphrase.fill(0)
            _uiState.update { it.copy(notice = uiText(R.string.notice_session_limit)) }
            return
        }
        val validatedPort = port!!
        val selectedIdentity = requestedIdentity
        val requestedSessionName = sessionName.trim().take(UserSettings.MAX_LABEL_LENGTH)
        val fallbackSessionName = nextDefaultSessionName(
            protocol = connectionOptions.protocol,
            sessions = _uiState.value.sessions,
        )
        fun connect(
            sourceProfile: SavedSshProfile?,
            workspaceName: String,
            ownedPassword: ByteArray?,
            ownedPassphrase: ByteArray?,
            reloadablePasswordProfile: SavedSshProfile? = savedPasswordProfile,
        ) {
            startRemoteSession(
                host = normalizedHost,
                port = validatedPort,
                username = normalizedUsername,
                password = ownedPassword,
                selectedIdentity = selectedIdentity,
                passphrase = ownedPassphrase,
                loadSavedPassphrase = null,
                savedPasswordProfile = reloadablePasswordProfile,
                sourceProfile = sourceProfile,
                workspaceName = workspaceName,
                replacementSessionId = replacementSessionId,
                connectionOptions = connectionOptions,
            )
        }
        if (!saveProfile) {
            val ownedPassword = pendingPassword.also { pendingPassword = null }
            val ownedPassphrase = pendingPassphrase.also { pendingPassphrase = null }
            connect(
                matchingProfile,
                requestedSessionName.ifEmpty { matchingProfile?.label ?: fallbackSessionName },
                ownedPassword,
                ownedPassphrase,
            )
            return
        }

        val sameCredentialScope = matchingProfile != null &&
            matchingProfile.host.equals(normalizedHost, ignoreCase = true) &&
            matchingProfile.port == validatedPort &&
            matchingProfile.username == normalizedUsername
        val profile = try {
            val profileId = matchingProfile?.id
                ?: terminalDataRepository.reserveHostPresentationId()
            val workspaceProfileLabel = privacySafeWorkspaceFriendlyName(
                candidate = requestedSessionName.ifEmpty {
                    matchingProfile?.label ?: fallbackSessionName
                },
                protocol = connectionOptions.protocol,
                sensitiveValues = listOf(normalizedUsername, normalizedHost),
            )
            SavedSshProfile(
                id = profileId,
                label = workspaceProfileLabel,
                host = normalizedHost,
                port = validatedPort,
                username = normalizedUsername,
                hasSavedPassword = selectedIdentity == null &&
                    (savePassword ||
                        (matchingProfile?.hasSavedPassword == true && sameCredentialScope)),
                persistentId = matchingProfile?.persistentId
                    ?: terminalDataRepository.persistentHostId(profileId),
                isFavorite = matchingProfile?.isFavorite ?: false,
                protocol = connectionOptions.protocol,
                connectionCompatibility = when {
                    selectedIdentity != null ->
                        SavedHostConnectionCompatibility.PRIVATE_KEY_REQUIRES_FULL_UI
                    connectionOptions.protocol == ConnectionProtocol.MOSH ->
                        SavedHostConnectionCompatibility.MOSH_PASSWORD
                    else -> SavedHostConnectionCompatibility.SSH_PASSWORD
                },
                moshPort = connectionOptions.moshPort,
                moshPortRange = connectionOptions.moshPortRange,
                moshServerCommand = connectionOptions.moshServerCommand,
            )
        } catch (error: Exception) {
            pendingPassword.fill(0)
            pendingPassphrase.fill(0)
            _uiState.update { it.copy(notice = uiText(R.string.notice_host_prepare_failed)) }
            return
        }
        val catalog = connectionsCatalogLoad.value?.catalog
        val existingHost = profile.persistentId?.let { persistentId ->
            catalog?.hosts?.firstOrNull { it.profile.id == persistentId }?.profile
        }
        val authoritativeProfile = runCatching {
            val availableCatalog = requireNotNull(catalog) {
                "The local catalog is unavailable."
            }
            profile.toAuthoritativeQuickConnectProfile(
                catalog = availableCatalog,
                existing = existingHost,
                connectionOptions = connectionOptions,
                nowEpochMillis = System.currentTimeMillis(),
            )
        }.getOrElse {
            pendingPassword.fill(0)
            pendingPassphrase.fill(0)
            _uiState.update { it.copy(notice = uiText(R.string.notice_host_prepare_failed)) }
            return
        }
        val persistentIdentityId = selectedIdentity?.let { identity ->
            catalog?.persistentIdentityId(identity.id) ?: identity.recoveryToken
        }
        if (selectedIdentity != null && persistentIdentityId == null) {
            pendingPassword.fill(0)
            pendingPassphrase.fill(0)
            _uiState.update {
                it.copy(notice = uiText(R.string.notice_reimport_key_before_save_host))
            }
            return
        }
        val deferredSecrets = DeferredSecretPair(
            first = pendingPassword.also { pendingPassword = null },
            second = pendingPassphrase.also { pendingPassphrase = null },
        )
        val job = viewModelScope.launch(
            context = Dispatchers.IO,
            start = CoroutineStart.LAZY,
        ) {
            var claimedPassword: ByteArray? = null
            var claimedPassphrase: ByteArray? = null
            var authenticationUpdate: HostAuthenticationUpdate? = null
            try {
                val claimed = deferredSecrets.claim()
                claimedPassword = claimed.first
                claimedPassphrase = claimed.second
                authenticationUpdate = quickConnectAuthenticationUpdate(
                    persistentIdentityId = persistentIdentityId,
                    savePassword = savePassword,
                    password = requireNotNull(claimedPassword),
                    retainSavedPassword = matchingProfile?.hasSavedPassword == true &&
                        sameCredentialScope,
                )
                val selectedAuthentication = requireNotNull(authenticationUpdate)
                val committed = runCatching {
                    terminalDataRepository.saveHostProfile(
                        profile = authoritativeProfile,
                        authentication = selectedAuthentication,
                    )
                }
                committed.onSuccess { settings -> publishCommittedSettings(settings) }
                    .onFailure { error ->
                        reportCatalogWriteFailure(
                            error,
                            if (savePassword) {
                                uiText(R.string.notice_connected_without_host_password)
                            } else {
                                uiText(R.string.notice_connected_without_host)
                            },
                        )
                    }
                withContext(Dispatchers.Main.immediate) {
                    val persistedSource = if (committed.isSuccess) profile else matchingProfile
                    val savedPasswordWasCommitted = committed.isSuccess && savePassword &&
                        selectedIdentity == null
                    val passwordStart = resolveQuickConnectPasswordStart(
                        savedPasswordCommitted = savedPasswordWasCommitted,
                        password = claimedPassword,
                        persistedProfile = persistedSource,
                        previouslySavedProfile = savedPasswordProfile,
                    )
                    claimedPassword = null
                    val ownedPassphrase = claimedPassphrase.also { claimedPassphrase = null }
                    connect(
                        persistedSource,
                        requestedSessionName.ifEmpty {
                            persistedSource?.label ?: fallbackSessionName
                        },
                        passwordStart.password,
                        ownedPassphrase,
                        passwordStart.savedPasswordProfile,
                    )
                }
            } finally {
                (authenticationUpdate as? HostAuthenticationUpdate.SavePassword)
                    ?.secret
                    ?.fill(0)
                claimedPassword?.fill(0)
                claimedPassphrase?.fill(0)
            }
        }
        job.invokeOnCompletion { deferredSecrets.wipeUnclaimed() }
        job.start()
    }

    private fun runtimeProfileSelectionFor(
        sourceProfile: SavedSshProfile?,
    ): SessionRuntimeProfileSelection {
        val catalog = connectionsCatalogLoad.value?.catalog
        val host = sourceProfile?.persistentId?.let { persistentId ->
            catalog?.hosts?.firstOrNull { it.profile.id == persistentId }?.profile
        }
        val terminal = resolveRuntimeTerminalSelection(
            host = host,
            defaultProfile = defaultTerminalProfile,
            profilesById = terminalProfilesById,
        )
        val terminalProfileId = terminal.explicitProfileId
        val keyboardProfileId = host?.keyboardProfileId
        val defaults = runtimeReliabilityDefaults
        val reliability = resolveRemoteReliability(
            globalKeepaliveIntervalSeconds = defaults.keepaliveIntervalSeconds,
            globalReconnectEnabled = defaults.reconnectEnabled,
            globalReconnectMaxAttempts = defaults.reconnectMaxAttempts,
            hostKeepaliveIntervalSeconds = host?.keepaliveIntervalSeconds,
            hostReconnectPolicy = host?.reconnectPolicy,
        )
        return SessionRuntimeProfileSelection(
            terminalProfileId = terminalProfileId,
            keyboardProfileId = keyboardProfileId,
            terminalProfile = terminal.profile,
            keyboardProfile = if (keyboardProfileId == null) {
                defaultKeyboardProfile
            } else {
                keyboardProfilesById[keyboardProfileId]
            },
            terminalType = terminal.profile?.termValue,
            startupCommand = terminal.startupCommand,
            remoteClipboardMode = terminal.profile?.links?.remoteClipboardMode
                ?: activeRemoteClipboardMode,
            keepaliveIntervalSeconds = reliability.keepaliveIntervalSeconds,
            reliabilityPolicy = RemoteSessionReliabilityPolicy(
                reconnectEnabled = reliability.reconnectEnabled,
                reconnectMaxAttempts = reliability.reconnectMaxAttempts,
            ),
        )
    }

    private fun startRemoteSession(
        host: String,
        port: Int,
        username: String,
        /** Ownership transfers to this method, including on every validation failure. */
        password: ByteArray?,
        selectedIdentity: SavedSshIdentity?,
        /** Ownership transfers to this method, including on every validation failure. */
        passphrase: ByteArray?,
        /** Reloads an explicitly saved passphrase; contains no retained plaintext. */
        loadSavedPassphrase: (suspend () -> ByteArray)?,
        savedPasswordProfile: SavedSshProfile?,
        sourceProfile: SavedSshProfile?,
        workspaceName: String,
        replacementSessionId: Long?,
        connectionOptions: RemoteConnectionOptions,
        keyboardInteractive: KeyboardInteractiveStart? = null,
    ) {
        var ownershipTransferred = false
        try {
            val stateBeforeStart = _uiState.value
            if (!stateBeforeStart.canStartSshSession(replacementSessionId)) {
                _uiState.update {
                    it.copy(
                        notice = if (replacementSessionId == null) {
                            uiText(R.string.notice_session_limit)
                        } else {
                            uiText(R.string.notice_session_reconnect_unavailable)
                        },
                    )
                }
                return
            }
            val runtimeProfiles = runtimeProfileSelectionFor(sourceProfile)
            if (runtimeProfiles.terminalProfile == null || runtimeProfiles.terminalType == null) {
                _uiState.update {
                    it.copy(
                        notice = if (runtimeProfiles.terminalProfileId == null) {
                            uiText(R.string.notice_default_terminal_profile_unavailable)
                        } else {
                            uiText(R.string.notice_terminal_profile_unavailable)
                        },
                    )
                }
                return
            }
            if (runtimeProfiles.keyboardProfile?.toRuntimeAccessoryActionsOrNull() == null) {
                _uiState.update {
                    it.copy(notice = uiText(R.string.notice_keyboard_profile_unavailable))
                }
                return
            }
            terminalBuildFeature.stop()
            controller.stop()
            val authentication = when {
                keyboardInteractive is KeyboardInteractiveStart.SessionOnly ->
                    SshAuthentication.KeyboardInteractive.SessionOnly(
                        keyboardInteractive.initialResponse,
                    )
            keyboardInteractive is KeyboardInteractiveStart.ReusableResponse ->
                SshAuthentication.KeyboardInteractive.ReusableResponse(
                    keyboardInteractive.loadResponse,
                )
            selectedIdentity != null -> SshAuthentication.PrivateKey(
                identityName = selectedIdentity.label,
                loadKey = {
                    try {
                        terminalDataRepository.copyPrivateKey(selectedIdentity.id)
                    } catch (error: CredentialStoreException) {
                        markStoredCredentialUnavailable(identityId = selectedIdentity.id, error = error)
                        throw error
                    }
                },
                passphrase = passphrase?.takeIf(ByteArray::isNotEmpty),
                loadPassphrase = if (passphrase == null || passphrase.isEmpty()) {
                    loadSavedPassphrase
                } else {
                    null
                },
            )
            password?.isNotEmpty() == true -> SshAuthentication.Password(password)
            else -> {
                val profile = requireNotNull(savedPasswordProfile)
                SshAuthentication.StoredPassword {
                    try {
                        terminalDataRepository.copyPassword(profile.id)
                    } catch (error: CredentialStoreException) {
                        markStoredCredentialUnavailable(profileId = profile.id, error = error)
                        throw error
                    }
                }
            }
        }
            if (authentication !is SshAuthentication.Password) password?.fill(0)
            if (authentication !is SshAuthentication.PrivateKey) passphrase?.fill(0)
            if (authentication !is SshAuthentication.KeyboardInteractive.SessionOnly) {
                (keyboardInteractive as? KeyboardInteractiveStart.SessionOnly)
                    ?.initialResponse
                    ?.fill(0)
            }
            val sshConfig = SshConnectionConfig(
            host = host,
            port = port,
            username = username,
            authentication = authentication,
            keepaliveIntervalSeconds = runtimeProfiles.keepaliveIntervalSeconds,
            terminalType = runtimeProfiles.terminalType,
            startupCommand = runtimeProfiles.startupCommand,
            tmuxSessionSelectorEnabled = tmuxSessionSelectorEnabled,
        )
            val safeWorkspaceName = privacySafeWorkspaceFriendlyName(
            candidate = workspaceName,
            protocol = connectionOptions.protocol,
            sensitiveValues = listOf(username, host),
        )
            val connectionRequest = when (connectionOptions.protocol) {
            ConnectionProtocol.SSH -> RemoteSessionConnectionRequest.Ssh(sshConfig)
            ConnectionProtocol.MOSH -> RemoteSessionConnectionRequest.Mosh(
                connectionOptions.toMoshBootstrapRequest(sshConfig),
            )
        }
            val result = remoteSessions.startUserInitiatedSession(
            RemoteSessionStartRequest(
                title = safeWorkspaceName,
                workspaceName = safeWorkspaceName,
                connection = connectionRequest,
                sourceProfileId = sourceProfile?.id,
                hostProfileId = sourceProfile?.persistentId,
                terminalProfileId = runtimeProfiles.terminalProfileId,
                keyboardProfileId = runtimeProfiles.keyboardProfileId,
                replacementSessionId = replacementSessionId,
                terminalConfiguration = RemoteSessionTerminalConfiguration(
                    scrollbackLines = resolveRuntimeScrollbackLines(
                        runtimeProfiles.terminalProfile.scrollbackLines,
                    ),
                    rendererProfile = runtimeProfiles.terminalProfile.toRendererProfile(
                        customTerminalFonts,
                        customTerminalThemesById.values,
                    ),
                    remoteClipboardMode = runtimeProfiles.remoteClipboardMode,
                ),
                reliabilityPolicy = runtimeProfiles.reliabilityPolicy,
                moshFallbackPolicy = connectionOptions.moshFallbackPolicy,
            ),
        )
            // The repository clears authentication arrays on every returned result and retains
            // them only while a successfully started transport still needs them.
            ownershipTransferred = true
            when (result) {
            is StartSshSessionResult.Started -> {
                val showNotificationEducation = shouldShowNotificationPermissionEducation(
                    visibility = result.notificationVisibility,
                    alreadyConsumed = notificationPermissionEducationConsumed,
                )
                if (showNotificationEducation) consumeNotificationPermissionEducation()
                val notificationNotice = if (showNotificationEducation) {
                    uiText(R.string.notice_notification_visibility_limited)
                } else {
                    null
                }
                val runtimeNotice = moshRuntimeLimitNotice(
                    protocol = connectionOptions.protocol,
                    selectedTerminalType = runtimeProfiles.terminalType,
                    startupCommandConfigured = runtimeProfiles.startupCommand != null,
                )
                _uiState.update { state ->
                    state.copy(
                        activeSessionId = result.sessionId,
                        notice = listOfNotNull(notificationNotice, runtimeNotice).toNotice(),
                        notificationPermissionPromptVisible = showNotificationEducation,
                    )
                }
            }
            StartSshSessionResult.SessionLimitReached -> _uiState.update {
                it.copy(notice = uiText(R.string.notice_session_limit))
            }
            StartSshSessionResult.ReplacementUnavailable -> _uiState.update {
                it.copy(notice = uiText(R.string.notice_session_reconnect_unavailable))
            }
            StartSshSessionResult.ForegroundServiceUnavailable -> _uiState.update {
                it.copy(notice = uiText(R.string.notice_background_owner_failed))
            }
            StartSshSessionResult.Cancelled -> Unit
            }
        } finally {
            if (!ownershipTransferred) {
                password?.fill(0)
                passphrase?.fill(0)
                (keyboardInteractive as? KeyboardInteractiveStart.SessionOnly)
                    ?.initialResponse
                    ?.fill(0)
            }
        }
    }

    fun dismissNotificationPermissionPrompt() {
        _uiState.update { it.copy(notificationPermissionPromptVisible = false) }
    }

    private fun consumeNotificationPermissionEducation() {
        if (notificationPermissionEducationConsumed) return
        notificationPermissionEducationConsumed = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appContainer.settings.update {
                    it.setNotificationPermissionEducationConsumed(true)
                }
            }.onFailure { error ->
                appLogger.warning(AppLogEvent.SETTINGS_WRITE_FAILED, error) {
                    "Notification education state could not be persisted."
                }
            }
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                notificationPermissionPromptVisible = false,
                notice = if (granted) {
                    uiText(R.string.notice_notifications_enabled)
                } else {
                    uiText(R.string.notice_notifications_limited_connected)
                },
            )
        }
    }

    internal fun acknowledgeTerminalTask(sessionId: Long) { remoteSessions.acknowledgeTaskStatus(sessionId) }

    fun selectSession(sessionId: Long) {
        if (_uiState.value.sessions.none { it.id == sessionId }) return
        _uiState.update { state ->
            val previousKeyboardId = state.sessions.firstOrNull {
                it.id == state.activeSessionId
            }?.keyboardProfileId
            val selected = state.copy(activeSessionId = sessionId, notice = null)
            val selectedKeyboardId = selected.sessions.first { it.id == sessionId }.keyboardProfileId
            if (state.activeSessionId == sessionId || previousKeyboardId == selectedKeyboardId) {
                selected
            } else {
                withRuntimeKeyboardProfile(
                    state = selected,
                    keyboard = keyboardProfileForSession(selected, sessionId),
                )
            }
        }
    }

    fun selectLastActiveRemoteSessionForTerminalEntry() {
        val targetSessionId = _uiState.value.preferredTerminalEntrySessionId(
            savedStateHandle.get<Long>(LAST_ACTIVE_REMOTE_SESSION_ID),
        ) ?: return
        selectSession(targetSessionId)
    }

    fun profileForSession(sessionId: Long): SavedSshProfile? {
        val profileId = _uiState.value.sessions.firstOrNull { it.id == sessionId }?.sourceProfileId
            ?: return null
        return _uiState.value.profiles.firstOrNull { it.id == profileId }
    }

    internal fun connectionSeedForSession(sessionId: Long): SshConnectionSeed? =
        remoteSessions.connectionSeedFor(sessionId)?.toUiConnectionSeed()

    internal fun duplicateSession(sessionId: Long): SessionDuplicateResult =
        if (sessionId < 0) {
            if (openLocalArch()) SessionDuplicateResult.Started else SessionDuplicateResult.Rejected
        } else when (val result = remoteSessions.duplicateUserInitiatedSession(sessionId)) {
            is DuplicateSshSessionResult.Started -> {
                val showNotificationEducation = shouldShowNotificationPermissionEducation(
                    visibility = result.notificationVisibility,
                    alreadyConsumed = notificationPermissionEducationConsumed,
                )
                if (showNotificationEducation) consumeNotificationPermissionEducation()
                _uiState.update { state ->
                    state.copy(
                        activeSessionId = result.sessionId,
                        notice = if (showNotificationEducation) {
                            uiText(R.string.notice_notification_visibility_limited)
                        } else {
                            null
                        },
                        notificationPermissionPromptVisible = showNotificationEducation,
                    )
                }
                SessionDuplicateResult.Started
            }
            DuplicateSshSessionResult.AuthenticationRequired -> {
                if (duplicateSessionWithSavedPassword(sessionId)) {
                    SessionDuplicateResult.Started
                } else {
                    SessionDuplicateResult.AuthenticationRequired
                }
            }
            DuplicateSshSessionResult.SessionLimitReached -> {
                _uiState.update { it.copy(notice = uiText(R.string.notice_session_limit)) }
                SessionDuplicateResult.Rejected
            }
            DuplicateSshSessionResult.Unavailable -> {
                _uiState.update { it.copy(notice = uiText(R.string.notice_session_duplicate_failed)) }
                SessionDuplicateResult.Rejected
            }
        }

    /** Lets an older one-shot runtime adopt a password that its unchanged source host now saves. */
    private fun duplicateSessionWithSavedPassword(sessionId: Long): Boolean {
        val seed = connectionSeedForSession(sessionId) ?: return false
        val state = _uiState.value
        val profile = seed.sourceProfileId
            ?.let { profileId -> state.profiles.firstOrNull { it.id == profileId } }
            ?.takeIf { it.hasSavedPassword && seed.matches(it) }
            ?: return false
        val openSessionIds = state.sessions.mapTo(hashSetOf(), SessionTabUi::id)
        connectSsh(
            host = seed.host,
            portText = seed.port.toString(),
            username = seed.username,
            password = CharArray(0),
            saveProfile = false,
            selectedProfileId = profile.id,
            connectionOptions = seed.connectionOptions,
        )
        return _uiState.value.activeSessionId !in openSessionIds
    }

    fun profileForRecentSession(recentSessionId: String): SavedSshProfile? {
        val recent = _uiState.value.workspace.recentConnections
            .firstOrNull { it.id == recentSessionId }
            ?: return null
        return recent.sourceProfileId?.let(::profileById)
    }

    fun profileById(profileId: Long): SavedSshProfile? =
        _uiState.value.profiles.firstOrNull { it.id == profileId }

    internal fun launchWorkspaceProfile(profileId: Long): WorkspaceProfileLaunchResult {
        val state = _uiState.value
        val profile = state.profiles.firstOrNull { it.id == profileId }
        val persistentHostId = profile?.persistentId
        if (persistentHostId != null) {
            if (!state.settingsReady) {
                val notice = WorkspaceProfileLaunchDecision.SETTINGS_UNAVAILABLE
                    .workspaceLaunchRejectionNotice()
                _uiState.update {
                    it.copy(notice = notice)
                }
                return WorkspaceProfileLaunchResult.Rejected
            }
            if (!state.canStartSshSession(replacementSessionId = null)) {
                val notice = WorkspaceProfileLaunchDecision.SESSION_LIMIT_REACHED
                    .workspaceLaunchRejectionNotice()
                _uiState.update {
                    it.copy(notice = notice)
                }
                return WorkspaceProfileLaunchResult.Rejected
            }
            val catalog = connectionsCatalogLoad.value?.catalog
            if (catalog == null || catalog.hosts.none { it.profile.id == persistentHostId }) {
                _uiState.update { it.copy(notice = uiText(R.string.notice_saved_host_unavailable)) }
                return WorkspaceProfileLaunchResult.Rejected
            }
            return when (catalog.workspaceStoredAuthenticationDecision(persistentHostId)) {
                WorkspaceStoredAuthenticationDecision.START_DIRECTLY -> {
                    if (connectConnectionsHost(HostConnectRequest(persistentHostId))) {
                        WorkspaceProfileLaunchResult.Started
                    } else {
                        WorkspaceProfileLaunchResult.Rejected
                    }
                }
                WorkspaceStoredAuthenticationDecision.REQUEST_AUTHENTICATION ->
                    WorkspaceProfileLaunchResult.AuthenticationRequired(
                        profileId = profileId,
                        persistentHostId = persistentHostId,
                    )
                WorkspaceStoredAuthenticationDecision.UNAVAILABLE -> {
                    _uiState.update {
                        it.copy(
                            notice = uiText(R.string.notice_saved_host_private_key_unavailable),
                        )
                    }
                    WorkspaceProfileLaunchResult.Rejected
                }
            }
        }

        val decision = state.workspaceProfileLaunchDecision(profileId)
        if (decision == WorkspaceProfileLaunchDecision.REQUEST_AUTHENTICATION) {
            return WorkspaceProfileLaunchResult.AuthenticationRequired(profileId)
        }
        if (decision != WorkspaceProfileLaunchDecision.START_WITH_SAVED_PASSWORD) {
            _uiState.update { current ->
                current.copy(notice = decision.workspaceLaunchRejectionNotice())
            }
            return WorkspaceProfileLaunchResult.Rejected
        }

        val legacyProfile = state.profiles.first { it.id == profileId }
        val connectionOptions = legacyProfile.remoteConnectionOptionsOrNull()
        if (connectionOptions == null) {
            _uiState.update {
                it.copy(notice = uiText(R.string.notice_saved_host_invalid))
            }
            return WorkspaceProfileLaunchResult.Rejected
        }
        connectSsh(
            host = legacyProfile.host,
            portText = legacyProfile.port.toString(),
            username = legacyProfile.username,
            password = CharArray(0),
            selectedProfileId = legacyProfile.id,
            connectionOptions = connectionOptions,
        )
        return WorkspaceProfileLaunchResult.Started
    }

    fun saveSshProfile(
        label: String,
        host: String,
        portText: String,
        username: String,
        existingId: Long? = null,
    ) {
        if (!_uiState.value.settingsReady) return
        val normalizedLabel = label.trim()
        val normalizedHost = host.trim().removeSurrounding("[", "]")
        val normalizedUsername = username.trim()
        val port = portText.toIntOrNull()
        val error: UiText? = when {
            normalizedLabel.isBlank() || normalizedLabel.length > UserSettings.MAX_LABEL_LENGTH ->
                uiText(R.string.notice_profile_name_length, UserSettings.MAX_LABEL_LENGTH)
            normalizedLabel.any(Char::isISOControl) ->
                uiText(R.string.notice_profile_name_unsupported)
            normalizedHost.isBlank() || normalizedHost.length > UserSettings.MAX_HOST_LENGTH ||
                normalizedHost.any(Char::isWhitespace) -> uiText(R.string.notice_invalid_host)
            port == null || port !in 1..65_535 -> uiText(R.string.notice_invalid_port)
            normalizedUsername.isBlank() || normalizedUsername.length > UserSettings.MAX_USERNAME_LENGTH ||
                normalizedUsername.any { it.isWhitespace() || it.isISOControl() } ->
                uiText(R.string.notice_invalid_username)
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(notice = error) }
            return
        }
        val validatedPort = port!!
        val state = _uiState.value
        val selectedExisting = state.profiles.firstOrNull { it.id == existingId }
        if (selectedExisting?.connectionCompatibility != null &&
            selectedExisting.connectionCompatibility != SavedHostConnectionCompatibility.SSH_PASSWORD
        ) {
            _uiState.update {
                it.copy(notice = uiText(R.string.notice_host_editor_incompatible))
            }
            return
        }
        val match = selectedExisting ?: state.profiles.firstOrNull {
            it.connectionCompatibility == SavedHostConnectionCompatibility.SSH_PASSWORD &&
                it.host.equals(normalizedHost, ignoreCase = true) && it.port == validatedPort &&
                it.username == normalizedUsername
        }
        if (match == null && state.profiles.size >= UserSettings.MAX_PROFILES) {
            _uiState.update { it.copy(notice = uiText(R.string.notice_delete_profile_before_add)) }
            return
        }
        val sameCredentialScope = match != null &&
            match.host.equals(normalizedHost, ignoreCase = true) && match.port == validatedPort &&
            match.username == normalizedUsername
        val profile = SavedSshProfile(
            id = match?.id ?: terminalDataRepository.reserveHostPresentationId(),
            label = normalizedLabel,
            host = normalizedHost,
            port = validatedPort,
            username = normalizedUsername,
            hasSavedPassword = match?.hasSavedPassword == true && sameCredentialScope,
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { terminalDataRepository.saveProfile(profile) }
                .onSuccess { committed -> publishCommittedSettings(committed) }
                .onFailure { error ->
                    reportCatalogWriteFailure(error, uiText(R.string.notice_host_save_failed))
                }
        }
    }

    fun deleteSshProfile(id: Long) {
        if (!_uiState.value.settingsReady) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { terminalDataRepository.deleteProfile(id) }
                .onSuccess { committed -> publishCommittedSettings(committed) }
                .onFailure { error ->
                    reportCatalogWriteFailure(error, uiText(R.string.notice_host_delete_failed))
                }
        }
    }

    fun forgetSavedPassword(profileId: Long) {
        if (!_uiState.value.settingsReady) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { terminalDataRepository.forgetPassword(profileId) }
                .onSuccess { committed ->
                    publishCommittedSettings(
                        committed,
                        notice = uiText(R.string.notice_saved_password_removed),
                    )
                }
                .onFailure { error ->
                    reportCatalogWriteFailure(error, uiText(R.string.notice_saved_password_remove_failed))
                }
        }
    }

    fun importSshIdentity(uri: Uri, recoveryToken: String? = null) {
        val resolver = getApplication<Application>().contentResolver
        savedStateHandle.get<String>(PENDING_IDENTITY_IMPORT_URI)
            ?.takeIf { it != uri.toString() }
            ?.let(Uri::parse)
            ?.let(::releaseIdentityImportPermission)
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        savedStateHandle[PENDING_IDENTITY_IMPORT_URI] = uri.toString()
        if (recoveryToken == null) {
            savedStateHandle.remove<String>(PENDING_IDENTITY_RECOVERY_TOKEN)
        } else {
            savedStateHandle[PENDING_IDENTITY_RECOVERY_TOKEN] = recoveryToken
        }
        if (!_uiState.value.settingsReady) {
            _uiState.update {
                it.copy(notice = uiText(R.string.notice_key_import_deferred))
            }
            return
        }
        drainPendingIdentityImport()
    }

    private fun drainPendingIdentityImport() {
        val uri = savedStateHandle.get<String>(PENDING_IDENTITY_IMPORT_URI)?.let(Uri::parse) ?: return
        val recoveryToken = savedStateHandle.get<String>(PENDING_IDENTITY_RECOVERY_TOKEN)
        val state = _uiState.value
        if (!state.settingsReady) return
        savedStateHandle.remove<String>(PENDING_IDENTITY_IMPORT_URI)
        savedStateHandle.remove<String>(PENDING_IDENTITY_RECOVERY_TOKEN)
        val existing = recoveryToken?.let { resolveIdentityRecoveryTarget(state.identities, it) }
        if (recoveryToken != null && existing == null) {
            releaseIdentityImportPermission(uri)
            _uiState.update { it.copy(notice = uiText(R.string.notice_key_reimport_target_missing)) }
            return
        }
        if (existing?.isAvailable == true) {
            releaseIdentityImportPermission(uri)
            _uiState.update { it.copy(notice = uiText(R.string.notice_key_already_available)) }
            return
        }
        if (existing == null && state.identities.size >= UserSettings.MAX_IDENTITIES) {
            releaseIdentityImportPermission(uri)
            _uiState.update { it.copy(notice = uiText(R.string.notice_delete_key_before_import)) }
            return
        }
        val identityId = existing?.id ?: terminalDataRepository.reserveIdentityPresentationId()
        viewModelScope.launch(Dispatchers.IO) {
            var privateKey: ByteArray? = null
            try {
                val resolver = getApplication<Application>().contentResolver
                val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    ?.substringAfterLast('/')
                    ?.filterNot(Char::isISOControl)
                    ?.trim()
                    ?.take(UserSettings.MAX_LABEL_LENGTH)
                    ?.takeIf { it.isNotBlank() }
                    ?: getApplication<Application>().getString(R.string.notice_imported_key_default_name)
                privateKey = resolver.openInputStream(uri)?.use { input ->
                    input.readSensitiveBounded(MAX_PRIVATE_KEY_BYTES)
                } ?: error("Could not open selected key.")
                val committed = terminalDataRepository.importIdentity(
                    presentationId = identityId,
                    label = existing?.label ?: displayName,
                    privateKey = privateKey,
                )
                publishCommittedSettings(
                    committed,
                    notice = if (existing == null) {
                        uiText(R.string.notice_key_imported, UiText.Dynamic(displayName))
                    } else {
                        uiText(R.string.notice_key_reimported, UiText.Dynamic(existing.label))
                    },
                )
            } catch (error: Exception) {
                reportCatalogWriteFailure(error, uiText(R.string.notice_key_import_failed))
            } finally {
                privateKey?.fill(0)
                releaseIdentityImportPermission(uri)
            }
        }
    }

    private fun releaseIdentityImportPermission(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun markStoredCredentialUnavailable(
        profileId: Long? = null,
        identityId: Long? = null,
        error: CredentialStoreException,
    ) {
        appLogger.warning(AppLogEvent.CREDENTIAL_UNAVAILABLE, error)
        _uiState.update { state -> state.afterStoredCredentialUnavailable(profileId, identityId) }
    }

    fun deleteSshIdentity(id: Long) {
        if (!_uiState.value.settingsReady || _uiState.value.identities.none { it.id == id }) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { terminalDataRepository.deleteIdentity(id) }
                .onSuccess { committed ->
                    publishCommittedSettings(
                        committed,
                        notice = uiText(R.string.notice_private_key_removed),
                    )
                }
                .onFailure { error ->
                    reportCatalogWriteFailure(error, uiText(R.string.notice_private_key_remove_failed))
                }
        }
    }

    fun forgetKnownHost(host: String, algorithm: String) {
        viewModelScope.launch(Dispatchers.IO) {
            knownHostPublicationMutex.withLock {
                val removal = runCatching { knownHostManager.remove(host, algorithm) }
                if (removal.isFailure) {
                    removal.exceptionOrNull()?.let { error ->
                        appLogger.warning(AppLogEvent.SETTINGS_WRITE_FAILED, error)
                    }
                    _uiState.update { state ->
                        state.copy(notice = uiText(R.string.notice_trusted_hosts_update_failed))
                    }
                    return@withLock
                }
                val fallback = _uiState.value.knownHosts.filterNot { it.host == host }
                val refresh = refreshKnownHostsOrFallback(fallback, knownHostManager::list)
                if (!refresh.refreshed) {
                    appLogger.warning(AppLogEvent.SETTINGS_READ_FAILED) {
                        "Trusted-host endpoint was forgotten but its list could not be refreshed."
                    }
                }
                _uiState.update { state ->
                    state.copy(
                        knownHosts = refresh.knownHosts,
                        notice = if (refresh.refreshed) {
                            uiText(
                                R.string.notice_trusted_key_forgotten,
                                UiText.Dynamic(host),
                            )
                        } else {
                            uiText(
                                R.string.notice_trusted_key_forgotten_refresh_failed,
                                UiText.Dynamic(host),
                            )
                        },
                    )
                }
            }
        }
    }

    fun saveSnippet(label: String, command: String, appendEnter: Boolean, existingId: Long? = null) {
        if (!_uiState.value.settingsReady) return
        val normalizedLabel = label.trim()
        val error: UiText? = when {
            normalizedLabel.isBlank() || normalizedLabel.length > UserSettings.MAX_LABEL_LENGTH ->
                uiText(R.string.notice_snippet_name_length, UserSettings.MAX_LABEL_LENGTH)
            normalizedLabel.any(Char::isISOControl) ->
                uiText(R.string.notice_snippet_name_unsupported)
            command.isBlank() || command.length > UserSettings.MAX_SNIPPET_LENGTH ->
                uiText(R.string.notice_snippet_command_length, UserSettings.MAX_SNIPPET_LENGTH)
            command.any { it.isISOControl() && it !in "\r\n\t" } ->
                uiText(R.string.notice_snippet_unsupported_control)
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(notice = error) }
            return
        }
        val state = _uiState.value
        if (existingId == null && state.snippets.size >= UserSettings.MAX_SNIPPETS) {
            _uiState.update { it.copy(notice = uiText(R.string.notice_delete_snippet_before_add)) }
            return
        }
        val snippet = CommandSnippet(
            id = existingId ?: terminalDataRepository.reserveSnippetPresentationId(),
            label = normalizedLabel,
            command = command,
            appendEnter = appendEnter,
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { terminalDataRepository.saveSnippet(snippet) }
                .onSuccess { committed -> publishCommittedSettings(committed) }
                .onFailure { error ->
                    reportCatalogWriteFailure(error, uiText(R.string.notice_snippet_save_failed))
                }
        }
    }

    fun deleteSnippet(id: Long) {
        if (!_uiState.value.settingsReady) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { terminalDataRepository.deleteSnippet(id) }
                .onSuccess { committed -> publishCommittedSettings(committed) }
                .onFailure { error ->
                    reportCatalogWriteFailure(error, uiText(R.string.notice_snippet_delete_failed))
                }
        }
    }

    fun sendSnippet(id: Long): Boolean = sendSnippet(id, _uiState.value.activeSessionId)

    fun sendSnippet(id: Long, targetSessionId: Long): Boolean = sendSnippet(
        id = id,
        targetSessionId = targetSessionId,
        sendsImmediatelyOverride = null,
    )

    private fun sendSnippet(
        id: Long,
        targetSessionId: Long,
        sendsImmediatelyOverride: Boolean?,
    ): Boolean {
        val state = _uiState.value
        val snippet = state.snippets.firstOrNull { it.id == id }
            ?.let { saved ->
                sendsImmediatelyOverride?.let { saved.copy(sendsImmediately = it) } ?: saved
            }
            ?: return false
        val target = state.sessions.firstOrNull { it.id == targetSessionId }
        if (target == null ||
            (!target.isLocalTerminal && target.connectionState !is ConnectionState.Connected)
        ) {
            _uiState.update { it.copy(notice = uiText(R.string.notice_connect_before_snippet)) }
            return false
        }
        _uiState.update { it.copy(activeSessionId = targetSessionId) }
        return dispatchSnippet(
            state = state.copy(activeSessionId = targetSessionId),
            snippet = snippet,
            confirmed = false,
        ) != SnippetDispatchOutcome.REJECTED
    }

    fun confirmSnippetSend(id: Long) {
        val state = _uiState.value
        if (state.pendingSnippetSendId != id) return
        if (!state.canConfirmSnippetOnActiveSession(id)) {
            _uiState.update {
                it.copy(
                    pendingSnippetSendId = null,
                    pendingSnippetTargetSessionId = null,
                    pendingSnippetSendsImmediately = null,
                    notice = uiText(R.string.notice_terminal_changed_review_snippet),
                )
            }
            return
        }
        val snippet = state.snippets.firstOrNull { it.id == id }?.let { saved ->
            state.pendingSnippetSendsImmediately?.let {
                saved.copy(sendsImmediately = it)
            } ?: saved
        } ?: run {
            _uiState.update {
                it.copy(
                    pendingSnippetSendId = null,
                    pendingSnippetTargetSessionId = null,
                    pendingSnippetSendsImmediately = null,
                )
            }
            return
        }
        if (!state.activeSession.isLocalTerminal &&
            state.activeSession.connectionState !is ConnectionState.Connected
        ) {
            _uiState.update {
                it.copy(
                    pendingSnippetSendId = null,
                    pendingSnippetTargetSessionId = null,
                    pendingSnippetSendsImmediately = null,
                    notice = uiText(R.string.notice_connect_before_snippet),
                )
            }
            return
        }
        dispatchSnippet(state, snippet, confirmed = true)
    }

    fun cancelSnippetSend() {
        _uiState.update {
            it.copy(
                pendingSnippetSendId = null,
                pendingSnippetTargetSessionId = null,
                pendingSnippetSendsImmediately = null,
            )
        }
    }

    private fun dispatchSnippet(
        state: TerminalSpikeUiState,
        snippet: CommandSnippet,
        confirmed: Boolean,
    ): SnippetDispatchOutcome {
        val activeController = controllerForExistingSession(state.activeSessionId)
        val outcome = dispatchSnippet(snippet, confirmed) { appendEnter ->
            activeController?.sendPaste(snippet.command, appendEnter = appendEnter) == true
        }
        when (outcome) {
            SnippetDispatchOutcome.INSERTED -> {
                _uiState.update {
                    it.copy(
                        pendingSnippetSendId = null,
                        pendingSnippetTargetSessionId = null,
                        pendingSnippetSendsImmediately = null,
                        notice = null,
                    )
                }
            }
            SnippetDispatchOutcome.REQUIRES_CONFIRMATION -> {
                _uiState.update {
                    it.copy(
                        pendingSnippetSendId = snippet.id,
                        pendingSnippetTargetSessionId = state.activeSessionId,
                        pendingSnippetSendsImmediately = snippet.sendsImmediately,
                        notice = null,
                    )
                }
            }
            SnippetDispatchOutcome.SENT -> {
                _uiState.update {
                    it.copy(
                        pendingSnippetSendId = null,
                        pendingSnippetTargetSessionId = null,
                        pendingSnippetSendsImmediately = null,
                        notice = uiText(
                            R.string.notice_snippet_sent,
                            UiText.Dynamic(snippet.label),
                        ),
                    )
                }
            }
            SnippetDispatchOutcome.REJECTED -> {
                _uiState.update {
                    it.copy(
                        pendingSnippetSendId = null,
                        pendingSnippetTargetSessionId = null,
                        pendingSnippetSendsImmediately = null,
                        notice = uiText(R.string.notice_snippet_queue_failed),
                    )
                }
            }
        }
        return outcome
    }

    fun sendBufferedInput(sessionId: Long, text: String): Boolean {
        val state = _uiState.value
        if (text.isEmpty()) return false
        val error = state.bufferedInputValidationError(sessionId, text)
        if (error != null) {
            _uiState.update { it.copy(notice = error) }
            return false
        }
        val targetController = if (sessionId == LOCAL_TERMINAL_SESSION_ID) {
            controller
        } else {
            remoteSessions.controllerFor(sessionId)
        }
        if (targetController == null) {
            _uiState.update {
                it.copy(notice = uiText(R.string.notice_terminal_unavailable_draft_kept))
            }
            return false
        }
        if (!targetController.sendPaste(text)) {
            _uiState.update { it.copy(notice = uiText(R.string.notice_buffer_queue_failed)) }
            return false
        }
        _uiState.update { it.copy(notice = null) }
        return true
    }

    /** Streams one IME image through the same ordered path used by multi-select and clipboard. */
    internal fun pasteImage(
        sessionId: Long,
        mimeType: String,
        open: () -> InputStream?,
        onFinished: () -> Unit = {},
    ): Boolean = pasteImages(
        sessionId = sessionId,
        images = listOf(TerminalImagePasteSource(mimeType, open, onFinished)),
    )

    /**
     * Uploads an ordered image batch over the authenticated SSH side channel and pastes each
     * resulting path in selection order. Codex turns those paths into multiple `[Image #N]`
     * attachments in its current input.
     */
    internal fun pasteImages(
        sessionId: Long,
        images: List<TerminalImagePasteSource>,
    ): Boolean {
        if (images.isEmpty()) return false
        val prepared = images.mapNotNull { image ->
            pastedImageExtension(image.mimeType)?.let { extension -> image to extension }
        }
        if (prepared.isEmpty()) {
            _uiState.update { it.copy(notice = uiText(R.string.notice_image_paste_format_unsupported)) }
            return false
        }
        val session = _uiState.value.sessions.firstOrNull { it.id == sessionId }
        if (session == null || session.isLocalTerminal ||
            session.connectionState !is ConnectionState.Connected
        ) {
            _uiState.update { it.copy(notice = uiText(R.string.notice_image_paste_requires_remote)) }
            return false
        }
        val unsupportedCount = images.size - prepared.size
        prepared.map { it.first }.toSet().let { accepted ->
            images.filterNot(accepted::contains).forEach { runCatching(it.onFinished) }
        }
        _uiState.update {
            it.copy(
                notice = if (prepared.size == 1) {
                    uiText(R.string.notice_image_paste_uploading)
                } else {
                    quantityText(
                        R.plurals.notice_image_paste_uploading_count,
                        prepared.size,
                        prepared.size,
                    )
                }
            )
        }
        viewModelScope.launch {
            imagePasteMutex.withLock {
                var insertedCount = 0
                var oversizedCount = 0
                prepared.forEach { (image, extension) ->
                    val result = try {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val input = requireNotNull(image.open()) {
                                    "The selected image is no longer available."
                                }
                                remoteSessions.uploadPastedImage(
                                    sessionId = sessionId,
                                    fileName = "${UUID.randomUUID()}.$extension",
                                    source = PastedImageSizeLimitInputStream(input),
                                ).getOrThrow()
                            }
                        }
                    } finally {
                        runCatching(image.onFinished)
                    }
                    if (result.exceptionOrNull().hasCause<PastedImageTooLargeException>()) {
                        oversizedCount += 1
                    }
                    val remotePath = result.getOrNull()
                    if (remotePath != null &&
                        controllerForExistingSession(sessionId)?.sendPaste(remotePath) == true
                    ) {
                        insertedCount += 1
                    }
                }
                _uiState.update {
                    it.copy(
                        notice = when {
                            insertedCount == images.size -> quantityText(
                                R.plurals.notice_image_paste_attached_count,
                                insertedCount,
                                insertedCount,
                            )
                            insertedCount > 0 -> uiText(
                                R.string.notice_image_paste_partial,
                                insertedCount,
                                images.size,
                            )
                            oversizedCount > 0 -> uiText(R.string.notice_image_paste_too_large)
                            unsupportedCount == images.size ->
                                uiText(R.string.notice_image_paste_format_unsupported)
                            else -> uiText(R.string.notice_image_paste_failed)
                        },
                    )
                }
            }
        }
        return true
    }

    fun setExtraKeyVisible(key: TerminalExtraKey, visible: Boolean) {
        if (!_uiState.value.settingsReady) return
        _uiState.update { state ->
            val current = state.extraKeys
            val updated = if (visible) {
                if (key in current) current else current + key
            } else {
                if (current.size == 1) {
                    return@update state.copy(notice = uiText(R.string.notice_keep_one_extra_key))
                }
                current - key
            }
            state.copy(extraKeys = updated, notice = null)
        }
    }

    fun replaceExtraKey(currentKey: TerminalExtraKey, replacementKey: TerminalExtraKey) {
        if (!_uiState.value.settingsReady || currentKey == replacementKey) return
        _uiState.update { state ->
            val index = state.extraKeys.indexOf(currentKey)
            if (index < 0 || replacementKey in state.extraKeys) return@update state
            val keys = state.extraKeys.toMutableList()
            keys[index] = replacementKey
            state.copy(extraKeys = keys, notice = null)
        }
    }

    fun moveExtraKey(key: TerminalExtraKey, direction: Int) {
        if (!_uiState.value.settingsReady || direction !in listOf(-1, 1)) return
        _uiState.update { state ->
            val from = state.extraKeys.indexOf(key)
            val to = from + direction
            if (from < 0 || to !in state.extraKeys.indices) return@update state
            val keys = state.extraKeys.toMutableList()
            val moved = keys[from]
            keys[from] = keys[to]
            keys[to] = moved
            state.copy(extraKeys = keys, notice = null)
        }
    }

    fun resetExtraKeys() {
        if (!_uiState.value.settingsReady) return
        _uiState.update { state ->
            state.copy(extraKeys = TerminalExtraKey.DEFAULT_ORDER, notice = null)
        }
    }

    fun saveExtraKeys() {
        val state = _uiState.value
        if (!state.settingsReady) return
        if (!state.keyboardRuntimeCompatible) {
            _uiState.update {
                it.copy(notice = uiText(R.string.notice_keyboard_profile_edit_full))
            }
            return
        }
        val keys = state.extraKeys.toList()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { terminalDataRepository.saveExtraKeys(keys) }
                .onSuccess { committed ->
                    publishCommittedSettings(committed, notice = uiText(R.string.notice_terminal_keys_saved))
                }
                .onFailure { error ->
                    reportCatalogWriteFailure(error, uiText(R.string.notice_terminal_keys_save_failed))
                }
        }
    }

    fun answerHostIdentityPrompt(
        sessionId: Long,
        promptToken: Long,
        decision: HostIdentityDecision,
    ) {
        remoteSessions.answerHostIdentityPrompt(sessionId, promptToken, decision)
    }

    fun answerKeyboardInteractiveChallenge(
        sessionId: Long,
        challengeToken: Long,
        responses: List<CharArray>,
    ) {
        remoteSessions.answerKeyboardInteractiveChallenge(
            sessionId = sessionId,
            challengeToken = challengeToken,
            responses = responses,
        )
    }

    fun cancelKeyboardInteractiveChallenge(sessionId: Long, challengeToken: Long) {
        remoteSessions.cancelKeyboardInteractiveChallenge(sessionId, challengeToken)
    }

    fun answerTmuxSessionPrompt(sessionId: Long, promptToken: Long, tmuxSessionId: String?) {
        remoteSessions.answerTmuxSessionPrompt(sessionId, promptToken, tmuxSessionId)
    }

    fun deleteTmuxSession(sessionId: Long, promptToken: Long, tmuxSessionId: String) {
        remoteSessions.deleteTmuxSession(sessionId, promptToken, tmuxSessionId)
    }

    fun disconnectSsh(sessionId: Long = _uiState.value.activeSessionId) {
        if (sessionId < 0) { localSessions.disconnect(sessionId); return }
        if (sessionId == LOCAL_TERMINAL_SESSION_ID) return
        pendingRemoteClipboardRequest
            ?.takeIf { it.sessionId == sessionId }
            ?.let(::discardRemoteClipboardRequest)
        remoteSessions.disconnect(sessionId)
    }

    fun closeSession(sessionId: Long) {
        if (sessionId < 0) { localSessions.close(sessionId); return }
        if (sessionId == LOCAL_TERMINAL_SESSION_ID) return
        pendingRemoteClipboardRequest
            ?.takeIf { it.sessionId == sessionId }
            ?.let(::discardRemoteClipboardRequest)
        remoteSessions.close(sessionId)
    }

    internal suspend fun queryActiveTmuxSessions(sessionId: Long): TmuxSessionCatalog =
        withContext(Dispatchers.IO) {
            runCatching { remoteSessions.queryTmuxSessionCatalog(sessionId) }
                .getOrElse { TmuxSessionCatalog() }
        }

    internal suspend fun queryActiveTmuxSessionPreviews(sessionId: Long): TmuxSessionCatalog =
        withContext(Dispatchers.IO) {
            runCatching {
                remoteSessions.queryTmuxSessionCatalog(sessionId, includePreviews = true)
            }.getOrElse { TmuxSessionCatalog() }
        }

    internal fun terminalSwitcherPreviewLines(sessionId: Long): List<String> =
        controllerForExistingSession(sessionId)?.previewLines().orEmpty()

    internal suspend fun terminateActiveTmuxSession(
        sessionId: Long,
        tmuxSessionId: String,
    ): TmuxSessionCatalog = withContext(Dispatchers.IO) {
        runCatching { remoteSessions.terminateTmuxSession(sessionId, tmuxSessionId) }
            .getOrElse { TmuxSessionCatalog(deleteFailed = true) }
    }

    internal suspend fun switchActiveTmuxSession(sessionId: Long, tmuxSessionId: String): Boolean {
        if (!tmuxSessionId.isTmuxSessionId()) return false
        val state = _uiState.value
        if (
            state.sessions.none { it.id == sessionId } ||
            state.sessions.first { it.id == sessionId }.connectionState !is ConnectionState.Connected
        ) {
            return false
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                remoteSessions.switchTmuxSession(sessionId, tmuxSessionId)
            }.getOrDefault(false)
        }
    }

    fun jumpToBottom() {
        val activeController = controllerFor(_uiState.value.activeSessionId)
        val wasAutoFollowing = activeController.viewport.autoFollow
        activeController.jumpToBottom()
        activeController.reportViewportStateIfChanged(wasAutoFollowing)
    }

    fun selectTerminalInputMode(mode: TerminalInputMode) {
        _uiState.update { it.copy(terminalInputMode = mode) }
    }

    /** Resolves and dispatches a non-local typed deck action against one current state snapshot. */
    fun activateTerminalAccessoryAction(action: TerminalAccessoryAction): Boolean {
        val state = _uiState.value
        val modifiers = AccessoryModifierSnapshot(
            control = accessoryModifierState(state.ctrlArmed, state.ctrlLocked),
            alt = accessoryModifierState(state.altArmed, state.altLocked),
            shift = accessoryModifierState(state.shiftArmed, state.shiftLocked),
        )
        return when (val dispatch = action.resolve(modifiers)) {
            is TerminalAccessoryDispatch.ToggleModifier -> {
                toggleTerminalAccessoryModifier(dispatch.modifier)
                true
            }
            is TerminalAccessoryDispatch.Bytes -> {
                val accepted = try {
                    controllerFor(state.activeSessionId).sendWithAcceptance(dispatch.value)
                } finally {
                    dispatch.value.fill(0)
                }
                if (accepted) {
                    if (!state.ctrlLocked) lastCtrlTapNanos = 0L
                    if (!state.altLocked) lastAltTapNanos = 0L
                    if (!state.shiftLocked) lastShiftTapNanos = 0L
                    _uiState.update { current -> current.afterAccessoryByteDispatch() }
                }
                accepted
            }
            is TerminalAccessoryDispatch.Unsupported -> {
                _uiState.update { it.copy(notice = dispatch.explanation) }
                false
            }
            is TerminalAccessoryDispatch.Local -> false
        }
    }

    private fun toggleTerminalAccessoryModifier(modifier: TerminalAccessoryModifier) {
        val nowNanos = System.nanoTime()
        _uiState.update { state ->
            when (modifier) {
                TerminalAccessoryModifier.CONTROL -> {
                    val activation = nextModifierActivation(
                        armed = state.ctrlArmed,
                        locked = state.ctrlLocked,
                        behavior = state.modifierBehavior,
                        lastTapNanos = lastCtrlTapNanos,
                        nowNanos = nowNanos,
                    )
                    lastCtrlTapNanos = activation.lastTapNanos
                    state.copy(ctrlArmed = activation.armed, ctrlLocked = activation.locked)
                }
                TerminalAccessoryModifier.ALT -> {
                    val activation = nextModifierActivation(
                        armed = state.altArmed,
                        locked = state.altLocked,
                        behavior = state.modifierBehavior,
                        lastTapNanos = lastAltTapNanos,
                        nowNanos = nowNanos,
                    )
                    lastAltTapNanos = activation.lastTapNanos
                    state.copy(altArmed = activation.armed, altLocked = activation.locked)
                }
                TerminalAccessoryModifier.SHIFT -> {
                    val activation = nextModifierActivation(
                        armed = state.shiftArmed,
                        locked = state.shiftLocked,
                        behavior = state.modifierBehavior,
                        lastTapNanos = lastShiftTapNanos,
                        nowNanos = nowNanos,
                    )
                    lastShiftTapNanos = activation.lastTapNanos
                    state.copy(shiftArmed = activation.armed, shiftLocked = activation.locked)
                }
            }
        }
    }

    fun activateExtraKey(key: TerminalExtraKey) {
        if (key == TerminalExtraKey.CTRL) {
            val nowNanos = System.nanoTime()
            _uiState.update { state ->
                val activation = nextModifierActivation(
                    armed = state.ctrlArmed,
                    locked = state.ctrlLocked,
                    behavior = state.modifierBehavior,
                    lastTapNanos = lastCtrlTapNanos,
                    nowNanos = nowNanos,
                )
                lastCtrlTapNanos = activation.lastTapNanos
                state.copy(ctrlArmed = activation.armed, ctrlLocked = activation.locked)
            }
            return
        }
        if (key == TerminalExtraKey.ALT) {
            val nowNanos = System.nanoTime()
            _uiState.update { state ->
                val activation = nextModifierActivation(
                    armed = state.altArmed,
                    locked = state.altLocked,
                    behavior = state.modifierBehavior,
                    lastTapNanos = lastAltTapNanos,
                    nowNanos = nowNanos,
                )
                lastAltTapNanos = activation.lastTapNanos
                state.copy(altArmed = activation.armed, altLocked = activation.locked)
            }
            return
        }
        val state = _uiState.value
        val keyBytes = key.bytes ?: return
        val base = if (state.ctrlArmed) key.controlBytes ?: keyBytes else keyBytes
        val outgoing = if (state.altArmed) TerminalKeySequences.ESCAPE + base else base
        val accepted = controllerFor(state.activeSessionId).sendWithAcceptance(outgoing)
        if (!accepted) return
        if (!state.ctrlLocked) lastCtrlTapNanos = 0L
        if (!state.altLocked) lastAltTapNanos = 0L
        _uiState.update { it.afterExtraKeyDispatch(accepted = true) }
    }

    fun onAppVisible(visible: Boolean) {
        terminalBuildFeature.onAppVisible(visible)
    }

    private suspend fun publishCommittedSettings(
        @Suppress("UNUSED_PARAMETER") committed: UserSettings,
        notice: UiText? = null,
    ) {
        // Hold one gate across both the authoritative re-read and Main publication. Mutation
        // coroutines can resume out of order; loading before this gate would still allow an older
        // snapshot to pause and overwrite a newer publication.
        catalogPublicationGate.publish { latest ->
            withContext(Dispatchers.Main.immediate) {
                val compatibility = latest.compatibility
                connectionsCatalogLoad.value = latest
                if (compatibility.failure != null) {
                    _uiState.update { state ->
                        state.afterSettingsLoad(
                            loaded = compatibility,
                            knownHosts = state.knownHosts,
                            retry = false,
                        ).copy(notice = uiText(R.string.notice_change_saved_refresh_failed))
                    }
                    return@withContext
                }
                val authoritative = compatibility.settings
                _uiState.update { state ->
                    state.copy(
                        profiles = authoritative.profiles,
                        snippets = authoritative.snippets,
                        identities = authoritative.identities,
                        extraKeys = authoritative.extraKeys,
                        keyboardRuntimeCompatible = authoritative.keyboardRuntimeCompatible,
                        settingsReady = true,
                        settingsRecoveryFailure = null,
                        settingsRecoveryInProgress = false,
                        notice = notice ?: compatibility.warning?.toSettingsWarningUiText(),
                    )
                }
            }
        }
    }

    private suspend fun acknowledgedCatalogWrite(
        successNotice: UiText,
        failureMessage: UiText,
        operation: suspend () -> UserSettings,
    ): Result<Unit> = try {
        val committed = withContext(Dispatchers.IO) { operation() }
        publishCommittedSettings(committed, successNotice)
        Result.success(Unit)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        reportCatalogWriteFailure(error, failureMessage)
        if (error is TerminalDataCommittedRefreshException) {
            Result.success(Unit)
        } else {
            Result.failure(error)
        }
    }

    private suspend fun reportCatalogWriteFailure(error: Throwable, message: UiText) {
        appLogger.warning(AppLogEvent.SETTINGS_WRITE_FAILED, error)
        if (error is TerminalDataCommittedRefreshException) {
            val reload = runCatching { loadLocalSettings(retry = false) }
            val refreshed = reload.isSuccess && _uiState.value.settingsReady
            withContext(Dispatchers.Main.immediate) {
                _uiState.update { state ->
                    state.copy(
                        notice = if (refreshed) {
                            uiText(R.string.notice_change_saved)
                        } else {
                            uiText(R.string.notice_change_saved_refresh_failed)
                        },
                    )
                }
            }
            return
        }
        withContext(Dispatchers.Main.immediate) {
            _uiState.update { state -> state.copy(notice = message) }
        }
    }

    override fun onCleared() {
        cancelLocalNetworkAction()
        sftp.close()
        cancelConnectionsHostTest()
        cancelTerminalTranscriptExport()
        pendingRemoteClipboardRequest?.let(::discardRemoteClipboardRequest)
        savedStateHandle.get<String>(PENDING_IDENTITY_IMPORT_URI)?.let(Uri::parse)?.let(
            ::releaseIdentityImportPermission,
        )
        terminalBuildFeature.stop()
        controller.stop()
        super.onCleared()
    }

    internal fun stageLocalNetworkAction(
        cancel: () -> Unit,
        proceed: () -> LocalNetworkActionEffect,
    ): Boolean = localNetworkPendingAction.stage(cancel = cancel, proceed = proceed)

    internal fun stageLocalNetworkHostnameAction(
        host: String,
        cancel: () -> Unit,
        proceed: () -> LocalNetworkActionEffect,
    ): Boolean {
        if (!localNetworkPendingAction.stage(cancel = cancel, proceed = proceed)) return false
        localNetworkResolutionJob = viewModelScope.launch {
            val resolvedLocal = withContext(Dispatchers.IO) {
                resolvedEndpointIsLocalNetwork(host) { endpoint ->
                    InetAddress.getAllByName(endpoint).mapNotNull(InetAddress::getHostAddress)
                }
            }
            if (!localNetworkPendingAction.hasPendingAction()) return@launch
            val id = nextLocalNetworkDispatchId++
            _localNetworkActionDispatch.value = if (resolvedLocal == true) {
                LocalNetworkActionDispatch.RequestPermission(id)
            } else {
                val effect = localNetworkPendingAction.resolve(granted = true) ?: return@launch
                LocalNetworkActionDispatch.Apply(id, effect)
            }
        }
        return true
    }

    internal fun consumeLocalNetworkActionDispatch(id: Long): LocalNetworkActionDispatch? {
        val dispatch = _localNetworkActionDispatch.value?.takeIf { it.id == id } ?: return null
        _localNetworkActionDispatch.value = null
        return dispatch
    }

    internal fun resolveLocalNetworkAction(granted: Boolean): LocalNetworkActionEffect? {
        _localNetworkActionDispatch.value = null
        localNetworkResolutionJob = null
        return localNetworkPendingAction.resolve(granted)
    }

    internal fun cancelLocalNetworkAction() {
        localNetworkResolutionJob?.cancel()
        localNetworkResolutionJob = null
        _localNetworkActionDispatch.value = null
        localNetworkPendingAction.cancel()
    }

    companion object {
        private const val KNOWN_HOSTS_FILE = "known_hosts"
        private const val DEFAULT_SSH_PORT = 22
        private const val MAX_SESSION_TITLE_LENGTH = 40
        private const val MAX_SECRET_LENGTH = 1_024
        private const val MAX_PRIVATE_KEY_BYTES = 256 * 1_024
        private const val WORKSPACE_RECENT_HISTORY_SCAN_LIMIT = 64
        private const val CONNECTIONS_RECENT_HOST_LIMIT = 64
        private const val ACTIVE_TERMINAL_SESSION_ID = "active_terminal_session_id"
        private const val LAST_ACTIVE_REMOTE_SESSION_ID = "last_active_remote_session_id"
        private const val PENDING_IDENTITY_IMPORT_URI = "pending_identity_import_uri"
        private const val PENDING_IDENTITY_RECOVERY_TOKEN = "pending_identity_recovery_token"
    }
}

internal fun newConnectionFailureNotice(
    current: ConnectionState,
    previous: ConnectionState?,
): UiText? = (current as? ConnectionState.Failed)
    ?.takeIf { previous !is ConnectionState.Failed }
    ?.let { failure -> UiText.Dynamic(failure.message) }

internal data class ModifierActivation(
    val armed: Boolean,
    val locked: Boolean,
    val lastTapNanos: Long,
)

internal fun TerminalSpikeUiState.afterExtraKeyDispatch(accepted: Boolean): TerminalSpikeUiState =
    if (!accepted) {
        this
    } else {
        copy(ctrlArmed = ctrlLocked, altArmed = altLocked)
    }

internal fun TerminalSpikeUiState.afterAccessoryByteDispatch(): TerminalSpikeUiState = copy(
    ctrlArmed = ctrlLocked,
    altArmed = altLocked,
    shiftArmed = shiftLocked,
)

internal fun nextModifierActivation(
    armed: Boolean,
    locked: Boolean,
    behavior: ModifierBehavior,
    lastTapNanos: Long,
    nowNanos: Long,
): ModifierActivation {
    if (behavior == ModifierBehavior.ONE_SHOT) {
        return ModifierActivation(armed = !armed, locked = false, lastTapNanos = 0L)
    }
    if (locked || armed && nowNanos - lastTapNanos !in 1..MODIFIER_DOUBLE_TAP_NANOS) {
        return ModifierActivation(armed = false, locked = false, lastTapNanos = 0L)
    }
    if (armed) {
        return ModifierActivation(armed = true, locked = true, lastTapNanos = 0L)
    }
    return ModifierActivation(armed = true, locked = false, lastTapNanos = nowNanos)
}

private const val MODIFIER_DOUBLE_TAP_NANOS = 350_000_000L

private fun ConnectionState.toPersistedSessionState(): SessionState = when (this) {
    ConnectionState.Connecting, is ConnectionState.AwaitingApproval -> SessionState.CONNECTING
    ConnectionState.Connected -> SessionState.CONNECTED
    is ConnectionState.Reconnecting -> SessionState.RECONNECTING
    ConnectionState.Disconnected -> SessionState.DISCONNECTED
    is ConnectionState.Failed -> SessionState.FAILED
}

internal fun buildRecentSessionRecord(
    id: String,
    hostProfileId: String?,
    hostDisplayName: String,
    protocol: ConnectionProtocol,
    connectionState: ConnectionState,
    startedAtEpochMillis: Long,
    lastActivityAtEpochMillis: Long,
    rawTerminalTitle: String?,
    sensitiveValues: Iterable<String> = emptyList(),
): RecentSession {
    val sessionState = connectionState.toPersistedSessionState()
    return RecentSession(
        id = id,
        hostProfileId = hostProfileId,
        hostDisplayName = hostDisplayName,
        protocol = protocol,
        state = sessionState,
        startedAtEpochMillis = startedAtEpochMillis,
        lastActivityAtEpochMillis = lastActivityAtEpochMillis,
        endedAtEpochMillis = if (sessionState.isActive) null else lastActivityAtEpochMillis,
        terminalTitle = sanitizeWorkspaceTerminalTitle(rawTerminalTitle, sensitiveValues),
    )
}

internal fun InputStream.readSensitiveBounded(
    maximumBytes: Int,
    onBufferWiped: (ByteArray) -> Unit = {},
): ByteArray {
    require(maximumBytes > 0) { "Maximum sensitive input size must be positive." }
    val output = SensitiveByteAccumulator(
        initialCapacity = minOf(maximumBytes, 16 * 1024),
        maximumCapacity = maximumBytes,
        onBufferWiped = onBufferWiped,
    )
    val scratch = ByteArray(minOf(maximumBytes, 8 * 1024))
    try {
        while (true) {
            val count = read(scratch)
            if (count < 0) break
            if (count == 0) continue
            require(output.size <= maximumBytes - count) { "Selected file is too large." }
            output.append(scratch, count)
        }
        return output.copyValue()
    } finally {
        scratch.fill(0)
        onBufferWiped(scratch)
        output.close()
    }
}

private inline fun <reified T : Throwable> Throwable?.hasCause(): Boolean {
    var current = this
    val visited = mutableSetOf<Throwable>()
    while (current != null && visited.add(current)) {
        if (current is T) return true
        current = current.cause
    }
    return false
}

private class SensitiveByteAccumulator(
    initialCapacity: Int,
    private val maximumCapacity: Int,
    private val onBufferWiped: (ByteArray) -> Unit,
) {
    private var storage = ByteArray(initialCapacity)
    var size: Int = 0
        private set

    fun append(source: ByteArray, count: Int) {
        require(count in 0..source.size)
        ensureCapacity(size + count)
        source.copyInto(storage, destinationOffset = size, endIndex = count)
        size += count
    }

    fun copyValue(): ByteArray = storage.copyOf(size)

    fun close() {
        wipe(storage)
        size = 0
    }

    private fun ensureCapacity(required: Int) {
        if (required <= storage.size) return
        require(required <= maximumCapacity) { "Selected file is too large." }
        var capacity = storage.size.coerceAtLeast(1)
        while (capacity < required) {
            capacity = minOf(maximumCapacity, maxOf(required, capacity * 2))
        }
        val replacement = ByteArray(capacity)
        storage.copyInto(replacement, endIndex = size)
        wipe(storage)
        storage = replacement
    }

    private fun wipe(buffer: ByteArray) {
        buffer.fill(0)
        onBufferWiped(buffer)
    }
}
