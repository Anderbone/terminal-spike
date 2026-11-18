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

    @Test
    fun supplementaryFormattingIsRemovedWithoutRemovingVisibleSupplementaryText() {
        assertEquals(
            "Build 🙂done",
            sanitizeUntrustedDisplayText("Build \uDB40\uDC01🙂\uD834\uDD73done", 512),
        )
        assertEquals("", sanitizeUntrustedDisplayText("\uDB40\uDC01\uDB40\uDC20", 512))
    }

    @Test
    fun truncationKeepsWholeCodePointsAndDoesNotLeaveATrailingSeparator() {
        assertEquals("", sanitizeUntrustedDisplayText("🙂tail", 1))
        assertEquals("🙂", sanitizeUntrustedDisplayText("🙂tail", 2))
        assertEquals("A", sanitizeUntrustedDisplayText("A🙂tail", 2))
        assertEquals("A🙂", sanitizeUntrustedDisplayText("A🙂tail", 3))
        assertEquals("A", sanitizeUntrustedDisplayText("A\n🙂tail", 3))
        assertEquals("A 🙂", sanitizeUntrustedDisplayText("A\n🙂tail", 4))
        assertEquals("A B", sanitizeUntrustedDisplayText("  A\u00A0\u2007\u202FB  ", 3))
    }

    @Test
    fun malformedUtf16UsesReplacementCharactersWithoutChangingValidPairs() {
        assertEquals(
            "�X�🙂",
            sanitizeUntrustedDisplayText("\uD83DX\uDE42🙂", 512),
        )
    }
}
