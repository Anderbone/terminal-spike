package com.yanjiyu.terminalspike

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.ui.TerminalEmptyState
import com.yanjiyu.terminalspike.ui.TerminalEmptyStateTestTag
import com.yanjiyu.terminalspike.ui.theme.AppThemeMode
import com.yanjiyu.terminalspike.ui.theme.TerminalSpikeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TerminalEmptyStateVisualContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyTerminalRetainsExpectedSurfacePolarityInLightAndDarkThemes() {
        val themeMode = mutableStateOf(AppThemeMode.LIGHT)
        render(themeMode = { themeMode.value })
        val light = rootLuminance()

        composeRule.runOnIdle { themeMode.value = AppThemeMode.DARK }
        val dark = rootLuminance()

        assertTrue("Expected light terminal background, was $light", light > 0.5f)
        assertTrue("Expected dark terminal background, was $dark", dark < 0.5f)
    }

    @Test
    fun narrowLargeTextKeepsEmptyTerminalActionReachableAndTouchSized() {
        var openCount = 0
        render(
            width = 320,
            height = 480,
            fontScale = 2f,
            onOpenConnection = { openCount += 1 },
        )

        composeRule.onNodeWithTag(TerminalEmptyStateTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("No terminal session is open.").assertIsDisplayed()
        composeRule.onNodeWithText("Open saved connection")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, openCount) }
    }

    private fun render(
        width: Int = 599,
        height: Int = 900,
        fontScale: Float = 1f,
        themeMode: () -> AppThemeMode = { AppThemeMode.LIGHT },
        onOpenConnection: () -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                TerminalSpikeTheme(themeMode = themeMode(), updateSystemBarIcons = false) {
                    Box(
                        modifier = Modifier
                            .requiredSize(width.dp, height.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .testTag("terminal-empty-visual-root"),
                        contentAlignment = Alignment.Center,
                    ) {
                        TerminalEmptyState(
                            canOpenConnection = true,
                            onOpenConnection = onOpenConnection,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun rootLuminance(): Float = composeRule.onNodeWithTag("terminal-empty-visual-root")
        .captureToImage()
        .toPixelMap()[1, 1]
        .luminance()
}
