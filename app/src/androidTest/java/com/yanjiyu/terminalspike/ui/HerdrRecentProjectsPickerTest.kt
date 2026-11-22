package com.yanjiyu.terminalspike.ui

import android.content.res.Configuration
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.yanjiyu.terminalspike.TerminalSpikeComponentTestActivity
import com.yanjiyu.terminalspike.awaitBoundsSettled
import com.yanjiyu.terminalspike.connection.RecentHerdrProject
import com.yanjiyu.terminalspike.ui.theme.AppThemeMode
import com.yanjiyu.terminalspike.ui.theme.TerminalSpikeTheme
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HerdrRecentProjectsPickerTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalSpikeComponentTestActivity>()
    private val now = 1_794_758_400_000L
    private val rows = listOf(
        RecentHerdrProject("/home/jiyu/git/workspace-cable-flow", now - 120_000),
        RecentHerdrProject("/home/jiyu/git/terminal-spike", now - 7_200_000),
        RecentHerdrProject("/home/jiyu/git/play/pi-obsidian-edge", now - 86_400_000),
        RecentHerdrProject("/home/jiyu/git/websites/studio", now - 3 * 86_400_000),
        RecentHerdrProject("/tmp/legacy"),
    )
    private val query = mutableStateOf("")
    private val opening = mutableStateOf<String?>(null)
    private var selected: RecentHerdrProject? = null

    private fun show(fontScale: Float = 1f, locale: Locale = Locale.ENGLISH, refreshing: Boolean = false) {
        compose.setContent {
            val context = LocalContext.current
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
            val localized = context.createConfigurationContext(configuration)
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources,
                LocalDensity provides Density(density, fontScale)) {
                TerminalSpikeTheme(themeMode = AppThemeMode.DARK, updateSystemBarIcons = false) {
                    HerdrRecentProjectsPicker(rows, refreshing, opening.value, false, query.value, now,
                        onQueryChange = { query.value = it },
                        onOpen = { selected = it; opening.value = it.path }, onDismiss = {})
                }
            }
        }
    }

    @Test fun orderedRowsKeepTimesOnTheRightAndSelectionDisablesOtherActions() {
        show(refreshing = true)
        compose.onNodeWithText("Loading projects…").assertDoesNotExist()
        // Compare row geometry only after the IME-driven dialog move on API 26–27 has finished.
        compose.awaitBoundsSettled(hasTestTag("recent-project:${rows[1].path}"))
        val first = compose.onNodeWithTag("recent-project:${rows[0].path}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("recent-project:${rows[1].path}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(first.bottom <= second.top)
        val name = compose.onNodeWithText("workspace-cable-flow", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val time = compose.onNode(hasContentDescription("Last used", substring = true) and hasAnyAncestor(hasTestTag("recent-project:${rows[0].path}")),
            useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The date must not overlap the project name", name.right <= time.left)
        savePreview("recent-projects-english.png")
        compose.onNodeWithTag("recent-project:${rows[0].path}").performClick()
        compose.runOnIdle { assertEquals(rows[0], selected) }
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.onNodeWithTag("recent-project-search").assertIsNotEnabled()
        compose.onNodeWithText("Opening project…").assertIsDisplayed()
    }

    @Test fun searchMatchesPathsWithoutReorderingAndClearRestoresTheList() {
        show()
        compose.onNodeWithTag("recent-project-search").performTextInput("TERMINAL")
        compose.onNodeWithTag("recent-project:${rows[1].path}").assertIsDisplayed()
        compose.onNodeWithTag("recent-project:${rows[0].path}").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        savePreview("recent-projects-search-cleared.png")
        compose.onNodeWithTag("recent-project:${rows[0].path}").assertIsDisplayed()
        compose.onNodeWithTag("recent-project-search").performTextInput("no-such-project")
        compose.onNodeWithText("No matching projects").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test fun chineseLargeTextKeepsSearchDatesAndDismissReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = Settings.System.getFloat(instrumentation.targetContext.contentResolver, Settings.System.FONT_SCALE, 1f)
        fun setScale(scale: Float) {
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale $scale").close()
            compose.waitUntil(10_000) {
                kotlin.math.abs(instrumentation.targetContext.resources.configuration.fontScale - scale) < 0.01f
            }
            compose.activityRule.scenario.recreate()
        }
        try {
            // Dialogs own an Android composition root: a parent LocalDensity override alone
            // does not exercise real system font scaling in their content.
            setScale(2f)
            show(fontScale = 2f, locale = Locale.SIMPLIFIED_CHINESE)
            assertTrue(kotlin.math.abs(compose.activity.resources.configuration.fontScale - 2f) < 0.01f)
            savePreview("recent-projects-chinese-large.png")
            compose.onNodeWithText("最近项目").assertIsDisplayed()
            compose.onNodeWithText("最近使用优先").assertIsDisplayed()
            compose.onNodeWithText("上次使用").assertIsDisplayed()
            compose.onNodeWithText("取消").assertIsDisplayed()
            compose.onNodeWithTag("recent-project-list").performScrollToNode(hasTestTag("recent-project:${rows.last().path}"))
            compose.onNodeWithTag("recent-project:${rows.last().path}").assertIsDisplayed()
            compose.onNodeWithContentDescription("时间未知", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("取消").assertIsDisplayed()
        } finally {
            setScale(original)
        }
    }

    private fun savePreview(name: String) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        java.io.File(compose.activity.cacheDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
