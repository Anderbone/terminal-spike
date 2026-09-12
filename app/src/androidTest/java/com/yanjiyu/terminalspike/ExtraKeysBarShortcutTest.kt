package com.yanjiyu.terminalspike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.ui.resolve
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import com.yanjiyu.terminalspike.ui.BufferedInputDraftState
import com.yanjiyu.terminalspike.ui.ExtraKeysBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ExtraKeysBarShortcutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactDeckShowsDirectControlChordsWithFullAccessibilityAtLargeFontScale() {
        var activated: TerminalExtraKey? = null

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        TestExtraKeysBar(
                            keys = TerminalExtraKey.DEFAULT_ORDER,
                            onKey = { activated = it },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        composeRule.onNodeWithText("^C").assertIsDisplayed()
        composeRule.onNodeWithText("^W").assertIsDisplayed()
        composeRule.onNodeWithText("CTRL+C").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Control C").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Control W").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Control modifier").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Terminal key PGUP").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hide software keyboard").assertIsDisplayed()

        composeRule.runOnIdle { assertEquals(TerminalExtraKey.CTRL_C, activated) }
        assertShortcutAreaIsNineByTwo()
    }

    @Test
    fun customDeckKeepsAdditionalKeysAndCustomizeActionOnASecondPage() {
        val customKeys = TerminalExtraKey.DEFAULT_ORDER + listOf(
            TerminalExtraKey.F1,
            TerminalExtraKey.F2,
            TerminalExtraKey.F3,
            TerminalExtraKey.F4,
            TerminalExtraKey.F5,
        )
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    TestExtraKeysBar(keys = customKeys)
                }
            }
        }

        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        composeRule.onNodeWithTag("terminal_input_pager").performTouchInput { swipeLeft() }

        composeRule.onNodeWithContentDescription("Terminal key F1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Terminal key F5").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Customize terminal keys").assertIsDisplayed()
    }

    @Test
    fun phoneWidthDeckKeepsNineUnclippedShortcutsBesideTheFixedControls() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    TestExtraKeysBar(keys = TerminalExtraKey.DEFAULT_ORDER)
                }
            }
        }

        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        assertShortcutAreaIsNineByTwo()
    }

    private fun assertShortcutAreaIsNineByTwo() {
        val firstPageBounds = TerminalExtraKey.DEFAULT_ORDER.filterNot { it == TerminalExtraKey.ESC || it == TerminalExtraKey.TAB }.map { key ->
            composeRule.onNodeWithContentDescription(
                key.accessibilityDescription.resolve(
                    InstrumentationRegistry.getInstrumentation().targetContext.resources,
                ),
            )
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .fetchSemanticsNode()
                .boundsInRoot
        }
        val deckBounds = composeRule.onNodeWithTag("terminal_input_pager")
            .fetchSemanticsNode()
            .boundsInRoot
        val rows = firstPageBounds.groupBy { bounds -> bounds.top.toInt() }

        assertEquals(listOf(9, 9), rows.values.map { it.size }.sorted())
        assertTrue(
            firstPageBounds.all { bounds ->
                bounds.left >= deckBounds.left && bounds.right <= deckBounds.right
            },
        )
    }
}

@Composable
private fun TestExtraKeysBar(
    keys: List<TerminalExtraKey>,
    onKey: (TerminalExtraKey) -> Unit = {},
) {
    val draftState = remember { BufferedInputDraftState() }
    ExtraKeysBar(
        keys = keys,
        ctrlArmed = false,
        altArmed = false,
        customizationEnabled = true,
        inputTargetId = 11L,
        bufferedInputSendEnabled = true,
        bufferedInputDraftState = draftState,
        onKey = onKey,
        onCustomize = {},
        onSendBufferedInput = { _, _ -> true },
        onBufferedInputModeChanged = {},
        onDirectInputMode = {},
    )
}
