package com.yanjiyu.terminalspike.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PendingInputModifiersTest {
    @Test fun systemKeyboardCUsesPendingControl() {
        assertArrayEquals(byteArrayOf(3), applyPendingInputModifiers("c".toByteArray(), true, false, false))
    }

    @Test fun oneShotOnlyModifiesFirstKeyInImeBatch() {
        assertArrayEquals(byteArrayOf(3, 100), applyPendingInputModifiers("cd".toByteArray(), true, false, false))
    }

    @Test fun altAndShiftCombineWithoutChangingFollowingText() {
        assertArrayEquals("\u001bCd".toByteArray(), applyPendingInputModifiers("cd".toByteArray(), false, true, true))
    }

    @Test fun unicodeAndTerminalSequencesArePreserved() {
        for (text in listOf("中文", "\u001b[A")) {
            assertArrayEquals(text.toByteArray(), applyPendingInputModifiers(text.toByteArray(), true, true, true))
        }
    }
}
