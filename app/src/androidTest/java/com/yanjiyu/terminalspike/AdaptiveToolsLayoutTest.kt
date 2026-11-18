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
import com.yanjiyu.terminalspike.ui.CompactPrimaryNavigationTestTag
import com.yanjiyu.terminalspike.ui.ExpandedPrimaryNavigationTestTag
import com.yanjiyu.terminalspike.ui.SettingsToolsScreen
import com.yanjiyu.terminalspike.ui.connections.ConnectionsCompactListTestTag
import com.yanjiyu.terminalspike.ui.connections.ConnectionsExpandedDetailTestTag
import com.yanjiyu.terminalspike.ui.settings.AdvancedSettingsListTestTag
import com.yanjiyu.terminalspike.ui.settings.ExpandedSettingsCategoryListTestTag
import com.yanjiyu.terminalspike.ui.settings.ExpandedSettingsDetailTestTag
import com.yanjiyu.terminalspike.ui.toUiState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class AdaptiveToolsLayoutTest {
    val composeRule = createComposeRule()
    private val order = IsolatedPreferencesRule("connection_order")

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(order).around(composeRule)

    @Test
    fun compactConnectionsUsesProductionCatalogAndSingleDetailPane() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(599.dp, 760.dp)) { ConnectionCatalogFixture(order) }
                }
            }
        }

        composeRule.onNodeWithTag(ConnectionsCompactListTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(ConnectionsExpandedDetailTestTag).assertDoesNotExist()
        composeRule.onNodeWithTag(ExpandedSettingsCategoryListTestTag).assertDoesNotExist()
        composeRule.onNodeWithTag(ExpandedSettingsDetailTestTag).assertDoesNotExist()
        composeRule.onNodeWithText("Production").assertIsDisplayed()
    }

    @Test
    fun expandedSettingsUsesMasterDetailAndSurvivesLargeText() {
        composeRule.setContent {
            SizedSettingsScreen(
                width = 600,
                fontScale = 2f,
            )
        }

        composeRule.onNodeWithTag(CompactPrimaryNavigationTestTag).assertDoesNotExist()
        composeRule.onNodeWithTag(ExpandedPrimaryNavigationTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(ExpandedSettingsCategoryListTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(ExpandedSettingsDetailTestTag).assertIsDisplayed()
        val categories = composeRule.onNodeWithTag(ExpandedSettingsCategoryListTestTag)
        categories.performScrollToNode(hasText("Advanced options"))
        composeRule.onNodeWithText("Advanced options").performClick()
        composeRule.onNodeWithTag(AdvancedSettingsListTestTag).performScrollToNode(hasText("Mosh"))
        composeRule.onNodeWithText("Mosh").performClick()
        composeRule.onNodeWithText("Unavailable").performScrollTo().assertIsDisplayed()
        categories.performScrollToNode(hasText("About"))
        composeRule.onNodeWithText("About").performClick()
        composeRule.onNodeWithText("About Terminal Spike").assertIsDisplayed()
    }
}

@Composable
private fun SizedSettingsScreen(
    width: Int,
    fontScale: Float = 1f,
) {
    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
        MaterialTheme {
            Box(Modifier.requiredSize(width.dp, 760.dp)) {
                SettingsToolsScreen(
                    knownHosts = emptyList(),
                    snippets = emptyList(),
                    moshExtension = MoshExtensionStatus.Absent.toUiState(),
                    onNavigateBack = {},
                    onOpenWorkspace = {},
                    onOpenTerminal = {},
                    onOpenSettings = {},
                    onForgetKnownHost = { _, _ -> },
                    onSaveSnippet = { _, _, _, _ -> Result.success(Unit) },
                    onDeleteSnippet = {},
                    onRefreshMoshExtension = {},
                    onOpenRendererLab = {},
                )
            }
        }
    }
}
