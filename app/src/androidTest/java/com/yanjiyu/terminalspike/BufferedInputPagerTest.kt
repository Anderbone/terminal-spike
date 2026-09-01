package com.yanjiyu.terminalspike

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
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

        composeRule.onNodeWithTag("terminal_input_pager").performTouchInput { swipeRight() }
        composeRule.onNodeWithContentDescription(resources.getString(R.string.terminal_buffered_input_description))
            .performTextInput("printf 'exact ✓'")

        composeRule.runOnIdle { assertEquals(0, sendCount) }
        composeRule.runOnIdle { inputTargetId.longValue = 22L }
        composeRule.onNodeWithText("printf 'exact ✓'").assertIsDisplayed()

        composeRule.onNodeWithText(resources.getString(R.string.terminal_send)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, sendCount)
            assertEquals(22L, sentTargetId)
            assertEquals("printf 'exact ✓'", sentText)
        }
        composeRule.onNodeWithText(
            resources.getString(R.string.terminal_buffered_input_placeholder),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            resources.getString(R.string.terminal_buffered_input_pasted_recoverable),
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
        composeRule.onNodeWithTag("terminal_input_pager").performTouchInput { swipeRight() }
        composeRule.onNodeWithContentDescription(resources.getString(R.string.terminal_buffered_input_description))
            .performTextInput(exactDraft)
        composeRule.onNodeWithText(resources.getString(R.string.terminal_send)).performClick()

        composeRule.onNodeWithText(resources.getString(R.string.terminal_multiline_paste_title)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, sendCount) }
        composeRule.onNodeWithText(resources.getString(R.string.cancel)).performClick()
        composeRule.onNodeWithText(exactDraft).assertIsDisplayed()

        composeRule.onNodeWithText(resources.getString(R.string.terminal_send)).performClick()
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
