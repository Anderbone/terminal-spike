package com.yanjiyu.terminalspike.terminal

internal fun TerminalController.createDefaultTerminalInputSink(): TerminalInputSink =
    InactiveTerminalInputSink

private data object InactiveTerminalInputSink : TerminalInputSink {
    override fun send(bytes: ByteArray) = Unit

    override fun trySend(bytes: ByteArray): Boolean = false
}
