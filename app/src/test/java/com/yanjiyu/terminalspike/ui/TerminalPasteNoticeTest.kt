package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalPasteNoticeTest {
    @Test
    fun pasteTerminatorsHaveAnExplicitRejectionNotice() {
        val state = TerminalSpikeUiState()
        for (marker in listOf("\u001b[201~", "\u009b201~")) {
            assertEquals(
                uiText(R.string.notice_paste_control_blocked),
                state.bufferedInputValidationError(LOCAL_TERMINAL_SESSION_ID, "draft${marker}\rcommand"),
            )
        }
    }

    @Test
    fun otherControlsKeepTheirExistingValidationNotice() {
        assertEquals(
            uiText(R.string.notice_buffer_unsupported_control),
            TerminalSpikeUiState().bufferedInputValidationError(LOCAL_TERMINAL_SESSION_ID, "draft\u001bother"),
        )
    }

    @Test
    fun ordinaryMultilineUnicodeTextRemainsAllowed() {
        assertNull(TerminalSpikeUiState().bufferedInputValidationError(LOCAL_TERMINAL_SESSION_ID, "漢字 😀\nnext\tvalue"))
    }
}
