package com.yanjiyu.terminalspike.ui.guide

import android.content.ClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.TerminalSpikeComponentTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class QuickStartGuideTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TerminalSpikeComponentTestActivity>()

    @Test
    fun realRecordingsDecodeAndPauseForEveryLesson() {
        val open = mutableStateOf(true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme { if (open.value) QuickStartGuide(onDismiss = { open.value = false }) }
        }
        GuideLesson.entries.forEachIndexed { page, lesson ->
            composeRule.onNodeWithTag("guide-title-$page").assertIsDisplayed()
            if (lesson.visual.video != null) {
                if (composeRule.onAllNodesWithContentDescription("Play recording").fetchSemanticsNodes().isNotEmpty()) {
                    composeRule.onNodeWithContentDescription("Play recording").performClick()
                }
                composeRule.waitUntil(15_000) {
                    composeRule.onAllNodesWithTag("guide-recording-ready").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithContentDescription("Pause recording").performClick()
                composeRule.onNodeWithContentDescription("Play recording").assertIsDisplayed().performClick()
            } else {
                composeRule.onNodeWithContentDescription(context.getString(lesson.title)).assertIsDisplayed()
                composeRule.onNodeWithTag("guide-animation-toggle").assertDoesNotExist()
            }
            if (page < GuideLesson.entries.lastIndex) composeRule.onNodeWithTag("guide-next").performClick()
        }
        composeRule.onNodeWithTag("guide-next").performClick()
        composeRule.runOnIdle { assertFalse(open.value) }
    }

    @Test
    fun mergedGuideSupportsSwipesAndButtonsAcrossBasicsAndTips() {
        composeRule.setContent { MaterialTheme { UserGuideIndex() } }
        composeRule.onNodeWithText("Quick tips").assertDoesNotExist()
        openLesson(GuideLesson.PROJECTS)
        composeRule.onNodeWithText("3 / 12").assertIsDisplayed()
        composeRule.onNodeWithTag("guide-next").performClick()
        composeRule.onNodeWithTag("guide-title-3").assertIsDisplayed()
        composeRule.onNodeWithTag("guide-pager").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("guide-title-4").assertIsDisplayed()
        composeRule.onNodeWithTag("guide-pager").performTouchInput { swipeRight() }
        composeRule.onNodeWithTag("guide-title-3").assertIsDisplayed()
        composeRule.onNodeWithTag("guide-back").performClick()
        composeRule.onNodeWithTag("guide-title-2").assertIsDisplayed()
        repeat(9) { composeRule.onNodeWithTag("guide-next").performClick() }
        composeRule.onNodeWithText("12 / 12").assertIsDisplayed()
        composeRule.onNodeWithTag("guide-next").performClick()
        composeRule.onNodeWithTag("user-guide-index").assertIsDisplayed()
    }

    private fun openLesson(lesson: GuideLesson) {
        val tag = "guide-lesson-${lesson.name}"
        composeRule.onNodeWithTag("user-guide-index").performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performClick()
    }

    @Test
    fun quickReferenceExplainsAttachmentGesturesAndDraftRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { MaterialTheme { GuideReferenceScreen(onBack = {}) } }
        openReference(GuideReferenceTopic.ATTACHMENTS)
        listOf(R.string.guide_attachment_photos, R.string.guide_attachment_files,
            R.string.guide_attachment_formats).forEach { showReferenceText(context.getString(it)) }
        openReference(GuideReferenceTopic.DRAFTS)
        composeRule.onNodeWithText(context.getString(R.string.guide_attachment_files)).assertDoesNotExist()
        showReferenceText(context.getString(R.string.guide_draft_undo))
        showReferenceText(context.getString(R.string.guide_draft_send))
    }

    @Test
    fun herdrSetupIncludesCopyableSettingsAndCompatibilityNotes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { MaterialTheme { GuideReferenceScreen(onBack = {}) } }
        fun copyGuideText(tip: Int) {
            val tag = "guide-reference-copy-$tip"
            composeRule.onNodeWithTag("user-guide-index").performScrollToNode(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        }
        openReference(GuideReferenceTopic.HERDR)
        showReferenceText(context.getString(R.string.guide_herdr_location))
        copyGuideText(R.string.guide_herdr_basic_config)
        composeRule.runOnIdle {
            val copied = context.getSystemService(ClipboardManager::class.java).primaryClip
                ?.getItemAt(0)?.coerceToText(context)?.toString()
            assertEquals("[ui]\nmouse_capture = true\nmobile_width_threshold = 64\nstatus_indicators = \"dots\"", copied)
        }
        showReferenceText(context.getString(R.string.guide_herdr_compatibility))
        copyGuideText(R.string.guide_herdr_grouped_config)
        composeRule.runOnIdle {
            val copied = context.getSystemService(ClipboardManager::class.java).primaryClip
                ?.getItemAt(0)?.coerceToText(context)?.toString()
            assertEquals("grouped_sidebar = true", copied)
        }
        showReferenceText(context.getString(R.string.guide_herdr_reattach))
        copyGuideText(R.string.guide_herdr_agent_prompt)
        composeRule.runOnIdle {
            val copied = context.getSystemService(ClipboardManager::class.java).primaryClip
                ?.getItemAt(0)?.coerceToText(context)?.toString()
            assertEquals(context.getString(R.string.guide_herdr_agent_prompt), copied)
        }
    }

    @Test
    fun computerSetupExplainsBothDevicesAndOffersACopyableServeCommand() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { MaterialTheme { GuideReferenceScreen(onBack = {}) } }
        openReference(GuideReferenceTopic.TAILSCALE)
        showReferenceText(context.getString(R.string.guide_tailscale_computer))
        showReferenceText(context.getString(R.string.guide_tailscale_phone))
        openReference(GuideReferenceTopic.CODE_SERVER)
        showReferenceText(context.getString(R.string.guide_code_server_computer))
        val tag = "guide-reference-copy-${R.string.guide_code_server_serve}"
        composeRule.onNodeWithTag("user-guide-index").performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.runOnIdle {
            val copied = context.getSystemService(ClipboardManager::class.java).primaryClip
                ?.getItemAt(0)?.coerceToText(context)?.toString()
            assertEquals("tailscale serve --bg --https=9443 http://127.0.0.1:8080", copied)
        }
        showReferenceText(context.getString(R.string.guide_code_server_phone))
        showReferenceText(context.getString(R.string.guide_code_server_install))
    }

    @Test
    fun largeTextKeepsReferenceSectionsReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme { GuideReferenceScreen(onBack = {}) }
            }
        }
        GuideReferenceTopic.entries.forEach { topic ->
            openReference(topic)
            showReferenceText(context.getString(topic.tips.last()))
            openReference(topic)
        }
        composeRule.onNodeWithTag("user-guide-index")
            .performScrollToNode(hasTestTag("guide-reference-ATTACHMENTS"))
        composeRule.onNodeWithTag("guide-reference-ATTACHMENTS").assertIsDisplayed()
    }

    private fun openReference(topic: GuideReferenceTopic) {
        val tag = "guide-reference-${topic.name}"
        composeRule.onNodeWithTag("user-guide-index").performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performClick()
    }

    private fun showReferenceText(text: String) {
        composeRule.onNodeWithTag("user-guide-index").performScrollToNode(hasText(text))
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun largeTextKeepsInstructionsAndExitReachable() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                QuickStartGuide(initialPage = GuideLesson.RECENT_PROJECTS.ordinal, onDismiss = {})
            }
        }
        composeRule.onNodeWithTag("guide-next").assertIsDisplayed()
        composeRule.onNodeWithText("In Herdr, long press New Workspace to choose a recent project.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Close").assertDoesNotExist()
        composeRule.onNodeWithTag("guide-back").assertIsDisplayed()
    }

    @Test
    fun fourOnboardingPagesSupportNextBackSwipeAndCompletion() {
        val open = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                if (open.value) QuickStartGuide(firstRun = true, onDismiss = { open.value = false })
            }
        }
        composeRule.onNodeWithTag("guide-title-0").assertIsDisplayed()
        composeRule.onNodeWithTag("guide-next").performClick()
        composeRule.onNodeWithTag("guide-title-1").assertIsDisplayed()
        composeRule.onNodeWithTag("guide-back").performClick()
        composeRule.onNodeWithTag("guide-title-0").assertIsDisplayed()
        composeRule.onNodeWithTag("guide-pager").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("guide-title-1").assertIsDisplayed()
        composeRule.onNodeWithTag("guide-pager").performTouchInput { swipeRight() }
        composeRule.onNodeWithTag("guide-title-0").assertIsDisplayed()
        repeat(2) { composeRule.onNodeWithTag("guide-next").performClick() }
        composeRule.onNodeWithTag("guide-title-2").assertIsDisplayed()
        composeRule.onNodeWithText("3 / 4").assertIsDisplayed()
        composeRule.onNodeWithTag("guide-next").performClick()
        composeRule.onNodeWithTag("guide-title-3").assertIsDisplayed()
        composeRule.onNodeWithText("Make Herdr your own").assertIsDisplayed()
        composeRule.onNodeWithText("4 / 4").assertIsDisplayed()
        composeRule.onNodeWithTag("guide-next").performClick()
        composeRule.runOnIdle { assertFalse(open.value) }
    }

    private fun pressGuideSystemBack() {
        // The default root can bind to the Activity before its new dialog takes focus.
        // Wait for that dialog and inject the same real Back action into its focused root.
        onView(isRoot()).inRoot(isDialog()).perform(pressBack())
    }

    @Test
    fun systemBackExitsAnyPageAndSettingsChapterReentryRemainsAvailable() {
        val open = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                UserGuideIndex()
                if (open.value) QuickStartGuide(firstRun = true, onDismiss = { open.value = false })
            }
        }
        pressGuideSystemBack()
        composeRule.onNodeWithTag("user-guide-index").assertIsDisplayed()
        openLesson(GuideLesson.PROJECTS)
        composeRule.onNodeWithTag("guide-title-2").assertIsDisplayed()
        pressGuideSystemBack()
        openLesson(GuideLesson.RECENT_PROJECTS)
        composeRule.onNodeWithText("In Herdr, long press New Workspace to choose a recent project.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("7 / 12").assertIsDisplayed()
        composeRule.onNodeWithTag("guide-next").performClick()
        composeRule.onNodeWithTag("guide-title-7").assertIsDisplayed()
        composeRule.onNodeWithTag("guide-back").performClick()
        composeRule.onNodeWithTag("guide-title-6").assertIsDisplayed()
        pressGuideSystemBack()
        openLesson(GuideLesson.SNIPPETS)
        composeRule.onNodeWithTag("guide-title-1").assertIsDisplayed()
        pressGuideSystemBack()
        composeRule.onNodeWithTag("user-guide-index").assertIsDisplayed()
        openLesson(GuideLesson.entries.last())
        pressGuideSystemBack()
        composeRule.onNodeWithTag("user-guide-index").assertIsDisplayed()
    }
}
