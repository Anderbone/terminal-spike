package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.terminal.model.TerminalLine

interface TerminalRendererController {
    fun append(lines: List<TerminalLine>)
    fun replaceVisibleScreen(lines: List<TerminalLine>)
    fun clear()
    fun jumpToBottom()
}
