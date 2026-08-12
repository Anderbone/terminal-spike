package com.yanjiyu.terminalspike.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class SshPtyConfigurationTest {
    @Test
    fun selectedTerminalTypeAndClampedDimensionsAreAppliedInOrder() {
        val target = RecordingPtyTarget()

        configureSshPty(
            target = target,
            terminalType = "xterm-direct",
            columns = 0,
            rows = -4,
        )

        assertEquals(
            listOf("pty", "term:xterm-direct", "size:1x1"),
            target.events,
        )
    }

    private class RecordingPtyTarget : SshPtyTarget {
        val events = mutableListOf<String>()

        override fun enablePty() {
            events += "pty"
        }

        override fun setTerminalType(terminalType: String) {
            events += "term:$terminalType"
        }

        override fun setDimensions(columns: Int, rows: Int) {
            events += "size:${columns}x$rows"
        }
    }
}
