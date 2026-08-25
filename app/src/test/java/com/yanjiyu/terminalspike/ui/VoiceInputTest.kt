package com.yanjiyu.terminalspike.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInputTest {
    @Test
    fun transcriptReplacesSelectionAndPlacesCursorAfterIt() {
        val initial = TextFieldValue("git old --stat", selection = TextRange(4, 7))

        val actual = initial.withVoiceTranscript("diff")

        assertEquals("git diff --stat", actual.text)
        assertEquals(TextRange(8), actual.selection)
    }

    @Test
    fun transcriptAtWordBoundaryGetsReadableSpacing() {
        val initial = TextFieldValue("git", selection = TextRange(3))

        val actual = initial.withVoiceTranscript("status")

        assertEquals("git status", actual.text)
        assertEquals(TextRange(10), actual.selection)
    }

    @Test
    fun blankTranscriptLeavesDraftUntouched() {
        val initial = TextFieldValue("keep me", selection = TextRange(2, 5))

        assertEquals(initial, initial.withVoiceTranscript("  "))
    }
}
