package com.yanjiyu.terminalspike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.terminal.view.AccessoryModifierSnapshot
import com.yanjiyu.terminalspike.terminal.view.AccessoryModifierState
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryAction
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import com.yanjiyu.terminalspike.terminal.view.toAccessoryAction
import com.yanjiyu.terminalspike.ui.BufferedInputDraftState
import com.yanjiyu.terminalspike.ui.TerminalAccessoryBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TerminalAccessoryWorkflowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rawAndTextLabelsAndDeckVisibilityControlsAreHidden() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        TerminalAccessoryBar(
                            actions = TerminalExtraKey.DEFAULT_ORDER.map { it.toAccessoryAction() },
                            modifiers = AccessoryModifierSnapshot(),
                            layout = KeyboardLayout.TWO_ROWS,
                            inputMode = TerminalInputMode.RAW,
                            customizationEnabled = true,
                            inputTargetId = 7L,
                            bufferedInputSendEnabled = true,
                            bufferedInputDraftState = remember { BufferedInputDraftState() },
                            onAction = {},
                            onCustomize = {},
                            onSendBufferedInput = { _, _ -> true },
                            onBufferedInputModeChanged = {},
                            onDirectInputMode = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        composeRule.onNodeWithText("Raw").assertDoesNotExist()
        composeRule.onNodeWithText("Text").assertDoesNotExist()
        composeRule.onNodeWithTag("terminal_accessory_collapse").assertDoesNotExist()
        composeRule.onNodeWithTag("terminal_accessory_expand").assertDoesNotExist()
        composeRule.onNodeWithText("Show terminal keys").assertDoesNotExist()
        composeRule.onNodeWithTag("terminal_input_pager").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Terminal key /").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hide software keyboard").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Terminal key →").assertIsDisplayed()
    }

    @Test
    fun lockedModifierHasDistinctSpokenStateAndLockMarker() {
        composeRule.setContent {
            MaterialTheme {
                TerminalAccessoryBar(
                    actions = listOf(
                        TerminalExtraKey.CTRL.toAccessoryAction(),
                        TerminalExtraKey.CTRL_C.toAccessoryAction(),
                    ),
                    modifiers = AccessoryModifierSnapshot(control = AccessoryModifierState.LOCKED),
                    customizationEnabled = false,
                    inputTargetId = 7L,
                    bufferedInputSendEnabled = true,
                    bufferedInputDraftState = remember { BufferedInputDraftState() },
                    onAction = {},
                    onCustomize = {},
                    onSendBufferedInput = { _, _ -> true },
                    onBufferedInputModeChanged = {},
                    onDirectInputMode = {},
                )
            }
        }

        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        composeRule.onNodeWithContentDescription("Control modifier")
            .assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                    "Control modifier locked",
                ),
            )
        composeRule.onNodeWithText("🔒").assertIsDisplayed()
    }

    @Test
    fun localActionCallbackReceivesATypeThatCannotContainBytes() {
        var selected: TerminalAccessoryAction? = null
        val local = TerminalAccessoryAction.Local(
            com.yanjiyu.terminalspike.terminal.view.TerminalLocalAccessoryAction.PASTE,
        )
        composeRule.setContent {
            MaterialTheme {
                TerminalAccessoryBar(
                    actions = listOf(local),
                    modifiers = AccessoryModifierSnapshot(),
                    customizationEnabled = false,
                    inputTargetId = 7L,
                    bufferedInputSendEnabled = true,
                    bufferedInputDraftState = remember { BufferedInputDraftState() },
                    onAction = { selected = it },
                    onCustomize = {},
                    onSendBufferedInput = { _, _ -> true },
                    onBufferedInputModeChanged = {},
                    onDirectInputMode = {},
                )
            }
        }

        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        composeRule.onNodeWithContentDescription("Paste from clipboard").performClick()
        composeRule.runOnIdle { assertEquals(local, selected) }
    }
}
