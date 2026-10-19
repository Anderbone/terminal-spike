package com.yanjiyu.terminalspike.ui.project

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.yanjiyu.terminalspike.connection.RemoteProjectLocation
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.connection.TmuxCommandRunner
import com.yanjiyu.terminalspike.connection.TmuxExecOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProjectScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val draftStore = TestProjectDraftStore()
    private val original = (1..250).joinToString("\n", postfix = "\n") { "val row$it = $it" }
    private var reportedLocations = emptyList<RemoteProjectLocation>()
    private var content = original.replace("val row70 = 70", "val row70 = 700")
    private val runner = TmuxCommandRunner { command ->
        val response = when {
            command.contains("rev-parse --show-toplevel") -> if (command.contains("/other")) "/other\n" else "/repo\n"
            command.contains("rev-parse --verify") -> "a".repeat(40) + "\n"
            command.contains("symbolic-ref") -> "main\n"
            command.contains("status --") -> " M src/Main.kt\u0000"
            command.contains("ls-files") -> "src/Main.kt\u0000README.md\u0000"
            command.contains("diff --") -> "@@ -70 +70 @@\n-val row70 = 70\n+val row70 = 700\n"
            command.contains("p=\$(realpath") -> content
            else -> error("Unexpected read")
        }
        TmuxExecOutput(response.toByteArray(), 0)
    }
    private val controller = ProjectController(scope, { id -> check(id == 42L); runner }, draftStore, locationsFor = { reportedLocations })

    @After fun close() { controller.close(); scope.cancel() }

    private fun open(automatic: Boolean = false) {
        composeRule.runOnIdle { controller.open(42, "Agent host", "endpoint"); if (!automatic) controller.load("/repo") }
        composeRule.setContent {
            MaterialTheme { ProjectScreen(controller, { _, _ -> false }, {}) }
        }
        composeRule.waitUntil(10_000) { controller.state.value.snapshot != null && !controller.state.value.busy }
    }

    @Test fun fullFileJumpsToChangesAndReviewKeepsCodeBeforeAgentEdits() {
        open()
        composeRule.onNodeWithText("Main.kt").performClick()
        composeRule.waitUntil(10_000) { controller.state.value.document != null && !controller.state.value.busy }
        composeRule.onNodeWithText("val row70 = 700").assertIsDisplayed()
        composeRule.onNodeWithText("val row71 = 71").assertIsDisplayed()
        composeRule.onNodeWithText("val row70 = 70").assertIsDisplayed()
        composeRule.onNodeWithText("+70").performClick()
        composeRule.onNodeWithText("Comment on 70–70").performClick()
        composeRule.onNodeWithTag("project-feedback-input").performTextInput("Use the original value")
        composeRule.onNodeWithText("Save draft").performClick()
        composeRule.runOnIdle {
            assertEquals("val row70 = 700", controller.state.value.feedback.single().code)
            content = content.replace("700", "701")
            controller.openFile("src/Main.kt")
        }
        composeRule.waitUntil(10_000) { !controller.state.value.busy && controller.state.value.document?.lines?.any { it.text == "val row70 = 701" } == true }
        composeRule.onNodeWithText("File changed since this comment. The original code is retained in feedback.").assertIsDisplayed()
        composeRule.onNodeWithText("Feedback 1").performClick()
        composeRule.onNodeWithText("Use the original value").assertIsDisplayed()
        composeRule.onNodeWithText("Paste to terminal").performClick()
        composeRule.onNodeWithText("Open an agent input that supports bracketed paste, or copy the message. The review was not pasted.").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, draftStore.feedback.size) }
    }

    @Test fun discoversHerdrProjectsAndKeepsIndependentTabsForTheSameHost() {
        reportedLocations = listOf(RemoteProjectLocation("/repo/subfolder", "Workspace one", true),
            RemoteProjectLocation("/other", "Workspace two"))
        open(automatic = true)
        val first = controller.state.value.tabId
        composeRule.onNodeWithText("Main.kt").performClick()
        composeRule.waitUntil(10_000) { controller.state.value.document != null && !controller.state.value.busy }
        composeRule.onNodeWithText("val row71 = 71").assertIsDisplayed()
        composeRule.onNodeWithTag("project-code-list").performScrollToIndex(160)
        composeRule.onNodeWithText("val row160 = 160").assertIsDisplayed()
        composeRule.onNodeWithTag("project-add-tab").performClick()
        composeRule.waitUntil(10_000) { !controller.state.value.busy }
        composeRule.onNodeWithText("/other").performClick()
        composeRule.waitUntil(10_000) { controller.state.value.snapshot?.root == "/other" }
        composeRule.runOnIdle { assertEquals(2, controller.tabs.value.size) }
        composeRule.onNodeWithTag("project-tab-$first").performClick()
        composeRule.runOnIdle {
            assertEquals(first, controller.state.value.tabId)
            assertEquals(2, controller.tabs.value.size)
        }
        composeRule.onNodeWithText("val row160 = 160").assertIsDisplayed()
        composeRule.onNodeWithText("val row71 = 71").assertIsNotDisplayed()
        composeRule.runOnIdle { assertEquals("src/Main.kt", controller.state.value.document?.path) }
        composeRule.onNodeWithContentDescription("Close project tab /other").performClick()
        composeRule.runOnIdle { assertEquals(1, controller.tabs.value.size) }
        composeRule.onNodeWithText("val row160 = 160").assertIsDisplayed()
    }

    @Test fun changedFilesAreGroupedAndSourceRowsUseCompactSpacing() {
        open()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        composeRule.onNodeWithTag("project-folder:src").assertIsDisplayed()
        val fileHeight = composeRule.onNodeWithTag("project-file:src/Main.kt").fetchSemanticsNode().boundsInRoot.height / density
        assertTrue("File row should be readable and touchable, was $fileHeight dp", fileHeight in 40f..44f)
        composeRule.onNodeWithText("Main.kt").performClick()
        composeRule.waitUntil(10_000) { controller.state.value.document != null && !controller.state.value.busy }
        val first = composeRule.onNodeWithText("val row71 = 71").fetchSemanticsNode().boundsInRoot.top
        val second = composeRule.onNodeWithText("val row72 = 72").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Source rows should be at most 19 dp apart", (second - first) / density <= 19f)
    }

    @Test fun veryLongSourceLinesStayWrappedWithinAndroidLayoutLimits() {
        content = original.replace("val row70 = 70", "x".repeat(16_000))
        open()
        composeRule.onNodeWithText("Main.kt").performClick()
        composeRule.waitUntil(10_000) { controller.state.value.document != null && !controller.state.value.busy }
        composeRule.onNodeWithTag("project-code").assertIsDisplayed()
        composeRule.onNodeWithTag("project-wrap-toggle").assertIsOn().assertIsNotEnabled()
    }

    @Test fun repositoryFilesBrowseFoldersAndOpenFileWithoutTerminalInput() {
        open()
        composeRule.onNodeWithTag("project-files-tab").performClick()
        composeRule.onNodeWithTag("project-folder:src").performClick()
        composeRule.onNodeWithText("Main.kt").performClick()
        composeRule.waitUntil(10_000) { controller.state.value.document != null && !controller.state.value.busy }
        composeRule.onNodeWithTag("project-code").assertIsDisplayed()
        composeRule.runOnIdle { controller.closeFile() }
        composeRule.onNodeWithTag("project-folder:src").assertIsDisplayed()
        composeRule.onNodeWithText("Main.kt").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(controller.state.value.feedback.isEmpty()) }
    }
}

private class TestProjectDraftStore : ProjectDraftStore {
    private val drafts = mutableMapOf<Pair<String, String>, List<ProjectFeedback>>()
    val feedback: List<ProjectFeedback> get() = drafts.values.flatten()
    override fun directory(endpoint: String) = ""
    override fun rememberDirectory(endpoint: String, directory: String) = Unit
    override fun load(endpoint: String, root: String) = drafts[endpoint to root].orEmpty()
    override fun save(endpoint: String, root: String, feedback: List<ProjectFeedback>) { drafts[endpoint to root] = feedback }
}
