package com.yanjiyu.terminalspike

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.ui.terminal.TerminalSnippetPickerDialog
import com.yanjiyu.terminalspike.ui.terminal.TerminalSnippetPickerItemTestTagPrefix
import com.yanjiyu.terminalspike.ui.terminal.TerminalSnippetPickerTestTag
import com.yanjiyu.terminalspike.ui.theme.TerminalSpikeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TerminalSnippetPickerDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listsExistingSnippetsAndReturnsSelectedSnippet() {
        var selectedId: Long? = null
        composeRule.setContent {
            TerminalSpikeTheme {
                TerminalSnippetPickerDialog(
                    sessionTitle = "Production",
                    snippets = listOf(
                        snippet(id = 3, label = "Status", sendsImmediately = false),
                        snippet(id = 4, label = "Deploy", sendsImmediately = true),
                    ),
                    onDismiss = {},
                    onSelect = { selectedId = it },
                )
            }
        }

        composeRule.onNodeWithTag(TerminalSnippetPickerTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Choose a snippet for Production.").assertIsDisplayed()
        composeRule.onNodeWithText("Insert into terminal").assertIsDisplayed()
        composeRule.onNodeWithText("Run now").assertIsDisplayed()
        composeRule.onNodeWithTag(TerminalSnippetPickerItemTestTagPrefix + 3).performClick()

        composeRule.runOnIdle { assertEquals(3L, selectedId) }
    }

    @Test
    fun emptyPickerStaysAQuickDialog() {
        composeRule.setContent {
            TerminalSpikeTheme {
                TerminalSnippetPickerDialog(
                    sessionTitle = "Terminal",
                    snippets = emptyList(),
                    onDismiss = {},
                    onSelect = {},
                )
            }
        }

        composeRule.onNodeWithText("No snippets yet").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    private fun snippet(
        id: Long,
        label: String,
        sendsImmediately: Boolean,
    ) = CommandSnippet(
        id = id,
        label = label,
        command = "printf '$label'",
        appendEnter = sendsImmediately,
        sendsImmediately = sendsImmediately,
    )
}
