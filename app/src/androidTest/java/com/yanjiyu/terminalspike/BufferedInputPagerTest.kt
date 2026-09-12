package com.yanjiyu.terminalspike

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import com.yanjiyu.terminalspike.ui.BufferedInputDraftState
import com.yanjiyu.terminalspike.ui.ExtraKeysBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BufferedInputPagerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun keyboardSendStagesTextAndExplicitEnterSubmitsEvenWithAnEmptyDraft() {
        val staged = mutableListOf<String>()
        val submitted = mutableListOf<String>()
        var enterCount = 0
        val draftState = BufferedInputDraftState()
        composeRule.setContent {
            MaterialTheme {
                ExtraKeysBar(
                    keys = TerminalExtraKey.DEFAULT_ORDER,
                    ctrlArmed = false,
                    altArmed = false,
                    customizationEnabled = true,
                    inputTargetId = 11L,
                    bufferedInputSendEnabled = true,
                    bufferedInputDraftState = draftState,
                    onKey = { if (it == TerminalExtraKey.ENTER) enterCount++ },
                    onCustomize = {},
                    onSendBufferedInput = { _, text -> staged.add(text); true },
                    onSubmitBufferedInput = { _, text -> submitted.add(text); true },
                    onBufferedInputModeChanged = {},
                    onDirectInputMode = {},
                )
            }
        }
        val input = composeRule.onNodeWithTag("buffered_terminal_input")
        input.performTextInput("first part")
        input.performImeAction()
        input.performTextInput("second part")
        input.performImeAction()
        composeRule.runOnIdle {
            assertEquals(listOf("first part", "second part"), staged)
            assertEquals(emptyList<String>(), submitted)
            assertEquals(0, enterCount)
            assertEquals("", draftState.value.text)
        }
        composeRule.onNodeWithTag("buffered_input_enter").performClick()
        composeRule.runOnIdle { assertEquals(1, enterCount) }
        input.performTextInput("submit this draft")
        composeRule.onNodeWithTag("buffered_input_enter").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("submit this draft"), submitted)
            assertEquals(1, enterCount)
            assertEquals("", draftState.value.text)
        }
    }

    @Test
    fun stagedTextFollowsTheActiveSessionWhenSent() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val inputTargetId = mutableLongStateOf(11L)
        var sendCount = 0
        var sentTargetId: Long? = null
        var sentText: String? = null
        val draftState = BufferedInputDraftState()

        composeRule.setContent {
            MaterialTheme {
                ExtraKeysBar(
                    keys = TerminalExtraKey.DEFAULT_ORDER,
                    ctrlArmed = false,
                    altArmed = false,
                    customizationEnabled = true,
                    inputTargetId = inputTargetId.longValue,
                    bufferedInputSendEnabled = true,
                    bufferedInputDraftState = draftState,
                    onKey = {},
                    onCustomize = {},
                    onSendBufferedInput = { targetId, text ->
                        sendCount += 1
                        sentTargetId = targetId
                        sentText = text
                        targetId == inputTargetId.longValue
                    },
                    onBufferedInputModeChanged = {},
                    onDirectInputMode = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(resources.getString(R.string.terminal_buffered_input_description))
            .performTextInput("printf 'exact ✓'")

        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        composeRule.onNodeWithText("ESC").assertIsDisplayed()
        composeRule.onNodeWithTag("buffered_terminal_input").assertDoesNotExist()
        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        composeRule.onNodeWithText("printf 'exact ✓'").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, sendCount) }
        composeRule.runOnIdle { inputTargetId.longValue = 22L }
        composeRule.onNodeWithText("printf 'exact ✓'").assertIsDisplayed()

        composeRule.onNodeWithTag("buffered_input_enter").performClick()

        composeRule.runOnIdle {
            assertEquals(1, sendCount)
            assertEquals(22L, sentTargetId)
            assertEquals("printf 'exact ✓'", sentText)
        }
        composeRule.onNodeWithText(
            resources.getString(R.string.terminal_buffered_input_placeholder),
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            resources.getString(R.string.terminal_restore_last_sent_input),
        ).performClick()
        composeRule.onNodeWithText("printf 'exact ✓'").assertIsDisplayed()
    }

    @Test
    fun multilineSendRequiresConfirmationAndCancelKeepsTheDraft() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        var sendCount = 0
        var sentText: String? = null
        val draftState = BufferedInputDraftState()

        composeRule.setContent {
            MaterialTheme {
                ExtraKeysBar(
                    keys = TerminalExtraKey.DEFAULT_ORDER,
                    ctrlArmed = false,
                    altArmed = false,
                    customizationEnabled = true,
                    inputTargetId = 11L,
                    bufferedInputSendEnabled = true,
                    bufferedInputDraftState = draftState,
                    onKey = {},
                    onCustomize = {},
                    onSendBufferedInput = { _, text ->
                        sendCount += 1
                        sentText = text
                        true
                    },
                    onBufferedInputModeChanged = {},
                    onDirectInputMode = {},
                )
            }
        }

        val exactDraft = "printf one\nprintf two"
        composeRule.runOnIdle { draftState.update(TextFieldValue(exactDraft), 11L) }
        composeRule.onNodeWithText(exactDraft).assertIsDisplayed()
        composeRule.onNodeWithTag("buffered_input_enter").performClick()

        composeRule.onNodeWithText(resources.getString(R.string.terminal_multiline_paste_title)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, sendCount) }
        composeRule.onNodeWithText(resources.getString(R.string.cancel)).performClick()
        composeRule.onNodeWithText(exactDraft).assertIsDisplayed()

        composeRule.onNodeWithTag("buffered_input_enter").performClick()
        composeRule.onNodeWithText(resources.getString(R.string.terminal_paste)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, sendCount)
            assertEquals(exactDraft, sentText)
        }
        composeRule.onNodeWithText(
            resources.getString(R.string.terminal_buffered_input_placeholder),
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            resources.getString(R.string.terminal_restore_last_sent_input),
        ).performClick()
        composeRule.onNodeWithText(exactDraft).assertIsDisplayed()
    }
}
