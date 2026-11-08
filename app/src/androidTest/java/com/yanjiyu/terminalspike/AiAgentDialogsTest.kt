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
    fun pickerDisplaysNamesAndCommandsAndDispatchesOnlySelectedId() {
        var selected: String? = null
        var edits = 0
        composeRule.setContent {
            MaterialTheme {
                AiAgentPickerDialog(AiAgents(), onSelect = { selected = it }, onEdit = { edits++ }, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Codex").assertIsDisplayed()
        composeRule.onNodeWithText("codex --yolo").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_agent_run_pi").performClick()
        composeRule.runOnIdle { assertEquals("pi", selected); assertEquals(0, edits) }
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
