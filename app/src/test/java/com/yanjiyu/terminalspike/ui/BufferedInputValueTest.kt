package com.yanjiyu.terminalspike.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferedInputValueTest {
    @Test
    fun composingDraftCannotSendUntilTheImeCommitsIt() {
        val draftState = BufferedInputDraftState()
        var dispatched: String? = null
        val composing = TextFieldValue(
            text = "nihao",
            selection = TextRange(5),
            composition = TextRange(0, 5),
        )

        draftState.update(composing, activeSessionId = 11L)
        assertFalse(composing.isBufferedSendEligible(sendEnabled = true, validationMessage = null))
        draftState.dispatch(activeSessionId = 11L, sendEnabled = true) { _, text ->
            dispatched = text
            true
        }

        assertEquals(null, dispatched)
        assertEquals(composing, draftState.value)

        val committed = composing.copy(composition = null)
        draftState.update(committed, activeSessionId = 11L)
        assertTrue(committed.isBufferedSendEligible(sendEnabled = true, validationMessage = null))
        draftState.dispatch(activeSessionId = 11L, sendEnabled = true) { _, text ->
            dispatched = text
            true
        }

        assertEquals("nihao", dispatched)
        assertEquals(TextFieldValue(), draftState.value)
    }

    @Test
    fun oversizedChangeIsRejectedWithoutCreatingAPartialDraft() {
        val draftState = BufferedInputDraftState()
        val original = TextFieldValue("keep this exact")
        var dispatchCount = 0
        draftState.update(original, activeSessionId = 11L)

        draftState.update(
            TextFieldValue("a".repeat(MAX_BUFFERED_INPUT_CHARACTERS) + "😀"),
            activeSessionId = 11L,
        )
        draftState.dispatch(activeSessionId = 11L, sendEnabled = true) { _, _ ->
            dispatchCount += 1
            true
        }

        assertEquals(original, draftState.value)
        assertNotNull(draftState.validationMessage)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun rejectedSendCanRetainTheCompleteDraftValue() {
        val draft = TextFieldValue(
            text = "git status",
            selection = TextRange(4, 10),
        )

        val afterRejectedSend = draft.afterBufferedSend(accepted = false)

        assertEquals(draft, afterRejectedSend)
    }

    @Test
    fun acceptedSendClearsTheDraft() {
        val draft = TextFieldValue("git status")

        assertEquals(TextFieldValue(), draft.afterBufferedSend(accepted = true))
    }

    @Test
    fun onlyDraftsWithLineBreaksRequireMultilineConfirmation() {
        assertFalse("printf 'one line'".requiresMultilineConfirmation())
        assertEquals(true, "printf one\nprintf two".requiresMultilineConfirmation())
        assertEquals(true, "printf one\rprintf two".requiresMultilineConfirmation())
    }

    @Test
    fun draftStateSendsToTheActiveSession() {
        val draftState = BufferedInputDraftState()
        val draft = TextFieldValue(
            text = "git status",
            selection = TextRange(4, 10),
        )
        var sentTargetId: Long? = null

        draftState.update(draft, activeSessionId = 11L)
        draftState.update(draft.copy(selection = TextRange(3)), activeSessionId = 22L)
        draftState.dispatch(activeSessionId = 22L, sendEnabled = true) { targetId, _ ->
            sentTargetId = targetId
            false
        }

        assertEquals(22L, sentTargetId)
        assertEquals(draft.copy(selection = TextRange(3)), draftState.value)

        draftState.dispatch(activeSessionId = 11L, sendEnabled = true) { targetId, _ ->
            sentTargetId = targetId
            true
        }

        assertEquals(11L, sentTargetId)
        assertEquals(TextFieldValue(), draftState.value)
    }

    @Test
    fun targetedSnippetInsertionCanEditANonEmptyDraftFromAnotherSession() {
        val draftState = BufferedInputDraftState()
        val original = TextFieldValue("echo first")
        draftState.update(original, activeSessionId = 11L)

        assertTrue(
            draftState.updateForTarget(
                TextFieldValue("echo firstecho second"),
                targetSessionId = 22L,
            ),
        )
        assertEquals(TextFieldValue("echo firstecho second"), draftState.value)
        assertEquals(
            true,
            draftState.updateForTarget(
                TextFieldValue("echo first && echo second"),
                targetSessionId = 11L,
            ),
        )
    }
}
