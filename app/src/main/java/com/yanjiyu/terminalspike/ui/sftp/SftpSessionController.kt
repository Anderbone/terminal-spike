package com.yanjiyu.terminalspike.ui.sftp

import com.yanjiyu.terminalspike.connection.HostIdentityDecision
import com.yanjiyu.terminalspike.connection.HostIdentityPrompt
import com.yanjiyu.terminalspike.connection.KeyboardInteractiveChallenge
import com.yanjiyu.terminalspike.connection.SftpDownloadDestination
import com.yanjiyu.terminalspike.connection.SftpFile
import com.yanjiyu.terminalspike.connection.SftpSession
import com.yanjiyu.terminalspike.connection.SftpUploadSource
import com.yanjiyu.terminalspike.connection.SshConnectionConfig
import com.yanjiyu.terminalspike.connection.parentPath
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

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
    private val operationContext: CoroutineContext = Dispatchers.IO,
    private val newClient: () -> SftpSession,
) : AutoCloseable {
    private val _state = MutableStateFlow<SftpUiState>(SftpUiState.Closed)
    val state: StateFlow<SftpUiState> = _state.asStateFlow()
    private val ownershipLock = Any()
    @Volatile
    private var client: SftpSession? = null
    @Volatile
    private var generation: Long = 0
    private var operation: Job? = null

    fun requestAuthentication(hostId: String, hostName: String, kind: SftpSecretKind) {
        supersedeAndCloseClient()
        _state.value = SftpUiState.AuthenticationRequired(hostId, hostName, kind)
    }

    fun connect(hostName: String, config: SshConnectionConfig) {
        supersedeAndCloseClient()
        val next = newClient()
        val ownerGeneration = synchronized(ownershipLock) {
            client = next
            generation
        }
        _state.value = SftpUiState.Connecting(hostName)
        operation = scope.launch(operationContext) {
            try {
                val initialPath = next.connect(
                    config = config,
                    onHostIdentityPrompt = { prompt ->
                        publishIfOwned(ownerGeneration, next) {
                            currentConnecting(hostName).copy(hostIdentityPrompt = prompt)
                        }
                    },
                    onKeyboardInteractiveChallenge = { challenge ->
                        publishIfOwned(ownerGeneration, next) {
                            currentConnecting(hostName).copy(
                                keyboardInteractiveChallenge = challenge,
                            )
                        }
                    },
                )
                showDirectory(hostName, initialPath, next, ownerGeneration = ownerGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                next.close()
                synchronized(ownershipLock) {
                    if (generation == ownerGeneration && client === next) {
                        client = null
                        _state.value = SftpUiState.Failed(hostName, error.safeMessage())
                    }
                }
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
        val owned = synchronized(ownershipLock) {
            client?.let { active -> OwnedPromptClient(active, generation) }
        }
        if (owned == null) {
            responses.forEach { response -> response.fill('\u0000') }
            return
        }
        if (!owned.active.answerKeyboardInteractive(token, responses)) return
        synchronized(ownershipLock) {
            if (generation != owned.generation || client !== owned.active) return@synchronized
            val current = _state.value as? SftpUiState.Connecting ?: return@synchronized
            if (current.keyboardInteractiveChallenge?.challengeToken == token) {
                _state.value = current.copy(keyboardInteractiveChallenge = null)
            }
        }
    }

    fun cancelKeyboardInteractive(token: Long) {
        client?.cancelKeyboardInteractive(token)
        close()
    }

    fun openDirectory(path: String) = mutate { state, active, ownerGeneration ->
        showDirectory(state.hostName, path, active, state.clipboard, ownerGeneration)
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

    fun paste() = mutate { state, active, ownerGeneration ->
        val clipboard = state.clipboard ?: return@mutate
        if (clipboard.cut) active.move(clipboard.sourcePath, state.path)
        else active.copy(clipboard.sourcePath, state.path)
        showDirectory(
            state.hostName,
            state.path,
            active,
            clipboard.takeUnless { it.cut },
            ownerGeneration,
        )
    }

    fun createDirectory(name: String) = mutate { state, active, ownerGeneration ->
        active.createDirectory(state.path, name)
        showDirectory(state.hostName, state.path, active, state.clipboard, ownerGeneration)
    }

    fun renameSelection(name: String) = mutate { state, active, ownerGeneration ->
        val selected = state.selectedPath ?: return@mutate
        active.rename(selected, name)
        showDirectory(state.hostName, state.path, active, state.clipboard, ownerGeneration)
    }

    fun deleteSelection() = mutate { state, active, ownerGeneration ->
        val selected = state.selectedPath ?: return@mutate
        active.delete(selected)
        showDirectory(state.hostName, state.path, active, state.clipboard, ownerGeneration)
    }

    suspend fun upload(name: String, open: () -> InputStream): Result<Unit> = transfer { owned ->
        withContext(operationContext) { owned.active.upload(owned.state.path, name, open()) }
        showDirectory(
            owned.state.hostName,
            owned.state.path,
            owned.active,
            owned.state.clipboard,
            owned.generation,
        )
    }

    suspend fun uploadFiles(files: List<Pair<String, () -> InputStream>>): Result<Unit> = transfer {
        owned ->
        require(files.isNotEmpty()) { "Choose at least one file to upload." }
        withContext(operationContext) {
            files.forEach { (name, open) -> owned.active.upload(owned.state.path, name, open()) }
        }
        showDirectory(
            owned.state.hostName,
            owned.state.path,
            owned.active,
            owned.state.clipboard,
            owned.generation,
        )
        showMessageIfOwned(
            "Uploaded ${files.size} ${if (files.size == 1) "file" else "files"}",
            owned,
        )
    }

    suspend fun uploadFolder(source: SftpUploadSource): Result<Unit> = transfer { owned ->
        withContext(operationContext) {
            owned.active.uploadRecursively(owned.state.path, source)
        }
        showDirectory(
            owned.state.hostName,
            owned.state.path,
            owned.active,
            owned.state.clipboard,
            owned.generation,
        )
        showMessageIfOwned("Uploaded ${source.rootName}", owned)
    }

    suspend fun download(open: () -> OutputStream): Result<Unit> = transfer { owned ->
        val selected = owned.state.selectedPath ?: error("Select a file to download.")
        withContext(operationContext) { owned.active.download(selected, open()) }
        publishIfOwned(owned.generation, owned.active) {
            val current = _state.value as? SftpUiState.Browsing ?: owned.state
            current.copy(selectedPath = null, busy = false)
        }
    }

    suspend fun downloadSelected(
        destination: SftpDownloadDestination,
        destinationLabel: String,
    ): Result<Unit> = transfer { owned ->
        val selected = owned.state.selectedPath ?: error("Select a file or folder to download.")
        val selectedName = selected.substringAfterLast('/')
        withContext(operationContext) { owned.active.downloadRecursively(selected, destination) }
        publishIfOwned(owned.generation, owned.active) {
            val current = _state.value as? SftpUiState.Browsing ?: owned.state
            current.copy(
                selectedPath = null,
                busy = false,
                message = "Downloaded $selectedName to $destinationLabel",
            )
        }
    }

    fun dismissMessage() {
        val current = _state.value as? SftpUiState.Browsing ?: return
        _state.value = current.copy(message = null)
    }

    fun reportFailure(error: Throwable) = showFailure(error)

    override fun close() {
        supersedeAndCloseClient()
        _state.value = SftpUiState.Closed
    }

    private fun mutate(block: suspend (SftpUiState.Browsing, SftpSession, Long) -> Unit) {
        val current = _state.value as? SftpUiState.Browsing ?: return
        if (current.busy) return
        val active = client ?: return
        operation?.cancel()
        val ownerGeneration = synchronized(ownershipLock) {
            generation += 1
            generation
        }
        _state.value = current.copy(busy = true, message = null)
        operation = scope.launch(operationContext) {
            try {
                block(current, active, ownerGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showFailureIfOwned(error, ownerGeneration, active)
            }
        }
    }

    private fun beginTransfer(): OwnedBrowsing {
        val current = _state.value as? SftpUiState.Browsing ?: error("SFTP is not ready.")
        check(!current.busy) { "Another file operation is still running." }
        val active = client ?: error("SFTP is not connected.")
        operation?.cancel()
        operation = null
        val ownerGeneration = synchronized(ownershipLock) {
            generation += 1
            generation
        }
        _state.value = current.copy(busy = true, message = null)
        return OwnedBrowsing(current, active, ownerGeneration)
    }

    private suspend fun <T> transfer(block: suspend (OwnedBrowsing) -> T): Result<T> {
        val owned = try {
            beginTransfer()
        } catch (error: Exception) {
            showFailure(error)
            return Result.failure(error)
        }
        return try {
            Result.success(block(owned))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            showFailureIfOwned(error, owned.generation, owned.active)
            Result.failure(error)
        }
    }

    private fun showMessageIfOwned(message: String, owned: OwnedBrowsing) {
        publishIfOwned(owned.generation, owned.active) {
            val current = _state.value as? SftpUiState.Browsing ?: owned.state
            current.copy(busy = false, message = message)
        }
    }

    private fun showDirectory(
        hostName: String,
        path: String,
        active: SftpSession,
        clipboard: SftpClipboard? = null,
        ownerGeneration: Long = generation,
    ) {
        val files = active.list(path)
        publishIfOwned(ownerGeneration, active) {
            SftpUiState.Browsing(
                hostName = hostName,
                path = path,
                files = files,
                clipboard = clipboard,
            )
        }
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

    private fun supersedeAndCloseClient() {
        val previousOperation: Job?
        val previousClient: SftpSession?
        synchronized(ownershipLock) {
            generation += 1
            previousOperation = operation
            operation = null
            previousClient = client
            client = null
        }
        previousOperation?.cancel()
        previousClient?.close()
    }

    private fun owns(ownerGeneration: Long, active: SftpSession): Boolean =
        synchronized(ownershipLock) {
            generation == ownerGeneration && client === active
        }

    private inline fun publishIfOwned(
        ownerGeneration: Long,
        active: SftpSession,
        state: () -> SftpUiState,
    ) {
        synchronized(ownershipLock) {
            if (generation == ownerGeneration && client === active) _state.value = state()
        }
    }

    private fun showFailureIfOwned(
        error: Throwable,
        ownerGeneration: Long,
        active: SftpSession,
    ) {
        synchronized(ownershipLock) {
            if (generation == ownerGeneration && client === active) showFailure(error)
        }
    }

    private data class OwnedBrowsing(
        val state: SftpUiState.Browsing,
        val active: SftpSession,
        val generation: Long,
    )

    private data class OwnedPromptClient(
        val active: SftpSession,
        val generation: Long,
    )
}

private fun Throwable.safeMessage(): String = message
    ?.replace(Regex("[\\r\\n\\t]+"), " ")
    ?.take(240)
    ?.takeIf(String::isNotBlank)
    ?: "SFTP operation failed."
