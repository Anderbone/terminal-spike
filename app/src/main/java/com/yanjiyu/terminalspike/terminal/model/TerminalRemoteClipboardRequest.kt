package com.yanjiyu.terminalspike.terminal.model

/**
 * A bounded OSC 52 write request emitted by the parser.
 *
 * Parsing never writes Android's clipboard. The surrounding session UI must enforce the selected
 * Disabled/Ask policy and require an explicit user decision before copying [text].
 */
data class TerminalRemoteClipboardRequest(
    val text: String,
) {
    init {
        require(text.isNotEmpty())
        require(text.length <= MAX_TEXT_BYTES)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES)
    }

    companion object {
        const val MAX_TEXT_BYTES = 768
    }
}
