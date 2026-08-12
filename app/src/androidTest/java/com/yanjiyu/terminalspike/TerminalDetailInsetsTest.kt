package com.yanjiyu.terminalspike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.ui.TerminalDetailInsetContainer
import com.yanjiyu.terminalspike.ui.TerminalSystemBarsController
import com.yanjiyu.terminalspike.ui.TerminalSystemBarsEffect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TerminalDetailInsetsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun terminalChromeAndControlsStayInsideGestureAndCutoutInsets() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                MaterialTheme {
                    TerminalDetailInsetContainer(
                        modifier = Modifier.requiredSize(width = 400.dp, height = 800.dp),
                        safeContentInsets = WindowInsets(
                            left = 17.dp,
                            top = 23.dp,
                            right = 31.dp,
                            bottom = 37.dp,
                        ),
                    ) {
                        Box(Modifier.fillMaxSize().testTag("terminal-safe-content"))
                    }
                }
            }
        }

        val bounds = composeRule.onNodeWithTag("terminal-safe-content")
            .fetchSemanticsNode()
            .boundsInRoot
        assertNear(17f, bounds.left)
        assertNear(23f, bounds.top)
        assertNear(369f, bounds.right)
        assertNear(763f, bounds.bottom)
    }

    @Test
    fun terminalCanvasUsesTheFullWidthWhenOnlyVerticalInsetsArePresent() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                MaterialTheme {
                    TerminalDetailInsetContainer(
                        modifier = Modifier.requiredSize(width = 400.dp, height = 800.dp),
                        safeContentInsets = WindowInsets(top = 23.dp, bottom = 37.dp),
                    ) {
                        Box(Modifier.fillMaxSize().testTag("full-width-terminal-content"))
                    }
                }
            }
        }

        val bounds = composeRule.onNodeWithTag("full-width-terminal-content")
            .fetchSemanticsNode()
            .boundsInRoot
        assertNear(0f, bounds.left)
        assertNear(23f, bounds.top)
        assertNear(400f, bounds.right)
        assertNear(763f, bounds.bottom)
    }

    @Test
    fun immersiveSystemBarsApplyOnlyWhileTheTerminalIsVisible() {
        val immersive = mutableStateOf(false)
        var immersiveApplied = false
        var immersiveEntryCount = 0
        val controller = TerminalSystemBarsController { enabled ->
            immersiveApplied = enabled
            if (enabled) immersiveEntryCount += 1
        }
        composeRule.setContent {
            TerminalSystemBarsEffect(
                immersive = immersive.value,
                controller = controller,
            )
        }

        composeRule.runOnIdle { immersive.value = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(immersiveApplied)
            assertTrue(immersiveEntryCount == 1)
            immersive.value = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertFalse(immersiveApplied) }
    }

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue("Expected $expected, was $actual", kotlin.math.abs(expected - actual) <= 1f)
    }
}
