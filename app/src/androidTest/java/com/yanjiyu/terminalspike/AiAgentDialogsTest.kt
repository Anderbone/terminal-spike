package com.yanjiyu.terminalspike

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.yanjiyu.terminalspike.settings.AiAgents
import com.yanjiyu.terminalspike.ui.terminal.AiAgentEditorDialog
import com.yanjiyu.terminalspike.ui.terminal.AiAgentPickerDialog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class AiAgentDialogsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun pickerSavesModelEffortAndDefaultWithoutLaunching() {
        var saved: AiAgents? = null
        var edits = 0
        var saves = 0
        val agents = mutableStateOf(AiAgents())
        val show = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                if (show.value) AiAgentPickerDialog(agents.value, onSave = { draft ->
                    ++saves
                    if (saves == 1) false else {
                        saved = draft
                        agents.value = draft
                        true
                    }
                }, onEdit = { edits++ }, onDismiss = { show.value = false })
            }
        }
        composeRule.onNodeWithText("Codex").assertIsDisplayed()
        composeRule.onNodeWithText("codex --yolo").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_agent_model_menu").performClick()
        composeRule.onNodeWithTag("ai_agent_model_option_gpt-6.1-sol").performClick()
        composeRule.onNodeWithTag("ai_agent_effort_menu").performClick()
        composeRule.onNodeWithTag("ai_agent_effort_option_high").performClick()
        composeRule.onNodeWithTag("ai_agent_picker_default").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_agent_run_claude").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_agent_model_menu").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_agent_model_option_haiku").performClick()
        composeRule.onNodeWithTag("ai_agent_effort_menu").performClick()
        composeRule.onNodeWithTag("ai_agent_effort_option_low").performClick()
        composeRule.onNodeWithTag("ai_agent_run_pi").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(0, saves); assertEquals(0, edits) }
        composeRule.onNodeWithTag("ai_agent_model_menu").assertDoesNotExist()
        composeRule.onNodeWithTag("ai_agent_run_codex").performScrollTo().performClick()
        composeRule.onNodeWithTag("ai_agent_command_preview").performScrollTo()
        composeRule.onNodeWithText("codex --yolo --model 'gpt-6.1-sol' -c 'model_reasoning_effort=\"high\"'")
            .assertIsDisplayed()
        val screenshot = composeRule.onNodeWithTag("ai_agent_picker").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ai-agent-launcher.png")
            .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        composeRule.onNodeWithTag("ai_agent_picker_save").performClick()
        composeRule.onNodeWithText("Could not save. Try again.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_agent_picker_save").performClick()
        composeRule.runOnIdle {
            assertEquals(2, saves)
            assertFalse(show.value)
            assertEquals("gpt-6.1-sol", saved?.defaultAgent?.model)
            assertEquals("high", saved?.defaultAgent?.effort)
            assertEquals("haiku", saved?.entries?.first { it.id == "claude" }?.model)
            assertEquals("low", saved?.entries?.first { it.id == "claude" }?.effort)
            show.value = true
        }
        composeRule.onNodeWithText("Launch").assertDoesNotExist()
        composeRule.onNodeWithText("codex --yolo --model 'gpt-6.1-sol' -c 'model_reasoning_effort=\"high\"'")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Edit AI Agents").performClick()
        composeRule.runOnIdle { assertEquals(1, edits) }
    }

    @Test
    fun editorSavesEditedCommandAndDefaultThenCanRemoveDefaultAndSaveEmptyList() {
        val agents = mutableStateOf(AiAgents())
        val show = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                if (show.value) AiAgentEditorDialog(agents.value,
                    onSave = { agents.value = it; true }, onDismiss = { show.value = false })
            }
        }
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val panelHeight = composeRule.onNodeWithTag("ai_agent_editor").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Four agents should fit in a compact dialog", panelHeight <= 480f * context.resources.displayMetrics.density)
        val screenshot = composeRule.onNodeWithTag("ai_agent_editor").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ai-agents-compact.png")
            .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        for (text in listOf("OpenCode", "codex --yolo")) {
            composeRule.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    action(layouts)
                    assertFalse("Default entry must not be ellipsized: $text", layouts.single().isLineEllipsized(0))
                }
        }
        composeRule.onNodeWithTag("ai_agent_open_codex").performClick()
        composeRule.onNodeWithTag("ai_agent_command_codex").performTextReplacement("codex --resume")
        composeRule.onNodeWithTag("ai_agent_default_codex").performScrollTo().performClick()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle {
            assertEquals("codex --resume", agents.value.defaultAgent?.command)
            show.value = true
        }
        composeRule.onNodeWithTag("ai_agent_no_default").performClick()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle { assertNull(agents.value.defaultAgent); show.value = true }
        for (name in listOf("Codex", "Claude", "pi", "OpenCode")) {
            composeRule.onNodeWithContentDescription("Delete $name").performScrollTo().performClick()
        }
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle { assertEquals(emptyList<Any>(), agents.value.entries); show.value = true }
        composeRule.onNodeWithTag("ai_agent_add").performScrollTo().performClick()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextReplacement("My agent")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextReplacement("my-agent --resume")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle {
            assertEquals("My agent", agents.value.entries.single().name)
            assertEquals("my-agent --resume", agents.value.entries.single().command)
            assertNull(agents.value.defaultAgent)
        }
    }
}
