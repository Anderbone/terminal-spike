package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.terminal.model.TerminalLine
import com.yanjiyu.terminalspike.terminal.model.TerminalPalette
import com.yanjiyu.terminalspike.terminal.model.TerminalRun
import com.yanjiyu.terminalspike.terminal.model.TerminalStyle
import java.nio.charset.StandardCharsets

class FakeTerminalSession(
    private val echo: (TerminalLine) -> Unit,
) : TerminalInputSink {
    override fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val representation = when {
            bytes.contentEquals(byteArrayOf(0x1b)) -> "ESC"
            bytes.contentEquals(byteArrayOf(0x7f)) -> "BACKSPACE"
            bytes.contentEquals(byteArrayOf('\r'.code.toByte())) -> "ENTER"
            bytes.contentEquals(byteArrayOf('\t'.code.toByte())) -> "TAB"
            bytes.contentEquals(byteArrayOf(0x1b, 0x5b, 0x41)) -> "ARROW UP"
            bytes.contentEquals(byteArrayOf(0x1b, 0x5b, 0x42)) -> "ARROW DOWN"
            bytes.contentEquals(byteArrayOf(0x1b, 0x5b, 0x43)) -> "ARROW RIGHT"
            bytes.contentEquals(byteArrayOf(0x1b, 0x5b, 0x44)) -> "ARROW LEFT"
            bytes.contentEquals(byteArrayOf(0x1b, 0x5b, 0x35, 0x7e)) -> "PAGE UP"
            bytes.contentEquals(byteArrayOf(0x1b, 0x5b, 0x36, 0x7e)) -> "PAGE DOWN"
            else -> String(bytes, StandardCharsets.UTF_8).replace("\r", "↵").replace("\n", "↵")
        }
        echo(
            TerminalLine.styled(
                listOf(
                    TerminalRun(
                        "› input ",
                        TerminalStyle(foreground = TerminalPalette.CYAN, bold = true),
                    ),
                    TerminalRun(representation),
                ),
            ),
        )
    }
}
