package com.yanjiyu.terminalspike.core.model

import org.junit.Assert.*
import org.junit.Test

class InputKeyboardLayoutTest {
    @Test fun untouchedSettingsKeepLegacyLayoutAndSide() {
        assertNull(InputKeyboardLayout.parse(""))
        assertEquals(listOf("enter", "toggle", "input"), InputKeyboardLayout.defaultTokens(false))
        assertEquals(listOf("input", "enter", "toggle"), InputKeyboardLayout.defaultTokens(true))
    }

    @Test fun independentSidesAndOptionalEnterRetainExactOrder() {
        val value = "toggle,control,tab,input,escape,enter"
        assertEquals(value, InputKeyboardLayout.parse(value)!!.joinToString(","))
        assertNotNull(InputKeyboardLayout.parse("toggle,input,tab"))
        assertNotNull(InputKeyboardLayout.parse("toggle,enter,input,toggle,enter"))
    }

    @Test fun unusableLayoutsFallBackWithoutLosingModeSwitch() {
        listOf("input,enter", "toggle,enter", "toggle,input,input", "toggle,input,unknown",
            "input,tab,tab", "toggle,input,tab,enter,escape,control,alt,shift").forEach {
            assertNull(it, InputKeyboardLayout.parse(it))
        }
    }
}
