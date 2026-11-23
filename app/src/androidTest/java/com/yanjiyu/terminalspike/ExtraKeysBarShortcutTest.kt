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
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.ui.resolve
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import com.yanjiyu.terminalspike.terminal.view.toAccessoryAction
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryAction
import com.yanjiyu.terminalspike.terminal.view.TerminalLocalAccessoryAction
import com.yanjiyu.terminalspike.terminal.view.AccessoryModifierSnapshot
import com.yanjiyu.terminalspike.ui.TerminalAccessoryBar
import com.yanjiyu.terminalspike.ui.BufferedInputDraftState
import com.yanjiyu.terminalspike.ui.ExtraKeysBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import com.yanjiyu.terminalspike.terminal.TerminalInputContext
import org.junit.Test

class ExtraKeysBarShortcutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ordinaryKeyLongPressReplacesWithoutSendingAndCanBeDismissed() {
        val received = mutableListOf<TerminalAccessoryAction>()
        val actions = androidx.compose.runtime.mutableStateOf(com.yanjiyu.terminalspike.ui.settings.defaultRuntimeAccessoryActions())
        var replacement: List<KeyboardAction>? = null
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    TerminalAccessoryBar(
                        actions = actions.value,
                        modifiers = AccessoryModifierSnapshot(),
                        customizationEnabled = true, inputTargetId = 11L,
                        bufferedInputSendEnabled = true,
                        bufferedInputDraftState = remember { BufferedInputDraftState() },
                        onAction = { received += it },
                        onReplaceActions = { _, updated ->
                            replacement = updated
                            actions.value = updated.map { requireNotNull(com.yanjiyu.terminalspike.ui.settings.toInputAccessoryAction(it)) }
                        },
                        onCustomize = {}, onSendBufferedInput = { _, _ -> true },
                        onBufferedInputModeChanged = {}, onDirectInputMode = {},
                    )
                }
            }
        }
        val escape = composeRule.onNodeWithContentDescription(description(TerminalExtraKey.ESC))
        escape.performTouchInput { down(center) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("keyboard-replacement-grid")).fetchSemanticsNodes().isNotEmpty()
        }
        escape.performTouchInput { up() }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(emptyList<TerminalAccessoryAction>(), received)
            assertEquals(null, replacement)
        }
        escape.performTouchInput { down(center) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("keyboard-replacement-grid")).fetchSemanticsNodes().isNotEmpty()
        }
        escape.performTouchInput { up() }
        composeRule.onNodeWithContentDescription("Replace with Tab").performClick()
        composeRule.runOnIdle {
            assertEquals(emptyList<TerminalAccessoryAction>(), received)
            assertEquals(KeyboardAction.TAB, replacement!![1])
        }
        composeRule.onNodeWithContentDescription(description(TerminalExtraKey.TAB)).performClick()
        composeRule.runOnIdle {
            assertEquals(TerminalExtraKey.TAB, (received.single() as TerminalAccessoryAction.Key).key)
        }
    }

    @Test
    fun aiAgentTapRunsAndLongPressEditsWithoutAlsoRunning() {
        var runs = 0
        var edits = 0
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    TerminalAccessoryBar(
                        actions = com.yanjiyu.terminalspike.ui.settings.defaultRuntimeAccessoryActions(),
                        modifiers = AccessoryModifierSnapshot(),
                        customizationEnabled = true, inputTargetId = 11L,
                        bufferedInputSendEnabled = true,
                        bufferedInputDraftState = remember { BufferedInputDraftState() },
                        onAction = { if ((it as? TerminalAccessoryAction.Local)?.action == TerminalLocalAccessoryAction.AI_AGENTS) runs++ },
                        onEditAiAgents = { edits++ },
                        onCustomize = {}, onSendBufferedInput = { _, _ -> true },
                        onBufferedInputModeChanged = {}, onDirectInputMode = {},
                    )
                }
            }
        }
        val agent = composeRule.onNodeWithContentDescription("AI Agents")
        agent.performClick()
        composeRule.runOnIdle { assertEquals(1, runs) }
        agent.performTouchInput { down(center) }
        composeRule.waitUntil(timeoutMillis = 5_000) { edits == 1 }
        agent.performTouchInput { up() }
        composeRule.runOnIdle { assertEquals(1, runs); assertEquals(1, edits) }
    }

    @Test
    fun expandedKeyboardReplacesControlWAndDispatchesTabAndF2WithoutClosing() {
        val received = mutableListOf<TerminalExtraKey>()
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    TestExtraKeysBar(keys = TerminalExtraKey.DEFAULT_ORDER, onKey = { received += it })
                }
            }
        }
        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        composeRule.onNodeWithContentDescription("Control W").assertDoesNotExist()
        val compactBefore = composeRule.onNodeWithTag("terminal_input_pager").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithContentDescription("Open expanded terminal keyboard").performClick()
        composeRule.onNodeWithText("Close expanded keyboard").assertDoesNotExist()
        val compactAfter = composeRule.onNodeWithTag("terminal_input_pager").fetchSemanticsNode().boundsInRoot
        assertEquals(compactBefore.width, compactAfter.width, 0.5f)
        assertEquals(compactBefore.height, compactAfter.height, 0.5f)
        composeRule.onNodeWithTag("terminal_expanded_keyboard").assertIsDisplayed().assertHeightIsAtLeast(260.dp)
        composeRule.onNodeWithContentDescription("Terminal key TAB").performClick()
        composeRule.onNodeWithContentDescription("Terminal key F2").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(TerminalExtraKey.TAB, TerminalExtraKey.F2), received)
        }
        composeRule.onNodeWithTag("terminal_expanded_keyboard").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open expanded terminal keyboard").performClick()
        composeRule.onNodeWithTag("terminal_expanded_keyboard").assertDoesNotExist()
    }

    @Test
    fun imageKeyTapSelectsPhotosAndLongPressSelectsFilesWithoutAlsoOpeningPhotos() {
        var photos = 0
        var files = 0
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    TerminalAccessoryBar(
                        actions = listOf(TerminalAccessoryAction.Local(TerminalLocalAccessoryAction.SELECT_IMAGES)),
                        modifiers = AccessoryModifierSnapshot(),
                        customizationEnabled = true,
                        inputTargetId = 11L,
                        bufferedInputSendEnabled = true,
                        bufferedInputDraftState = remember { BufferedInputDraftState() },
                        onAction = { photos++ },
                        onSelectFiles = { files++ },
                        onCustomize = {},
                        onSendBufferedInput = { _, _ -> true },
                        onBufferedInputModeChanged = {},
                        onDirectInputMode = {},
                    )
                }
            }
        }
        val image = composeRule.onNodeWithContentDescription("Select images and videos")
        image.performClick()
        composeRule.runOnIdle { assertEquals(1, photos); assertEquals(0, files) }
        image.performTouchInput { down(center) }
        composeRule.waitUntil(timeoutMillis = 5_000) { files == 1 }
        image.performTouchInput { up() }
        composeRule.runOnIdle { assertEquals(1, photos); assertEquals(1, files) }
        image.performClick()
        composeRule.runOnIdle { assertEquals(2, photos); assertEquals(1, files) }
    }

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
        composeRule.onNodeWithText("^W").assertDoesNotExist()
        composeRule.onNodeWithText("CTRL+C").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Control C").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Open expanded terminal keyboard").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Shift Left").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(TerminalExtraKey.SHIFT_LEFT, activated) }
        composeRule.onNodeWithContentDescription("Control C").performClick()
        composeRule.onNodeWithContentDescription("Terminal key PGUP").assertDoesNotExist()
        composeRule.onNodeWithTag("terminal_toggle_pinned_snippets").assertIsDisplayed()
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

    private fun description(key: TerminalExtraKey): String =
        if (key == TerminalExtraKey.CTRL_W) "Open expanded terminal keyboard"
        else key.toAccessoryAction().accessibilityDescription.resolve(
            InstrumentationRegistry.getInstrumentation().targetContext.resources,
        )

    /**
     * Default deck: Enter leads beside the typing toggle; Esc moves into the deck,
     * Backspace is replaced by the pinned-snippet toggle at the top-right, Ctrl takes
     * Enter's old slot and Shift-Left takes Ctrl's. Tab and Page Up stay off the deck.
     */
    private fun assertShortcutAreaIsNineByTwo() {
        val deckKeys = listOf(
            TerminalExtraKey.ESC, TerminalExtraKey.SLASH, TerminalExtraKey.AT, TerminalExtraKey.DOLLAR,
            TerminalExtraKey.HOME, TerminalExtraKey.UP, TerminalExtraKey.END, TerminalExtraKey.CTRL_B,
            TerminalExtraKey.SHIFT_LEFT, TerminalExtraKey.ALT, TerminalExtraKey.CTRL_C, TerminalExtraKey.CTRL_W,
            TerminalExtraKey.LEFT, TerminalExtraKey.DOWN, TerminalExtraKey.RIGHT, TerminalExtraKey.CTRL,
            TerminalExtraKey.HIDE_KEYBOARD,
        )
        val firstPageBounds = deckKeys.map { key ->
            composeRule.onNodeWithContentDescription(description(key))
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .fetchSemanticsNode()
                .boundsInRoot
        } + composeRule.onNodeWithTag("terminal_toggle_pinned_snippets")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithContentDescription(description(TerminalExtraKey.BACKSPACE)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(description(TerminalExtraKey.TAB)).assertDoesNotExist()
        val deckBounds = composeRule.onNodeWithTag("terminal_input_pager")
            .fetchSemanticsNode()
            .boundsInRoot
        val rows = firstPageBounds.groupBy { bounds -> bounds.top.toInt() }
        val toggleBounds = composeRule.onNodeWithTag("terminal_toggle_pinned_snippets")
            .fetchSemanticsNode().boundsInRoot
        // The snippet toggle is the top-right cell of the deck.
        assertEquals(firstPageBounds.minOf { it.top }, toggleBounds.top, 0.5f)
        assertEquals(firstPageBounds.maxOf { it.right }, toggleBounds.right, 0.5f)

        val leadingBounds = composeRule.onNodeWithContentDescription(description(TerminalExtraKey.ENTER))
            .fetchSemanticsNode().boundsInRoot
        val typingBounds = composeRule.onNodeWithTag("terminal_typing_toggle")
            .fetchSemanticsNode().boundsInRoot
        for (bounds in firstPageBounds + typingBounds) {
            assertEquals(leadingBounds.width, bounds.width, 1f)
            assertEquals(leadingBounds.height, bounds.height, 1f)
        }
        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        val activeTypingBounds = composeRule.onNodeWithTag("terminal_typing_toggle")
            .fetchSemanticsNode().boundsInRoot
        val enterBounds = composeRule.onNodeWithTag("buffered_input_enter")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(typingBounds, activeTypingBounds)
        assertEquals(leadingBounds, enterBounds)
        composeRule.onNodeWithTag("terminal_typing_toggle").performClick()
        assertEquals(
            typingBounds,
            composeRule.onNodeWithTag("terminal_typing_toggle").fetchSemanticsNode().boundsInRoot,
        )
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
        inputContext = TerminalInputContext("test-agent", agent = true),
        onKey = onKey,
        onCustomize = {},
        onSendBufferedInput = { _, _ -> true },
        onBufferedInputModeChanged = {},
        onDirectInputMode = {},
    )
}
