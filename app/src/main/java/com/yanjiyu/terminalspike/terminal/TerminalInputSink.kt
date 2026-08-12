package com.yanjiyu.terminalspike.terminal

interface TerminalInputSink {
    fun send(bytes: ByteArray)

    fun trySend(bytes: ByteArray): Boolean {
        send(bytes)
        return true
    }

    /** Sends through the sink's normal direct-input policy and reports queue acceptance. */
    fun sendWithAcceptance(bytes: ByteArray): Boolean = trySend(bytes)
}
