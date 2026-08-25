package com.yanjiyu.terminalspike.terminal.view

import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FastTerminalPreImeBackTest {
    @Test
    fun activeTerminalBackBypassesTheImeAndCommitsExactlyOnce() {
        var invocations = 0
        var consumedDown = false
        var consumedUp = false

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = FastTerminalView(ApplicationProvider.getApplicationContext())
            view.setPreImeBackCallback { invocations += 1 }

            consumedDown = view.onKeyPreIme(
                KeyEvent.KEYCODE_BACK,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK),
            )
            consumedUp = view.onKeyPreIme(
                KeyEvent.KEYCODE_BACK,
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK),
            )
        }

        assertTrue(consumedDown)
        assertTrue(consumedUp)
        assertEquals(1, invocations)
    }

    @Test
    fun terminalWithoutASurroundingBackPolicyDoesNotTrapBack() {
        var consumed = true

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = FastTerminalView(ApplicationProvider.getApplicationContext())
            consumed = view.onKeyPreIme(
                KeyEvent.KEYCODE_BACK,
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK),
            )
        }

        assertFalse(consumed)
    }
}
