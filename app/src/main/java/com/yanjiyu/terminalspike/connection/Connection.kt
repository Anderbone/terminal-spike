package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.terminal.TerminalInputSink

interface Connection : TerminalInputSink {
    suspend fun connect(
        columns: Int,
        rows: Int,
        onBytes: (ByteArray) -> Unit,
        onState: (ConnectionState) -> Unit,
    )

    fun resize(columns: Int, rows: Int)
    fun answerPrompt(accept: Boolean)
    fun close()
}

class SshConnectionConfig(
    val host: String,
    val port: Int,
    val username: String,
    val authentication: SshAuthentication,
)

sealed interface SshAuthentication {
    class Password(val secret: ByteArray) : SshAuthentication

    class StoredPassword(val loadSecret: () -> ByteArray) : SshAuthentication

    class PrivateKey(
        val identityName: String,
        val loadKey: () -> ByteArray,
        val passphrase: ByteArray?,
    ) : SshAuthentication
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class AwaitingApproval(val prompt: ConnectionPrompt) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

sealed interface ConnectionPrompt

data class HostKeyPrompt(
    val host: String,
    val algorithm: String,
    val sha256Fingerprint: String,
) : ConnectionPrompt
