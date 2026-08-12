package com.yanjiyu.terminalspike.terminal.view

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalInputConnectionTest {
    @Test
    fun directInputExplicitlyDisablesCorrectionAndPersonalizedLearning() {
        val editorInfo = EditorInfo()

        TerminalInputConnection.configureEditorInfo(editorInfo)

        assertEquals(
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            editorInfo.inputType and InputType.TYPE_MASK_VARIATION,
        )
        assertTrue(editorInfo.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertTrue(editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }

    @Test
    fun deletionEncodesEveryRequestedBeforeAndAfterCodeUnitWithinABound() {
        assertArrayEquals(
            byteArrayOf(0x7f, 0x7f) + TerminalKeySequences.DELETE,
            encodeTerminalDeletion(beforeLength = 2, afterLength = 1),
        )
        assertEquals(256, encodeTerminalDeletion(beforeLength = Int.MAX_VALUE, afterLength = 0).size)
        assertTrue(encodeTerminalDeletion(0, 0).isEmpty())
    }

    @Test
    fun codePointDeletionNeverLeavesHalfOfASurrogatePair() {
        assertEquals("A", "A😀".dropLastCodePoints(1))
        assertEquals("", "A😀".dropLastCodePoints(2))
        assertEquals("A😀", "A😀".dropLastCodePoints(0))
    }

    @Test
    fun latinCompositionStreamsOnlyTheUnsentSuffixSoTheTerminalCursorKeepsUp() {
        val composition = TerminalImeCompositionState()

        assertArrayEquals("h".encodeToByteArray(), composition.update("h"))
        assertArrayEquals("ello".encodeToByteArray(), composition.update("hello"))
        assertArrayEquals(" ".encodeToByteArray(), composition.commit("hello "))
        assertTrue(composition.finish().isEmpty())
    }

    @Test
    fun complexCompositionRemainsPrivateUntilTheImeFinalizesIt() {
        val composition = TerminalImeCompositionState()

        assertTrue(composition.update("に").isEmpty())
        assertTrue(composition.update("日本").isEmpty())

        assertArrayEquals("日本".encodeToByteArray(), composition.finish())
    }

    @Test
    fun cancelledCompositionCannotReplayAfterAnExternalControlChord() {
        val composition = TerminalImeCompositionState()

        assertArrayEquals("first".encodeToByteArray(), composition.update("first"))
        assertArrayEquals(" ".encodeToByteArray(), composition.commit("first "))
        assertArrayEquals("second".encodeToByteArray(), composition.update("second"))

        composition.cancel()

        assertTrue(composition.commit("second").isEmpty())
        assertTrue(composition.finish().isEmpty())
    }
}
