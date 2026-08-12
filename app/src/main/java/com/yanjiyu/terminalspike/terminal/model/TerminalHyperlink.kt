package com.yanjiyu.terminalspike.terminal.model

/** Bounded OSC 8 hyperlink metadata. Opening policy remains outside the terminal hot path. */
data class TerminalHyperlink(
    val uri: String,
    val id: String? = null,
) {
    init {
        require(uri.isNotEmpty() && uri.length <= MAX_URI_LENGTH)
        require(id == null || id.length <= MAX_ID_LENGTH)
    }

    companion object {
        const val MAX_URI_LENGTH = 1_024
        const val MAX_ID_LENGTH = 128
    }
}
