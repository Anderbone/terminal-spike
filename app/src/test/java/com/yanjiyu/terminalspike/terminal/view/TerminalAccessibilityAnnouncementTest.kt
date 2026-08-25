package com.yanjiyu.terminalspike.terminal.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAccessibilityAnnouncementTest {
    @Test
    fun disabledAccessibilitySkipsUncheckedAnnouncementDispatch() {
        var dispatched = false

        val announced = dispatchTerminalAccessibilityAnnouncement(
            accessibilityEnabled = false,
            announce = { dispatched = true },
        )

        assertFalse(announced)
        assertFalse(dispatched)
    }

    @Test
    fun enabledAccessibilityDispatchesAnnouncement() {
        var dispatched = false

        val announced = dispatchTerminalAccessibilityAnnouncement(
            accessibilityEnabled = true,
            announce = { dispatched = true },
        )

        assertTrue(announced)
        assertTrue(dispatched)
    }
}
