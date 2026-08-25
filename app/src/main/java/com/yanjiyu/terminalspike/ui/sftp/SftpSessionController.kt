package com.yanjiyu.terminalspike.ui.sftp

import com.yanjiyu.terminalspike.connection.HostIdentityDecision
import com.yanjiyu.terminalspike.connection.HostIdentityPrompt
import com.yanjiyu.terminalspike.connection.KeyboardInteractiveChallenge
import com.yanjiyu.terminalspike.connection.SftpClient
import com.yanjiyu.terminalspike.connection.SftpDownloadDestination
import com.yanjiyu.terminalspike.connection.SftpFile
import com.yanjiyu.terminalspike.connection.SftpUploadEntry
import com.yanjiyu.terminalspike.connection.SshConnectionConfig
import com.yanjiyu.terminalspike.connection.parentPath
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SftpSecretKind { PASSWORD, PASSPHRASE }

internal sealed interface SftpUiState {
    data object Closed : SftpUiState
    data class AuthenticationRequired(
        val hostId: String,
        val hostName: String,
        val kind: SftpSecretKind,
    ) : SftpUiState
    data class Connecting(
        val hostName: String,
        val hostIdentityPrompt: HostIdentityPrompt? = null,
        val keyboardInteractiveChallenge: KeyboardInteractiveChallenge? = null,
    ) : SftpUiState
    data class Browsing(
        val hostName: String,
        val path: String,
        val files: List<SftpFile>,
        val selectedPath: String? = null,
        val clipboard: SftpClipboard? = null,
        val busy: Boolean = false,
        val message: String? = null,
    ) : SftpUiState
    data class Failed(val hostName: String, val message: String) : SftpUiState
}

internal data class SftpClipboard(val sourcePath: String, val cut: Boolean)

