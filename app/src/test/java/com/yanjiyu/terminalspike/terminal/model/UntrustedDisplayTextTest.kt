package com.yanjiyu.terminalspike.terminal.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UntrustedDisplayTextTest {
    @Test
    fun controlsBidiFormattingAndRepeatedWhitespaceAreRemoved() {
        assertEquals(
            "Build gpj.exe done",
            sanitizeUntrustedDisplayText(
                "\u0000Build\t\u202Egpj.exe\u202C\u2066\u2069\n done\u0007",
                maximumLength = 512,
            ),
        )
    }

    @Test
    fun visibleInternationalTextIsPreserved() {
        assertEquals(
            "שלום مرحبا 漢字 café 🙂",
            sanitizeUntrustedDisplayText(
                "  שלום مرحبا 漢字 café 🙂  ",
                maximumLength = 512,
            ),
        )
    }

    @Test
    fun allUnsafeInputBecomesEmptyAndVisibleOutputIsBounded() {
        assertEquals("", sanitizeUntrustedDisplayText("\u0000\u001B\u202E\u2069", 8))
        assertEquals("visible", sanitizeUntrustedDisplayText("visible suffix", 7))
    }
}
