package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshRuntimeLimitNoticeTest {
    @Test
    fun sshNeverShowsAMoshLimitation() {
        assertNull(
            moshRuntimeLimitNotice(
                protocol = ConnectionProtocol.SSH,
                selectedTerminalType = "xterm-direct",
                startupCommandConfigured = true,
            ),
        )
    }

    @Test
    fun moshSurfacesFixedTermAndIgnoredStartupWithoutCommandText() {
        val sensitiveCommand = "printf PRIVATE_STARTUP_COMMAND"

        val notice = requireNotNull(
            moshRuntimeLimitNotice(
                protocol = ConnectionProtocol.MOSH,
                selectedTerminalType = "xterm-direct",
                startupCommandConfigured = sensitiveCommand.isNotEmpty(),
            ),
        )

        assertEquals(
            uiText(
                R.string.notice_pair,
                uiText(R.string.notice_mosh_fixed_term, "xterm-256color"),
                uiText(R.string.notice_mosh_startup_ssh_only),
            ),
            notice,
        )
        assertFalse(notice.toString().contains(sensitiveCommand))
        assertFalse(notice.toString().contains("PRIVATE_STARTUP_COMMAND"))
    }

    @Test
    fun matchingFixedMoshTermWithoutStartupNeedsNoWarning() {
        assertNull(
            moshRuntimeLimitNotice(
                protocol = ConnectionProtocol.MOSH,
                selectedTerminalType = "xterm-256color",
                startupCommandConfigured = false,
            ),
        )
    }
}
