package com.yanjiyu.terminalspike.terminal

internal fun TerminalController.createDefaultTerminalInputSink(): TerminalInputSink =
    FakeTerminalSession { line -> append(listOf(line)) }
