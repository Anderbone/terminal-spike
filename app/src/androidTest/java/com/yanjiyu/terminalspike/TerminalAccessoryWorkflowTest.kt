package com.yanjiyu.terminalspike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TerminalAccessoryWorkflowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rawAndTextLabelsAreHiddenAndDeckUsesAFullSizeCollapseHandle() {
        composeRule.setContent {
            val density = LocalDensity.current
            var collapsed by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        TerminalAccessoryBar(
                            actions = TerminalExtraKey.DEFAULT_ORDER.map { it.toAccessoryAction() },
                            modifiers = AccessoryModifierSnapshot(),
                            layout = KeyboardLayout.TWO_ROWS,
                            inputMode = TerminalInputMode.RAW,
                            collapsed = collapsed,
                            customizationEnabled = true,
                            inputTargetId = 7L,
                            bufferedInputSendEnabled = true,
                            bufferedInputDraftState = remember { BufferedInputDraftState() },
                            onAction = {},
                            onCustomize = {},
                            onSendBufferedInput = { _, _ -> true },
                            onBufferedInputModeChanged = {},
                            onDirectInputMode = {},
                            onCollapsedChange = { collapsed = it },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Raw").assertDoesNotExist()
        composeRule.onNodeWithText("Text").assertDoesNotExist()
        composeRule.onNodeWithTag("terminal_accessory_collapse").performClick()

        val expandBounds = composeRule.onNodeWithTag("terminal_accessory_expand")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(expandBounds.height >= 48.dp.value)
        composeRule.onNodeWithText("Show terminal keys").performClick()
        composeRule.onNodeWithTag("terminal_input_pager").assertIsDisplayed()
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
                    collapsed = false,
                    customizationEnabled = false,
                    inputTargetId = 7L,
                    bufferedInputSendEnabled = true,
                    bufferedInputDraftState = remember { BufferedInputDraftState() },
                    onAction = {},
                    onCustomize = {},
                    onSendBufferedInput = { _, _ -> true },
                    onBufferedInputModeChanged = {},
                    onDirectInputMode = {},
                    onCollapsedChange = {},
                )
            }
        }

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
                    collapsed = false,
                    customizationEnabled = false,
                    inputTargetId = 7L,
                    bufferedInputSendEnabled = true,
                    bufferedInputDraftState = remember { BufferedInputDraftState() },
                    onAction = { selected = it },
                    onCustomize = {},
                    onSendBufferedInput = { _, _ -> true },
                    onBufferedInputModeChanged = {},
                    onDirectInputMode = {},
                    onCollapsedChange = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Paste from clipboard").performClick()
        composeRule.runOnIdle { assertEquals(local, selected) }
    }
}
