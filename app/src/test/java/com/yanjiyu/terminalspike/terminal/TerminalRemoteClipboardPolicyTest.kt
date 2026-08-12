package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.terminal.model.TerminalRemoteClipboardRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TerminalRemoteClipboardPolicyTest {
    @Test
    fun disabledDropsAndAskRequiresAnExplicitDecision() {
        val request = TerminalRemoteClipboardRequest("remote text")

        assertEquals(
            TerminalRemoteClipboardDecision.Ignore,
            decideRemoteClipboardRequest(RemoteClipboardMode.DISABLED, request),
        )
        val ask = decideRemoteClipboardRequest(RemoteClipboardMode.ASK, request)
            as TerminalRemoteClipboardDecision.Ask
        assertSame(request, ask.request)
    }
}