internal class SftpSessionController(
    private val scope: CoroutineScope,
    private val newClient: () -> SftpClient,
) : AutoCloseable {
    private val _state = MutableStateFlow<SftpUiState>(SftpUiState.Closed)
    val state: StateFlow<SftpUiState> = _state.asStateFlow()
    private var client: SftpClient? = null
    private var operation: Job? = null

    fun requestAuthentication(hostId: String, hostName: String, kind: SftpSecretKind) {
        closeClient()
        _state.value = SftpUiState.AuthenticationRequired(hostId, hostName, kind)
    }

    fun connect(hostName: String, config: SshConnectionConfig) {
        closeClient()
        val next = newClient()
        client = next
        _state.value = SftpUiState.Connecting(hostName)
        operation = scope.launch(Dispatchers.IO) {
            try {
                val initialPath = next.connect(
                    config = config,
                    onHostIdentityPrompt = { prompt ->
                        _state.value = currentConnecting(hostName).copy(hostIdentityPrompt = prompt)
                    },
                    onKeyboardInteractiveChallenge = { challenge ->
                        _state.value = currentConnecting(hostName)
                            .copy(keyboardInteractiveChallenge = challenge)
                    },
                )
                showDirectory(hostName, initialPath, next)
            } catch (error: Exception) {
                next.close()
                if (client === next) client = null
                _state.value = SftpUiState.Failed(hostName, error.safeMessage())
            }
        }
    }

    fun answerHostIdentity(token: Long, decision: HostIdentityDecision) {
        client?.answerHostIdentityPrompt(token, decision)
        (_state.value as? SftpUiState.Connecting)?.let {
            _state.value = it.copy(hostIdentityPrompt = null)
        }
    }

    fun answerKeyboardInteractive(token: Long, responses: List<CharArray>) {
        client?.answerKeyboardInteractive(token, responses)
        (_state.value as? SftpUiState.Connecting)?.let {
            _state.value = it.copy(keyboardInteractiveChallenge = null)
        }
    }

    fun cancelKeyboardInteractive(token: Long) {
        client?.cancelKeyboardInteractive(token)
        close()
    }

    fun openDirectory(path: String) = mutate { state, active ->
        showDirectory(state.hostName, path, active, state.clipboard)
    }

    fun goUp() {
        val current = _state.value as? SftpUiState.Browsing ?: return
        if (current.path != "/") openDirectory(parentPath(current.path))
    }

    fun select(path: String?) {
        val current = _state.value as? SftpUiState.Browsing ?: return
        _state.value = current.copy(selectedPath = path, message = null)
    }

    fun copySelection(cut: Boolean) {
        val current = _state.value as? SftpUiState.Browsing ?: return
        val selected = current.selectedPath ?: return
        _state.value = current.copy(
            selectedPath = null,
            clipboard = SftpClipboard(selected, cut),
            message = if (cut) "Ready to move" else "Ready to copy",
        )
    }

    fun paste() = mutate { state, active ->
        val clipboard = state.clipboard ?: return@mutate
        if (clipboard.cut) active.move(clipboard.sourcePath, state.path)
        else active.copy(clipboard.sourcePath, state.path)
        showDirectory(state.hostName, state.path, active, clipboard.takeUnless { it.cut })
    }

    fun createDirectory(name: String) = mutate { state, active ->
        active.createDirectory(state.path, name)
        showDirectory(state.hostName, state.path, active, state.clipboard)
    }

    fun renameSelection(name: String) = mutate { state, active ->
        val selected = state.selectedPath ?: return@mutate
        active.rename(selected, name)
        showDirectory(state.hostName, state.path, active, state.clipboard)
    }

    fun deleteSelection() = mutate { state, active ->
        val selected = state.selectedPath ?: return@mutate
        active.delete(selected)
        showDirectory(state.hostName, state.path, active, state.clipboard)
    }

    suspend fun upload(name: String, open: () -> InputStream): Result<Unit> = runCatching {
        val state = _state.value as? SftpUiState.Browsing ?: error("SFTP is not ready.")
        val active = client ?: error("SFTP is not connected.")
        withContext(Dispatchers.IO) { active.upload(state.path, name, open()) }
        showDirectory(state.hostName, state.path, active, state.clipboard)
    }.onFailure(::showFailure)

    suspend fun uploadFiles(files: List<Pair<String, () -> InputStream>>): Result<Unit> = runCatching {
        require(files.isNotEmpty()) { "Choose at least one file to upload." }
        val state = beginTransfer()
        val active = client ?: error("SFTP is not connected.")
        withContext(Dispatchers.IO) {
            files.forEach { (name, open) -> active.upload(state.path, name, open()) }
        }
        showDirectory(state.hostName, state.path, active, state.clipboard)
        showMessage("Uploaded ${files.size} ${if (files.size == 1) "file" else "files"}")
    }.onFailure(::showFailure)

    suspend fun uploadFolder(
        rootName: String,
        entries: List<SftpUploadEntry>,
    ): Result<Unit> = runCatching {
        val state = beginTransfer()
        val active = client ?: error("SFTP is not connected.")
        withContext(Dispatchers.IO) { active.uploadRecursively(state.path, rootName, entries) }
        showDirectory(state.hostName, state.path, active, state.clipboard)
        showMessage("Uploaded $rootName")
    }.onFailure(::showFailure)

    suspend fun download(open: () -> OutputStream): Result<Unit> = runCatching {
        val state = _state.value as? SftpUiState.Browsing ?: error("SFTP is not ready.")
        val selected = state.selectedPath ?: error("Select a file to download.")
        val active = client ?: error("SFTP is not connected.")
        withContext(Dispatchers.IO) { active.download(selected, open()) }
    }.onFailure(::showFailure)

    suspend fun downloadSelected(
        destination: SftpDownloadDestination,
        destinationLabel: String,
    ): Result<Unit> = runCatching {
        val state = beginTransfer()
        val selected = state.selectedPath ?: error("Select a file or folder to download.")
        val selectedName = selected.substringAfterLast('/')
        val active = client ?: error("SFTP is not connected.")
        withContext(Dispatchers.IO) { active.downloadRecursively(selected, destination) }
        val current = _state.value as? SftpUiState.Browsing ?: state
        _state.value = current.copy(
            selectedPath = null,
            busy = false,
            message = "Downloaded $selectedName to $destinationLabel",
        )
    }.onFailure(::showFailure)

    fun dismissMessage() {
        val current = _state.value as? SftpUiState.Browsing ?: return
        _state.value = current.copy(message = null)
    }

    fun reportFailure(error: Throwable) = showFailure(error)

    override fun close() {
        operation?.cancel()
        operation = null
        closeClient()
        _state.value = SftpUiState.Closed
    }

    private fun mutate(block: suspend (SftpUiState.Browsing, SftpClient) -> Unit) {
        val current = _state.value as? SftpUiState.Browsing ?: return
        if (current.busy) return
        val active = client ?: return
        operation?.cancel()
        _state.value = current.copy(busy = true, message = null)
        operation = scope.launch(Dispatchers.IO) {
            try {
                block(current, active)
            } catch (error: Exception) {
                showFailure(error)
            }
        }
    }

    private fun beginTransfer(): SftpUiState.Browsing {
        val current = _state.value as? SftpUiState.Browsing ?: error("SFTP is not ready.")
        check(!current.busy) { "Another file operation is still running." }
        _state.value = current.copy(busy = true, message = null)
        return current
    }

    private fun showMessage(message: String) {
        val current = _state.value as? SftpUiState.Browsing ?: return
        _state.value = current.copy(busy = false, message = message)
    }

    private fun showDirectory(
        hostName: String,
        path: String,
        active: SftpClient,
        clipboard: SftpClipboard? = null,
    ) {
        _state.value = SftpUiState.Browsing(
            hostName = hostName,
            path = path,
            files = active.list(path),
            clipboard = clipboard,
        )
    }

    private fun showFailure(error: Throwable) {
        val current = _state.value
        _state.value = when (current) {
            is SftpUiState.Browsing -> current.copy(busy = false, message = error.safeMessage())
            is SftpUiState.Connecting -> SftpUiState.Failed(current.hostName, error.safeMessage())
            else -> current
        }
    }

    private fun currentConnecting(hostName: String): SftpUiState.Connecting =
        (_state.value as? SftpUiState.Connecting) ?: SftpUiState.Connecting(hostName)

    private fun closeClient() {
        client?.close()
        client = null
    }
}

private fun Throwable.safeMessage(): String = message
    ?.replace(Regex("[\\r\\n\\t]+"), " ")
    ?.take(240)
    ?.takeIf(String::isNotBlank)
    ?: "SFTP operation failed."
