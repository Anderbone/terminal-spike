package com.yanjiyu.terminalspike

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.yanjiyu.terminalspike.ui.terminal.TerminalSnippetsPage
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.ui.terminal.TerminalSnippetPickerDialog
import com.yanjiyu.terminalspike.ui.terminal.TerminalSnippetPickerItemTestTagPrefix
import com.yanjiyu.terminalspike.ui.terminal.TerminalSnippetPickerTestTag
import com.yanjiyu.terminalspike.ui.theme.TerminalSpikeTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TerminalSnippetPickerDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun resetToolbarPreferences() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("snippet-toolbar", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun codexEffortMenuShowsConfirmedValueAndChangesThroughDropdown() {
        val agent = androidx.compose.runtime.mutableStateOf("codex")
        val effort = androidx.compose.runtime.mutableStateOf<String?>("medium")
        val ready = androidx.compose.runtime.mutableStateOf(true)
        val sent = mutableListOf<Long>()
        composeRule.setContent {
            TerminalSpikeTheme {
                TerminalSnippetsPage(
                    listOf(snippet(31, "Status", false)), true, true,
                    onSend = { sent += it }, onNewCodex = { error("Effort must not start Codex") },
                    onSave = { error("Effort must not create a snippet") }, onEditorClosed = {},
                    agentContextKey = "session-1", detectedAgentId = agent.value,
                    currentAgentEffort = effort.value, canSendAgentSnippets = ready.value,
                )
            }
        }
        composeRule.onNodeWithText("Status").assertIsDisplayed()
        composeRule.onNodeWithText("Low").assertDoesNotExist()
        composeRule.onNodeWithText("Medium ▾").performClick()
        composeRule.onNodeWithTag("agent_snippet_-104").assertExists()
        composeRule.onNodeWithTag("agent_snippet_-105").assertExists()
        composeRule.onNodeWithTag("agent_snippet_-106").assertExists()
        composeRule.onNodeWithTag("agent_snippet_-101").performClick()
        composeRule.runOnIdle { assertEquals(listOf(-101L), sent) }
        composeRule.onNodeWithText("Medium ▾").assertIsDisplayed()
        composeRule.runOnIdle { effort.value = "low" }
        composeRule.onNodeWithText("Low ▾").performClick()
        composeRule.onNodeWithTag("agent_snippet_-103").performClick()
        composeRule.runOnIdle { assertEquals(listOf(-101L, -103L), sent); ready.value = false }
        composeRule.onNodeWithTag("codex_effort_menu").assertIsNotEnabled()
        composeRule.onNodeWithText("Status").performClick()
        composeRule.runOnIdle { assertEquals(31L, sent.last()); ready.value = true; agent.value = "claude" }
        composeRule.onNodeWithTag("codex_effort_menu").assertDoesNotExist()
        composeRule.runOnIdle { agent.value = "codex" }
        composeRule.onNodeWithContentDescription("Manage snippets").performClick()
        composeRule.onNodeWithTag("codex_effort_menu").assertIsNotEnabled()
    }

    @Test
    fun effortControlStaysHiddenWithoutCodexAndNeverGuessesUnknownEffort() {
        val agent = androidx.compose.runtime.mutableStateOf("")
        val sent = mutableListOf<Long>()
        composeRule.setContent {
            TerminalSpikeTheme {
                TerminalSnippetsPage(emptyList(), true, true,
                    onSend = { sent += it }, onNewCodex = {}, onSave = { Result.success(Unit) },
                    onEditorClosed = {}, agentContextKey = "plain-ssh", detectedAgentId = agent.value)
            }
        }
        composeRule.onNodeWithTag("codex_effort_menu").assertDoesNotExist()
        composeRule.onNodeWithTag("snippet_group_menu").assertDoesNotExist()
        composeRule.runOnIdle { agent.value = "codex" }
        composeRule.onNodeWithText("Effort ▾").performClick()
        composeRule.onNodeWithTag("agent_snippet_-103").performClick()
        composeRule.runOnIdle { assertEquals(listOf(-103L), sent) }
        composeRule.onNodeWithText("Effort ▾").assertIsDisplayed()
        composeRule.runOnIdle { agent.value = "" }
        composeRule.onNodeWithTag("codex_effort_menu").assertDoesNotExist()
    }

    @Test
    fun builtInTextSnippetsCanBeEditedAndReplaced() {
        val saved = mutableListOf<Snippet>()
        composeRule.setContent {
            TerminalSpikeTheme {
                TerminalSnippetsPage(
                    com.yanjiyu.terminalspike.settings.DefaultTerminalSnippets.text, true, true,
                    onSend = { error("Editing must not send") }, onNewCodex = {},
                    onSave = { saved += it; Result.success(Unit) }, onEditorClosed = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Manage snippets").performClick()
        com.yanjiyu.terminalspike.settings.DefaultTerminalSnippets.text.forEach { original ->
            composeRule.onNodeWithText(original.label).performClick()
            composeRule.onNodeWithTag("snippet-editor-command")
                .assertTextContains(original.command)
                .performTextReplacement("edited ${original.command}")
            composeRule.onNodeWithTag("snippet-editor-save").performClick()
            composeRule.onNodeWithText(original.label).assertDoesNotExist()
            composeRule.runOnIdle {
                assertEquals(original.label, saved.last().name)
                assertEquals("edited ${original.command}", saved.last().command)
            }
        }
        composeRule.runOnIdle {
            val preferences = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .targetContext.getSharedPreferences("snippet-toolbar", android.content.Context.MODE_PRIVATE)
            assertEquals(setOf("-1", "-2"), preferences.getStringSet("replaced-defaults", emptySet()))
        }
    }

    @Test
    fun defaultCodexCanBeEditedAndReplaced() {
        var saved: Snippet? = null
        composeRule.setContent {
            TerminalSpikeTheme {
                TerminalSnippetsPage(emptyList(), true, true,
                    onSend = { error("Editing must not send") },
                    onNewCodex = { error("Editing must not start Codex") },
                    onSave = { saved = it; Result.success(Unit) }, onEditorClosed = {})
            }
        }
        composeRule.onNodeWithContentDescription("Manage snippets").performClick()
        composeRule.onNodeWithText("New Codex").performClick()
        composeRule.onNodeWithTag("snippet-editor-command")
            .assertTextContains(com.yanjiyu.terminalspike.settings.SnippetSequence.CODEX_EXAMPLE)
            .performTextReplacement("codex resume")
        composeRule.onNodeWithTag("snippet-editor-save").performClick()
        composeRule.runOnIdle { assertEquals("codex resume", saved?.command) }
        composeRule.onNodeWithText("New Codex").assertDoesNotExist()
    }

    @Test
    fun defaultCodexDeletionPersists() {
        composeRule.setContent {
            TerminalSpikeTheme {
                TerminalSnippetsPage(emptyList(), true, true,
                    onSend = {}, onNewCodex = {},
                    onSave = { error("Deleting must not save") }, onEditorClosed = {})
            }
        }
        composeRule.onNodeWithContentDescription("Manage snippets").performClick()
        composeRule.onNodeWithText("New Codex").performClick()
        composeRule.onNodeWithText("Delete", ignoreCase = true).performClick()
        composeRule.onNodeWithText("Delete snippet").performClick()
        composeRule.onNodeWithText("New Codex").assertDoesNotExist()
        composeRule.runOnIdle {
            val preferences = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .targetContext.getSharedPreferences("snippet-toolbar", android.content.Context.MODE_PRIVATE)
            assertEquals(true, preferences.getBoolean("codex-replaced", false))
        }
    }

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

    @Test
    fun accessoryPageAddsPlainTextWithoutSendingIt() {
        var saved: Snippet? = null
        composeRule.setContent {
            TerminalSpikeTheme {
                TerminalSnippetsPage(emptyList(), true, true,
                    onSend = { error("Saving must not execute a snippet") },
                    onNewCodex = { error("Adding a snippet must not start Codex") },
                    onSave = { saved = it; Result.success(Unit) }, onEditorClosed = {})
            }
        }
        composeRule.onNodeWithContentDescription("+ Add snippet").performClick()
        composeRule.onNodeWithTag("snippet-editor-name").performTextInput("Greeting")
        composeRule.onNodeWithTag("snippet-editor-command").performTextInput("hello")
        composeRule.onNodeWithTag("snippet-editor-save").performClick()
        composeRule.runOnIdle {
            assertEquals("hello", saved?.command)
            assertEquals(false, saved?.appendEnter)
        }
    }

    @Test
    fun accessoryPageRunsNewCodexWithoutOpeningEditor() {
        var starts = 0
        composeRule.setContent {
            TerminalSpikeTheme {
                TerminalSnippetsPage(emptyList(), false, true,
                    onSend = { error("New Codex must not select a saved snippet") },
                    onNewCodex = { starts++ },
                    onSave = { error("New Codex must not save a snippet") }, onEditorClosed = {})
            }
        }
        composeRule.onNodeWithText("New Codex").performClick()
        composeRule.onNodeWithTag("snippet-editor-save").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun accessoryManagementEditsWithoutSendingAndDoneRestoresActions() {
        val original = editableSnippet()
        var saved: Snippet? = null
        var sends = 0
        var starts = 0
        composeRule.setContent {
            TerminalSpikeTheme {
                TerminalSnippetsPage(
                    listOf(snippet(31, "Status", false)), true, true,
                    onSend = { sends++ }, onNewCodex = { starts++ },
                    onSave = { saved = it; Result.success(Unit) }, onEditorClosed = {},
                    snippetForEditing = { if (it == 31L) original else null },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Manage snippets").performClick()
        composeRule.onNodeWithText("Status").performTouchInput { click() }
        composeRule.onNodeWithTag("snippet-editor-name").assertTextContains("Status")
        composeRule.onNodeWithTag("snippet-editor-command").assertTextContains(original.command)
            .performTextReplacement("pwd")
        composeRule.onNodeWithTag("snippet-editor-save").performClick()
        composeRule.runOnIdle {
            assertEquals(original.id, saved?.id)
            assertEquals("pwd", saved?.command)
            assertEquals(0, sends)
            assertEquals(0, starts)
        }
        composeRule.onNodeWithContentDescription("Finish managing snippets").performClick()
        composeRule.onNodeWithText("Status").performTouchInput { click() }
        composeRule.onNodeWithText("New Codex").performTouchInput { click() }
        composeRule.onNodeWithTag("snippet-editor-dialog").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, sends)
            assertEquals(1, starts)
        }
    }

    @Test
    fun accessoryManagementCanEditWithoutTerminalInput() {
        composeRule.setContent {
            TerminalSpikeTheme {
                TerminalSnippetsPage(
                    listOf(snippet(31, "Status", false)), true, false,
                    onSend = { error("Management must not send") },
                    onNewCodex = { error("Management must not start Codex") },
                    onSave = { Result.success(Unit) }, onEditorClosed = {},
                    snippetForEditing = { editableSnippet() },
                )
            }
        }
        composeRule.onNodeWithText("Status").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Manage snippets").performClick()
        composeRule.onNodeWithText("Status").performTouchInput { click() }
        composeRule.onNodeWithTag("snippet-editor-name").assertTextContains("Status")
    }

    @Test
    fun accessoryDoubleTapUsesNormalActionsWithoutOpeningEditor() {
        var sends = 0
        var starts = 0
        composeRule.setContent {
            TerminalSpikeTheme {
                TerminalSnippetsPage(
                    listOf(snippet(31, "Status", false)), true, true,
                    onSend = { sends++ }, onNewCodex = { starts++ },
                    onSave = { error("Normal taps must not save") }, onEditorClosed = {},
                    snippetForEditing = { editableSnippet() },
                )
            }
        }
        composeRule.onNodeWithText("Status").performTouchInput { doubleClick() }
        composeRule.onNodeWithText("New Codex").performTouchInput { doubleClick() }
        composeRule.onNodeWithTag("snippet-editor-dialog").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(2, sends)
            assertEquals(2, starts)
        }
    }

    @Test
    fun accessoryDragPersistsOrderWithoutSending() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("snippet-toolbar", android.content.Context.MODE_PRIVATE)
        val previous = preferences.getString("order", null)
        preferences.edit().remove("order").commit()
        try {
            composeRule.setContent {
                TerminalSpikeTheme {
                    TerminalSnippetsPage(
                        listOf(snippet(31, "First", false), snippet(32, "Second", false)),
                        true, true,
                        onSend = { error("Dragging must not send") },
                        onNewCodex = { error("Dragging must not start Codex") },
                        onSave = { Result.success(Unit) }, onEditorClosed = {},
                    )
                }
            }
            composeRule.onNodeWithContentDescription("Manage snippets").performClick()
            val first = composeRule.onNodeWithText("First").fetchSemanticsNode().boundsInRoot
            val second = composeRule.onNodeWithText("Second").fetchSemanticsNode().boundsInRoot
            composeRule.onNodeWithText("First").performTouchInput {
                down(center)
                advanceEventTime(700)
                moveTo(center + (second.center - first.center), delayMillis = 200)
                up()
            }
            composeRule.runOnIdle {
                assertEquals("32,31,0", preferences.getString("order", null))
            }
            val movedFirst = composeRule.onNodeWithText("First").fetchSemanticsNode().boundsInRoot
            val movedSecond = composeRule.onNodeWithText("Second").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(movedSecond.left < movedFirst.left)
            val codex = composeRule.onNodeWithText("New Codex").fetchSemanticsNode().boundsInRoot
            composeRule.onNodeWithText("New Codex").performTouchInput {
                down(center)
                advanceEventTime(700)
                moveTo(center + (movedFirst.center - codex.center), delayMillis = 200)
                up()
            }
            composeRule.runOnIdle {
                assertEquals("32,0,31", preferences.getString("order", null))
            }
        } finally {
            preferences.edit().putString("order", previous).commit()
        }
    }

    private fun editableSnippet() = Snippet(
        id = "d7e6f0dd-23b1-4c36-b252-d03387137bdd",
        name = "Status", command = "git status", createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
    )

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
