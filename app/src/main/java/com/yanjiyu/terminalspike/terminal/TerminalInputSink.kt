package com.yanjiyu.terminalspike.terminal

interface TerminalInputSink {
    fun send(bytes: ByteArray)
}
