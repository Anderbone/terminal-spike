package com.yanjiyu.terminalspike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.ui.AdaptivePrimaryNavigation
import com.yanjiyu.terminalspike.ui.AppDestination
import com.yanjiyu.terminalspike.ui.CompactPrimaryNavigationTestTag
import com.yanjiyu.terminalspike.ui.ExpandedPrimaryNavigationTestTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdaptivePrimaryNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactWidthUsesExactlyThreePrimaryBarDestinations() {
        var openedTerminal = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                MaterialTheme {
                    AdaptivePrimaryNavigation(
                        selected = AppDestination.WORKSPACE,
                        onWorkspace = {},
                        onConnections = { openedTerminal = true },
                        onSettings = {},
                        modifier = Modifier.requiredSize(width = 599.dp, height = 700.dp),
                    ) { contentModifier, _ ->
                        Box(contentModifier)
                    }
                }
            }
        }

        composeRule.onNodeWithTag(CompactPrimaryNavigationTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(ExpandedPrimaryNavigationTestTag).assertDoesNotExist()
        assertExactlyThreeDestinations(CompactPrimaryNavigationTestTag)
        composeRule.onNodeWithContentDescription("Open connections").assertIsSelected()
        composeRule.onNodeWithContentDescription("Open terminal").performClick()
        composeRule.runOnIdle { assertTrue(openedTerminal) }
    }

    @Test
    fun expandedBreakpointUsesExactlyThreePrimaryRailDestinations() {
        var openedWorkspace = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                MaterialTheme {
                    AdaptivePrimaryNavigation(
                        selected = AppDestination.SETTINGS,
                        onWorkspace = { openedWorkspace = true },
                        onConnections = {},
                        onSettings = {},
                        modifier = Modifier.requiredSize(width = 600.dp, height = 700.dp),
                    ) { contentModifier, _ ->
                        Box(contentModifier)
                    }
                }
            }
        }

        composeRule.onNodeWithTag(ExpandedPrimaryNavigationTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(CompactPrimaryNavigationTestTag).assertDoesNotExist()
        assertExactlyThreeDestinations(ExpandedPrimaryNavigationTestTag)
        composeRule.onNodeWithContentDescription("Open settings").assertIsSelected()
        composeRule.onNodeWithContentDescription("Open connections").performClick()
        composeRule.runOnIdle { assertTrue(openedWorkspace) }
    }

    @Test
    fun connectionsDestinationClaimsTheFirstPrimaryDestination() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                MaterialTheme {
                    AdaptivePrimaryNavigation(
                        selected = AppDestination.CONNECTIONS,
                        onWorkspace = {},
                        onConnections = {},
                        onSettings = {},
                        modifier = Modifier.requiredSize(width = 599.dp, height = 700.dp),
                    ) { contentModifier, _ ->
                        Box(contentModifier)
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("Open connections").assertIsSelected()
        composeRule.onNodeWithContentDescription("Open terminal").assertIsNotSelected()
    }

    @Test
    fun terminalDestinationClaimsTheSecondPrimaryDestination() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                MaterialTheme {
                    AdaptivePrimaryNavigation(
                        selected = AppDestination.TERMINAL,
                        onWorkspace = {},
                        onConnections = {},
                        onSettings = {},
                        modifier = Modifier.requiredSize(width = 599.dp, height = 700.dp),
                    ) { contentModifier, _ ->
                        Box(contentModifier)
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("Open connections").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("Open terminal").assertIsSelected()
        composeRule.onNodeWithContentDescription("Open settings").assertIsNotSelected()
    }

    @Test
    fun compactContentAndNavigationRespectGestureSideAndBottomInsets() {
        val safeContentInsets = WindowInsets(left = 17.dp, top = 23.dp, right = 31.dp, bottom = 37.dp)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                MaterialTheme {
                    AdaptivePrimaryNavigation(
                        selected = AppDestination.WORKSPACE,
                        onWorkspace = {},
                        onConnections = {},
                        onSettings = {},
                        modifier = Modifier.requiredSize(width = 400.dp, height = 800.dp),
                        safeContentInsets = safeContentInsets,
                    ) { contentModifier, _ ->
                        Box(contentModifier) {
                            Box(Modifier.fillMaxSize().testTag("safe-primary-content"))
                        }
                    }
                }
            }
        }

        val content = composeRule.onNodeWithTag("safe-primary-content").fetchSemanticsNode().boundsInRoot
        assertNear(17f, content.left)
        assertNear(23f, content.top)
        assertNear(369f, content.right)
        val workspaceItem = composeRule.onNodeWithContentDescription("Open connections")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(workspaceItem.left >= 17f)
        assertTrue(workspaceItem.right <= 369f)
        assertTrue(workspaceItem.bottom <= 763f)
    }

    @Test
    fun expandedContentAndRailRespectOppositeCutoutAndVerticalInsets() {
        val safeContentInsets = WindowInsets(left = 17.dp, top = 23.dp, right = 31.dp, bottom = 37.dp)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                MaterialTheme {
                    AdaptivePrimaryNavigation(
                        selected = AppDestination.SETTINGS,
                        onWorkspace = {},
                        onConnections = {},
                        onSettings = {},
                        modifier = Modifier.requiredSize(width = 700.dp, height = 800.dp),
                        safeContentInsets = safeContentInsets,
                    ) { contentModifier, _ ->
                        Box(contentModifier) {
                            Box(Modifier.fillMaxSize().testTag("safe-expanded-content"))
                        }
                    }
                }
            }
        }

        val content = composeRule.onNodeWithTag("safe-expanded-content").fetchSemanticsNode().boundsInRoot
        assertNear(23f, content.top)
        assertNear(669f, content.right)
        assertNear(763f, content.bottom)
        val workspaceItem = composeRule.onNodeWithContentDescription("Open connections")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(workspaceItem.left >= 17f)
        assertTrue(workspaceItem.top >= 23f)
        assertTrue(workspaceItem.bottom <= 763f)
    }

    private fun assertExactlyThreeDestinations(navigationTag: String) {
        val navigationItem = hasAnyAncestor(hasTestTag(navigationTag))
            .and(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
        composeRule.onAllNodes(navigationItem, useUnmergedTree = true).assertCountEquals(3)
        composeRule.onNodeWithContentDescription("Open connections").assertExists()
        composeRule.onNodeWithContentDescription("Open terminal").assertExists()
        composeRule.onNodeWithContentDescription("Open settings").assertExists()
        composeRule.onNodeWithContentDescription("Open local workspace").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open local tools").assertDoesNotExist()
    }

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue("Expected $expected, was $actual", kotlin.math.abs(expected - actual) <= 1f)
    }
}
