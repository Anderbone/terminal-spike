package com.yanjiyu.terminalspike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionStatus
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import com.yanjiyu.terminalspike.ui.AppDestination
import com.yanjiyu.terminalspike.ui.CompactToolTabsTestTag
import com.yanjiyu.terminalspike.ui.ExpandedToolDetailTestTag
import com.yanjiyu.terminalspike.ui.ExpandedToolSectionListTestTag
import com.yanjiyu.terminalspike.ui.LocalToolsScreen
import com.yanjiyu.terminalspike.ui.toUiState
import org.junit.Rule
import org.junit.Test

class AdaptiveToolsLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactConnectionsUsesScrollableTabsAndSingleDetailPane() {
        composeRule.setContent {
            SizedToolsScreen(width = 599, destination = AppDestination.CONNECTIONS)
        }

        composeRule.onNodeWithTag(CompactToolTabsTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(ExpandedToolSectionListTestTag).assertDoesNotExist()
        composeRule.onNodeWithTag(ExpandedToolDetailTestTag).assertDoesNotExist()
        composeRule.onNodeWithText("Hosts").assertIsDisplayed()
    }

    @Test
    fun expandedSettingsUsesMasterDetailAndSurvivesLargeText() {
        composeRule.setContent {
            SizedToolsScreen(
                width = 600,
                destination = AppDestination.SETTINGS,
                fontScale = 2f,
            )
        }

        composeRule.onNodeWithTag(CompactToolTabsTestTag).assertDoesNotExist()
        composeRule.onNodeWithTag(ExpandedToolSectionListTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(ExpandedToolDetailTestTag).assertIsDisplayed()
        val categories = composeRule.onNodeWithTag(ExpandedToolSectionListTestTag)
        categories.performScrollToNode(hasText("Mosh Extension"))
        composeRule.onNodeWithText("Mosh Extension").performClick()
        composeRule.onNodeWithText("Not installed").performScrollTo().assertIsDisplayed()
        categories.performScrollToNode(hasText("About"))
        composeRule.onNodeWithText("About").performClick()
        composeRule.onNodeWithText("About Terminal Spike").assertIsDisplayed()
    }
}

@Composable
private fun SizedToolsScreen(
    width: Int,
    destination: AppDestination,
    fontScale: Float = 1f,
) {
    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
        MaterialTheme {
            Box(Modifier.requiredSize(width.dp, 760.dp)) {
                LocalToolsScreen(
                    destination = destination,
                    profiles = emptyList(),
                    identities = emptyList(),
                    knownHosts = emptyList(),
                    snippets = emptyList(),
                    extraKeys = TerminalExtraKey.DEFAULT_ORDER,
                    moshExtension = MoshExtensionStatus.Absent.toUiState(),
                    onNavigateBack = {},
                    onOpenWorkspace = {},
                    onOpenConnections = {},
                    onOpenSettings = {},
                    onUseProfile = {},
                    onSaveProfile = { _, _, _, _, _ -> },
                    onDeleteProfile = {},
                    onImportIdentity = {},
                    onReimportIdentity = {},
                    onDeleteIdentity = {},
                    onForgetKnownHost = { _, _ -> },
                    onSaveSnippet = { _, _, _, _ -> },
                    onDeleteSnippet = {},
                    onSendSnippet = {},
                    onSetKeyVisible = { _, _ -> },
                    onReplaceKey = { _, _ -> },
                    onMoveKey = { _, _ -> },
                    onResetKeys = {},
                    onSaveKeys = {},
                    onRefreshMoshExtension = {},
                    onOpenRendererLab = {},
                )
            }
        }
    }
}
