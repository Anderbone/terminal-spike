package com.yanjiyu.terminalspike.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.Connection
import com.yanjiyu.terminalspike.connection.JschSshConnection
import com.yanjiyu.terminalspike.connection.KnownHostManager
import com.yanjiyu.terminalspike.connection.KnownHostSummary
import com.yanjiyu.terminalspike.connection.SshConnectionConfig
import com.yanjiyu.terminalspike.connection.SshAuthentication
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.SavedSshIdentity
import com.yanjiyu.terminalspike.settings.SecureSshIdentityStore
import com.yanjiyu.terminalspike.settings.SecureSshPasswordStore
import com.yanjiyu.terminalspike.settings.SecureUserSettingsStore
import com.yanjiyu.terminalspike.settings.SshPasswordScope
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.engine.VtTerminalEngine
import com.yanjiyu.terminalspike.terminal.model.TerminalBuffer
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import com.yanjiyu.terminalspike.terminal.view.TerminalKeySequences
import com.yanjiyu.terminalspike.workload.FullScreenRate
import com.yanjiyu.terminalspike.workload.FullScreenWorkload
import com.yanjiyu.terminalspike.workload.PreloadSize
import com.yanjiyu.terminalspike.workload.StreamingRate
import com.yanjiyu.terminalspike.workload.StreamingWorkload
import com.yanjiyu.terminalspike.workload.WorkloadGenerator
import com.yanjiyu.terminalspike.workload.WorkloadMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.io.ByteArrayOutputStream
import java.io.InputStream

private const val BENCHMARK_SESSION_ID = 0L

data class SessionTabUi(
    val id: Long,
    val title: String,
    val connectionState: ConnectionState,
    val isBenchmark: Boolean = false,
)

data class TerminalSpikeUiState(
    val mode: WorkloadMode = WorkloadMode.STREAM,
    val streamingRate: StreamingRate = StreamingRate.LINES_100,
    val fullScreenRate: FullScreenRate = FullScreenRate.FPS_30,
    val preloadSize: PreloadSize = PreloadSize.LINES_10K,
    val isRunning: Boolean = false,
    val isPreloading: Boolean = false,
    val showPerformance: Boolean = true,
    val ctrlArmed: Boolean = false,
    val altArmed: Boolean = false,
    val sessions: List<SessionTabUi> = listOf(
        SessionTabUi(
            id = BENCHMARK_SESSION_ID,
            title = "Bench",
            connectionState = ConnectionState.Disconnected,
            isBenchmark = true,
        ),
    ),
    val activeSessionId: Long = BENCHMARK_SESSION_ID,
    val notice: String? = null,
    val profiles: List<SavedSshProfile> = emptyList(),
    val snippets: List<CommandSnippet> = emptyList(),
    val identities: List<SavedSshIdentity> = emptyList(),
    val knownHosts: List<KnownHostSummary> = emptyList(),
    val extraKeys: List<TerminalExtraKey> = TerminalExtraKey.DEFAULT_ORDER,
    val settingsReady: Boolean = false,
) {
    val activeSession: SessionTabUi
        get() = sessions.firstOrNull { it.id == activeSessionId } ?: sessions.first()
    val sshMode: Boolean get() = !activeSession.isBenchmark
    val connectionState: ConnectionState get() = activeSession.connectionState
    val canAddSshSession: Boolean get() = sessions.count { !it.isBenchmark } < 4
}

class TerminalSpikeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    val controller = TerminalController()
    private val _uiState = MutableStateFlow(TerminalSpikeUiState())
    val uiState: StateFlow<TerminalSpikeUiState> = _uiState.asStateFlow()
    private val generator = WorkloadGenerator()
    private val streamingWorkload = StreamingWorkload(generator)
    private val fullScreenWorkload = FullScreenWorkload(generator)
    private var workloadJob: Job? = null
    private var preloadJob: Job? = null
    private val sshSessions = ConcurrentHashMap<Long, SshSessionRuntime>()
    private val nextSessionId = AtomicLong(1L)
    private val settingsStore = SecureUserSettingsStore(application)
    private val identityStore = SecureSshIdentityStore(application)
    private val passwordStore = SecureSshPasswordStore(application)
    private val knownHostManager = KnownHostManager { application.filesDir.resolve(KNOWN_HOSTS_FILE) }
    private val settingsWrites = Channel<UserSettings>(Channel.CONFLATED)
    private var nextSettingsId = 1L
    private var appVisible = true

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = settingsStore.load()
            val knownHosts = knownHostManager.list()
            val maximumId = (loaded.settings.profiles.map { it.id } + loaded.settings.snippets.map { it.id })
                .maxOrNull() ?: 0L
            withContext(Dispatchers.Main.immediate) {
                nextSettingsId = maximumId + 1L
                _uiState.update {
                    it.copy(
                        profiles = loaded.settings.profiles,
                        snippets = loaded.settings.snippets,
                        identities = loaded.settings.identities,
                        knownHosts = knownHosts,
                        extraKeys = loaded.settings.extraKeys,
                        settingsReady = true,
                        notice = loaded.warning,
                    )
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            for (settings in settingsWrites) {
                runCatching { settingsStore.save(settings) }
                    .onFailure {
                        _uiState.update { state ->
                            state.copy(notice = "Could not save encrypted local tools.")
                        }
                    }
            }
        }
    }

    fun controllerFor(sessionId: Long): TerminalController =
        sshSessions[sessionId]?.controller ?: controller

    fun connectSsh(
        host: String,
        portText: String,
        username: String,
        password: String,
        identityId: Long? = null,
        passphrase: String = "",
        saveProfile: Boolean = false,
        savedPasswordProfileId: Long? = null,
        savePassword: Boolean = false,
    ) {
        val normalizedHost = host.trim().removeSurrounding("[", "]")
        val normalizedUsername = username.trim()
        val port = portText.toIntOrNull()
        val matchingProfile = _uiState.value.profiles.firstOrNull { profile ->
            profile.host.equals(normalizedHost, ignoreCase = true) && profile.port == port &&
                profile.username == normalizedUsername
        }
        val savedPasswordProfile = _uiState.value.profiles.firstOrNull { profile ->
            profile.id == savedPasswordProfileId && profile.hasSavedPassword &&
                profile.host.equals(normalizedHost, ignoreCase = true) && profile.port == port &&
                profile.username == normalizedUsername
        }
        val error = when {
            normalizedHost.isBlank() || normalizedHost.length > UserSettings.MAX_HOST_LENGTH ||
                normalizedHost.any(Char::isWhitespace) -> "Enter a valid host."
            port == null || port !in 1..65_535 -> "Port must be between 1 and 65535."
            username.isBlank() || username.length > UserSettings.MAX_USERNAME_LENGTH ||
                username.any { it.isWhitespace() || it.isISOControl() } -> "Enter a valid username."
            identityId == null && password.isEmpty() && savedPasswordProfile == null ->
                "Enter a password, use a saved password, or choose a private key."
            password.length > MAX_SECRET_LENGTH -> "Password is too long."
            passphrase.length > MAX_SECRET_LENGTH -> "Passphrase is too long."
            identityId != null && _uiState.value.identities.none { it.id == identityId } ->
                "Choose an available private key."
            savePassword && (identityId != null || password.isEmpty() || !saveProfile) ->
                "Enter a password and save the host before saving its password."
            saveProfile && matchingProfile == null && _uiState.value.profiles.size >= UserSettings.MAX_PROFILES ->
                "Delete a profile before saving another."
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(notice = error) }
            return
        }
        if (_uiState.value.sessions.count { !it.isBenchmark } >= MAX_SSH_SESSIONS) {
            _uiState.update { it.copy(notice = "Close a session before opening another.") }
            return
        }
        val validatedPort = port!!

        if (saveProfile) {
            saveSshProfile(
                label = buildSessionTitle(username.trim(), normalizedHost, validatedPort),
                host = normalizedHost,
                portText = validatedPort.toString(),
                username = normalizedUsername,
            )
        }

        val profileForPassword = _uiState.value.profiles.firstOrNull { profile ->
            profile.host.equals(normalizedHost, ignoreCase = true) && profile.port == validatedPort &&
                profile.username == normalizedUsername
        }
        if (savePassword && profileForPassword != null) {
            persistPassword(profileForPassword, password.toByteArray(Charsets.UTF_8))
        }

        stopJobs()
        controller.stop()
        val sessionId = nextSessionId.getAndIncrement()
        val sessionController = TerminalController(TerminalBuffer(SSH_SCROLLBACK_LINES))
        val selectedIdentity = _uiState.value.identities.firstOrNull { it.id == identityId }
        val authentication = if (selectedIdentity != null) {
            SshAuthentication.PrivateKey(
                identityName = selectedIdentity.label,
                loadKey = { identityStore.load(selectedIdentity.id) },
                passphrase = passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8),
            )
        } else if (password.isNotEmpty()) {
            SshAuthentication.Password(password.toByteArray(Charsets.UTF_8))
        } else {
            val profile = requireNotNull(savedPasswordProfile)
            val scope = profile.passwordScope()
            SshAuthentication.StoredPassword { passwordStore.load(scope) }
        }
        val connection = JschSshConnection(
            knownHostsFile = { getApplication<Application>().filesDir.resolve(KNOWN_HOSTS_FILE) },
            config = SshConnectionConfig(
                host = normalizedHost,
                port = validatedPort,
                username = normalizedUsername,
                authentication = authentication,
            ),
        )
        val runtime = SshSessionRuntime(
            id = sessionId,
            controller = sessionController,
            engine = VtTerminalEngine(
                columns = sessionController.terminalColumns,
                rows = sessionController.terminalRows,
            ),
            connection = connection,
        )
        sshSessions[sessionId] = runtime
        sessionController.setInputSink(connection) { columns, rows ->
            connection.resize(columns, rows)
            sessionController.updateTerminalFrame(runtime.engine.resize(columns, rows))
        }
        val title = buildSessionTitle(normalizedUsername, normalizedHost, validatedPort)
        _uiState.update {
            it.copy(
                isRunning = false,
                isPreloading = false,
                sessions = it.sessions + SessionTabUi(
                    id = sessionId,
                    title = title,
                    connectionState = ConnectionState.Connecting,
                ),
                activeSessionId = sessionId,
                notice = null,
            )
        }

        runtime.job = viewModelScope.launch(Dispatchers.IO) {
            connection.connect(
                columns = sessionController.terminalColumns,
                rows = sessionController.terminalRows,
                onBytes = { bytes ->
                    if (sshSessions[sessionId] === runtime) {
                        val update = runtime.engine.accept(bytes)
                        update.responses.forEach(connection::send)
                        sessionController.updateTerminalFrame(update)
                    }
                },
                onState = { state ->
                    if (sshSessions[sessionId] === runtime) {
                        updateSessionState(sessionId, state)
                    }
                },
            )
        }
    }

    fun selectSession(sessionId: Long) {
        if (_uiState.value.sessions.none { it.id == sessionId }) return
        _uiState.update { it.copy(activeSessionId = sessionId, notice = null) }
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
        val error = when {
            normalizedLabel.isBlank() || normalizedLabel.length > UserSettings.MAX_LABEL_LENGTH ->
                "Profile name must be 1–${UserSettings.MAX_LABEL_LENGTH} characters."
            normalizedLabel.any(Char::isISOControl) -> "Profile name contains unsupported characters."
            normalizedHost.isBlank() || normalizedHost.length > UserSettings.MAX_HOST_LENGTH ||
                normalizedHost.any(Char::isWhitespace) -> "Enter a valid host."
            port == null || port !in 1..65_535 -> "Port must be between 1 and 65535."
            normalizedUsername.isBlank() || normalizedUsername.length > UserSettings.MAX_USERNAME_LENGTH ||
                normalizedUsername.any { it.isWhitespace() || it.isISOControl() } -> "Enter a valid username."
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(notice = error) }
            return
        }
        val candidateId = nextLocalId()
        var credentialToDelete: Long? = null
        _uiState.update { state ->
            val match = state.profiles.firstOrNull { it.id == existingId } ?: state.profiles.firstOrNull {
                it.host.equals(normalizedHost, ignoreCase = true) && it.port == port &&
                    it.username == normalizedUsername
            }
            if (match == null && state.profiles.size >= UserSettings.MAX_PROFILES) {
                return@update state.copy(notice = "Delete a profile before adding another.")
            }
            val sameCredentialScope = match != null &&
                match.host.equals(normalizedHost, ignoreCase = true) && match.port == port &&
                match.username == normalizedUsername
            if (match?.hasSavedPassword == true && !sameCredentialScope) credentialToDelete = match.id
            val profile = SavedSshProfile(
                id = match?.id ?: candidateId,
                label = normalizedLabel,
                host = normalizedHost,
                port = port!!,
                username = normalizedUsername,
                hasSavedPassword = match?.hasSavedPassword == true && sameCredentialScope,
            )
            val profiles = state.profiles.filterNot { it.id == existingId || it.id == match?.id } + profile
            state.copy(profiles = profiles.sortedBy { it.label.lowercase() }, notice = null)
        }
        queueSettings(_uiState.value)
        credentialToDelete?.let(::deletePasswordFile)
    }

    fun deleteSshProfile(id: Long) {
        if (!_uiState.value.settingsReady) return
        _uiState.update { state ->
            state.copy(profiles = state.profiles.filterNot { it.id == id }, notice = null)
        }
        queueSettings(_uiState.value)
        deletePasswordFile(id)
    }

    fun forgetSavedPassword(profileId: Long) {
        if (!_uiState.value.settingsReady) return
        deletePasswordFile(profileId, updateProfile = true)
    }

    fun importSshIdentity(uri: Uri) {
        if (!_uiState.value.settingsReady) return
        if (_uiState.value.identities.size >= UserSettings.MAX_IDENTITIES) {
            _uiState.update { it.copy(notice = "Delete a private key before importing another.") }
            return
        }
        val identityId = nextLocalId()
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
                    ?: "Imported key"
                privateKey = resolver.openInputStream(uri)?.use { input ->
                    input.readBounded(SecureSshIdentityStore.MAX_PRIVATE_KEY_BYTES)
                } ?: error("Could not open selected key.")
                val inspection = identityStore.import(identityId, privateKey)
                withContext(Dispatchers.Main.immediate) {
                    _uiState.update { state ->
                        state.copy(
                            identities = (state.identities + SavedSshIdentity(
                                id = identityId,
                                label = displayName,
                                keyType = inspection.keyType,
                                fingerprint = inspection.fingerprint,
                                passphraseRequired = inspection.passphraseRequired,
                            )).sortedBy { it.label.lowercase() },
                            notice = "Imported $displayName.",
                        )
                    }
                    queueSettings(_uiState.value)
                }
            } catch (_: Exception) {
                identityStore.delete(identityId)
                _uiState.update { state ->
                    state.copy(notice = "Could not import that SSH private key.")
                }
            } finally {
                privateKey?.fill(0)
            }
        }
    }

    fun deleteSshIdentity(id: Long) {
        if (!_uiState.value.settingsReady || _uiState.value.identities.none { it.id == id }) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { identityStore.delete(id) }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            identities = state.identities.filterNot { it.id == id },
                            notice = "Private key removed from this device.",
                        )
                    }
                    queueSettings(_uiState.value)
                }
                .onFailure {
                    _uiState.update { state -> state.copy(notice = "Could not remove the private key.") }
                }
        }
    }

    fun forgetKnownHost(host: String, algorithm: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { knownHostManager.remove(host, algorithm) }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            knownHosts = knownHostManager.list(),
                            notice = "Forgot trusted key for $host.",
                        )
                    }
                }
                .onFailure {
                    _uiState.update { state -> state.copy(notice = "Could not update trusted hosts.") }
                }
        }
    }

    fun saveSnippet(label: String, command: String, appendEnter: Boolean, existingId: Long? = null) {
        if (!_uiState.value.settingsReady) return
        val normalizedLabel = label.trim()
        val error = when {
            normalizedLabel.isBlank() || normalizedLabel.length > UserSettings.MAX_LABEL_LENGTH ->
                "Snippet name must be 1–${UserSettings.MAX_LABEL_LENGTH} characters."
            normalizedLabel.any(Char::isISOControl) -> "Snippet name contains unsupported characters."
            command.isBlank() || command.length > UserSettings.MAX_SNIPPET_LENGTH ->
                "Snippet command must be 1–${UserSettings.MAX_SNIPPET_LENGTH} characters."
            command.any { it.isISOControl() && it !in "\r\n\t" } ->
                "Snippet contains unsupported control characters."
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(notice = error) }
            return
        }
        val candidateId = nextLocalId()
        _uiState.update { state ->
            if (existingId == null && state.snippets.size >= UserSettings.MAX_SNIPPETS) {
                return@update state.copy(notice = "Delete a snippet before adding another.")
            }
            val snippet = CommandSnippet(
                id = existingId ?: candidateId,
                label = normalizedLabel,
                command = command,
                appendEnter = appendEnter,
            )
            state.copy(
                snippets = (state.snippets.filterNot { it.id == existingId } + snippet)
                    .sortedBy { it.label.lowercase() },
                notice = null,
            )
        }
        queueSettings(_uiState.value)
    }

    fun deleteSnippet(id: Long) {
        if (!_uiState.value.settingsReady) return
        _uiState.update { state ->
            state.copy(snippets = state.snippets.filterNot { it.id == id }, notice = null)
        }
        queueSettings(_uiState.value)
    }

    fun sendSnippet(id: Long) {
        val state = _uiState.value
        val snippet = state.snippets.firstOrNull { it.id == id } ?: return
        if (state.activeSession.connectionState !is ConnectionState.Connected) {
            _uiState.update { it.copy(notice = "Connect an SSH session before sending a snippet.") }
            return
        }
        val activeController = controllerFor(state.activeSessionId)
        activeController.sendPaste(snippet.command)
        if (snippet.appendEnter) activeController.send(TerminalKeySequences.ENTER)
        _uiState.update { it.copy(notice = "Sent ${snippet.label}.") }
    }

    fun setExtraKeyVisible(key: TerminalExtraKey, visible: Boolean) {
        if (!_uiState.value.settingsReady) return
        _uiState.update { state ->
            val current = state.extraKeys
            val updated = if (visible) {
                if (key in current) current else current + key
            } else {
                if (current.size == 1) return@update state.copy(notice = "Keep at least one extra key visible.")
                current - key
            }
            state.copy(extraKeys = updated, notice = null)
        }
        queueSettings(_uiState.value)
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
        queueSettings(_uiState.value)
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
        queueSettings(_uiState.value)
    }

    fun resetExtraKeys() {
        if (!_uiState.value.settingsReady) return
        _uiState.update { state ->
            state.copy(extraKeys = TerminalExtraKey.DEFAULT_ORDER, notice = null)
        }
        queueSettings(_uiState.value)
    }

    fun saveExtraKeys() {
        val state = _uiState.value
        if (!state.settingsReady) return
        queueSettings(state)
        _uiState.update { it.copy(notice = "Terminal keys saved.") }
    }

    fun answerHostKeyPrompt(sessionId: Long, accept: Boolean) {
        sshSessions[sessionId]?.connection?.answerPrompt(accept)
    }

    fun disconnectSsh(sessionId: Long = _uiState.value.activeSessionId) {
        val runtime = sshSessions[sessionId] ?: return
        runtime.connection.answerPrompt(false)
        runtime.connection.close()
        runtime.job?.cancel()
        runtime.job = null
        updateSessionState(sessionId, ConnectionState.Disconnected)
    }

    fun closeSession(sessionId: Long) {
        if (sessionId == BENCHMARK_SESSION_ID) return
        val runtime = sshSessions.remove(sessionId) ?: return
        runtime.connection.answerPrompt(false)
        runtime.connection.close()
        runtime.job?.cancel()
        runtime.controller.stop()
        runtime.controller.clear()
        _uiState.update { state ->
            val remaining = state.sessions.filterNot { it.id == sessionId }
            val active = if (state.activeSessionId == sessionId) {
                remaining.lastOrNull { !it.isBenchmark }?.id ?: BENCHMARK_SESSION_ID
            } else {
                state.activeSessionId
            }
            state.copy(sessions = remaining, activeSessionId = active, notice = null)
        }
    }

    fun selectMode(mode: WorkloadMode) {
        if (_uiState.value.mode == mode) return
        val restart = _uiState.value.isRunning
        stopJobs()
        controller.stop()
        controller.setAlternateScreen(mode == WorkloadMode.FULL_SCREEN)
        _uiState.update { it.copy(mode = mode, isRunning = false, isPreloading = false) }
        if (restart) start()
    }

    fun selectStreamingRate(rate: StreamingRate) {
        _uiState.update { it.copy(streamingRate = rate) }
        if (rate == StreamingRate.STOPPED) {
            stop()
        } else if (_uiState.value.isRunning) {
            restartWorkload()
        }
    }

    fun selectFullScreenRate(rate: FullScreenRate) {
        _uiState.update { it.copy(fullScreenRate = rate) }
        if (_uiState.value.isRunning) restartWorkload()
    }

    fun selectPreloadSize(size: PreloadSize) {
        _uiState.update { it.copy(preloadSize = size) }
    }

    fun start() {
        val state = _uiState.value
        if (state.mode == WorkloadMode.STREAM && state.streamingRate == StreamingRate.STOPPED) return
        preloadJob?.cancel()
        controller.setAlternateScreen(state.mode == WorkloadMode.FULL_SCREEN)
        controller.start()
        _uiState.update { it.copy(isRunning = true, isPreloading = false) }
        updateWorkloadDescription()
        if (appVisible) launchWorkload()
    }

    fun stop() {
        stopJobs()
        controller.stop()
        _uiState.update { it.copy(isRunning = false, isPreloading = false) }
        controller.setWorkloadDescription(_uiState.value.mode.label, "Stopped")
    }

    fun clear() {
        preloadJob?.cancel()
        _uiState.update { it.copy(isPreloading = false) }
        controller.clear()
    }

    fun jumpToBottom() = controller.jumpToBottom()

    fun setPerformanceVisible(visible: Boolean) {
        _uiState.update { it.copy(showPerformance = visible) }
    }

    fun preload() {
        val lineCount = _uiState.value.preloadSize.lineCount
        stopJobs()
        controller.stop()
        controller.setAlternateScreen(false)
        controller.start()
        _uiState.update {
            it.copy(mode = WorkloadMode.STREAM, isRunning = false, isPreloading = true)
        }
        controller.setWorkloadDescription("Stream preload", "$lineCount lines")
        preloadJob = viewModelScope.launch {
            withContext(Dispatchers.Default) {
                var generated = 0
                while (generated < lineCount) {
                    val count = minOf(PRELOAD_BATCH_SIZE, lineCount - generated)
                    val batch = List(count) { offset ->
                        generator.streamingLine((generated + offset).toLong())
                    }
                    while (controller.pendingLineCount() > PRELOAD_BACKPRESSURE_LINES) {
                        delay(PRELOAD_BATCH_DELAY_MS)
                    }
                    controller.enqueueGeneratedLines(batch)
                    generated += count
                    delay(PRELOAD_BATCH_DELAY_MS)
                }
                while (controller.pendingLineCount() > 0) delay(PRELOAD_BATCH_DELAY_MS)
            }
            controller.stop()
            _uiState.update { it.copy(isPreloading = false) }
            controller.setWorkloadDescription("Stream", "Preload complete")
        }
    }

    fun activateExtraKey(key: TerminalExtraKey) {
        if (key == TerminalExtraKey.CTRL) {
            _uiState.update { it.copy(ctrlArmed = !it.ctrlArmed) }
            return
        }
        if (key == TerminalExtraKey.ALT) {
            _uiState.update { it.copy(altArmed = !it.altArmed) }
            return
        }
        val state = _uiState.value
        val keyBytes = key.bytes ?: return
        val base = if (state.ctrlArmed) key.controlBytes ?: keyBytes else keyBytes
        val outgoing = if (state.altArmed) TerminalKeySequences.ESCAPE + base else base
        controllerFor(state.activeSessionId).send(outgoing)
        _uiState.update { it.copy(ctrlArmed = false, altArmed = false) }
    }

    fun onAppVisible(visible: Boolean) {
        if (appVisible == visible) return
        appVisible = visible
        if (!visible) {
            workloadJob?.cancel()
            workloadJob = null
            controller.pause()
        } else if (_uiState.value.isRunning) {
            controller.resume()
            launchWorkload()
        }
    }

    private fun restartWorkload() {
        workloadJob?.cancel()
        workloadJob = null
        controller.stop()
        controller.start()
        updateWorkloadDescription()
        if (appVisible) launchWorkload()
    }

    private fun launchWorkload() {
        workloadJob?.cancel()
        val state = _uiState.value
        workloadJob = viewModelScope.launch(Dispatchers.Default) {
            when (state.mode) {
                WorkloadMode.STREAM -> streamingWorkload.run(state.streamingRate, controller::enqueueGeneratedLines)
                WorkloadMode.FULL_SCREEN -> fullScreenWorkload.run(state.fullScreenRate) { screen ->
                    controller.enqueueGeneratedScreen(screen.lines, screen.cursor)
                }
            }
        }
    }

    private fun updateWorkloadDescription() {
        val state = _uiState.value
        val rate = when (state.mode) {
            WorkloadMode.STREAM -> state.streamingRate.label
            WorkloadMode.FULL_SCREEN -> state.fullScreenRate.label
        }
        controller.setWorkloadDescription(state.mode.label, rate)
    }

    private fun stopJobs() {
        workloadJob?.cancel()
        workloadJob = null
        preloadJob?.cancel()
        preloadJob = null
    }

    private fun nextLocalId(): Long = nextSettingsId++

    private fun queueSettings(state: TerminalSpikeUiState) {
        if (!state.settingsReady) return
        settingsWrites.trySend(
            UserSettings(
                profiles = state.profiles,
                snippets = state.snippets,
                extraKeys = state.extraKeys,
                identities = state.identities,
            ),
        )
    }

    private fun persistPassword(profile: SavedSshProfile, password: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                passwordStore.save(profile.passwordScope(), password)
                _uiState.update { state ->
                    state.copy(
                        profiles = state.profiles.map { saved ->
                            if (saved.id == profile.id) saved.copy(hasSavedPassword = true) else saved
                        },
                        notice = "Password saved securely on this device.",
                    )
                }
                queueSettings(_uiState.value)
            } catch (_: Exception) {
                runCatching { passwordStore.delete(profile.id) }
                _uiState.update { state ->
                    state.copy(notice = "Could not securely save that password.")
                }
            } finally {
                password.fill(0)
            }
        }
    }

    private fun deletePasswordFile(profileId: Long, updateProfile: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { passwordStore.delete(profileId) }
                .onSuccess {
                    if (updateProfile) {
                        _uiState.update { state ->
                            state.copy(
                                profiles = state.profiles.map { profile ->
                                    if (profile.id == profileId) profile.copy(hasSavedPassword = false) else profile
                                },
                                notice = "Saved password removed from this device.",
                            )
                        }
                        queueSettings(_uiState.value)
                    }
                }
                .onFailure {
                    if (updateProfile) {
                        _uiState.update { state ->
                            state.copy(notice = "Could not remove the saved password.")
                        }
                    }
                }
        }
    }

    override fun onCleared() {
        stopJobs()
        sshSessions.values.forEach { runtime ->
            runtime.connection.answerPrompt(false)
            runtime.connection.close()
            runtime.job?.cancel()
        }
        sshSessions.clear()
        controller.stop()
        super.onCleared()
    }

    private fun updateSessionState(sessionId: Long, connectionState: ConnectionState) {
        _uiState.update { state ->
            state.copy(
                sessions = state.sessions.map { tab ->
                    if (tab.id == sessionId) tab.copy(connectionState = connectionState) else tab
                },
                activeSessionId = if (connectionState is ConnectionState.AwaitingApproval) {
                    sessionId
                } else {
                    state.activeSessionId
                },
            )
        }
        if (connectionState is ConnectionState.Connected) {
            viewModelScope.launch(Dispatchers.IO) {
                _uiState.update { it.copy(knownHosts = knownHostManager.list()) }
            }
        }
    }

    private fun buildSessionTitle(username: String, host: String, port: Int): String {
        val base = "$username@$host"
        return (if (port == DEFAULT_SSH_PORT) base else "$base:$port").take(MAX_SESSION_TITLE_LENGTH)
    }

    companion object {
        private const val KNOWN_HOSTS_FILE = "known_hosts"
        private const val DEFAULT_SSH_PORT = 22
        private const val MAX_SSH_SESSIONS = 4
        private const val MAX_SESSION_TITLE_LENGTH = 40
        private const val MAX_SECRET_LENGTH = 1_024
        private const val SSH_SCROLLBACK_LINES = 20_000
        private const val PRELOAD_BATCH_SIZE = 1_000
        private const val PRELOAD_BATCH_DELAY_MS = 18L
        private const val PRELOAD_BACKPRESSURE_LINES = 4_000
    }
}

private data class SshSessionRuntime(
    val id: Long,
    val controller: TerminalController,
    val engine: VtTerminalEngine,
    val connection: Connection,
    var job: Job? = null,
)

private fun SavedSshProfile.passwordScope() = SshPasswordScope(
    profileId = id,
    host = host,
    port = port,
    username = username,
)

private fun InputStream.readBounded(maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maximumBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        require(total <= maximumBytes) { "Selected file is too large." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
