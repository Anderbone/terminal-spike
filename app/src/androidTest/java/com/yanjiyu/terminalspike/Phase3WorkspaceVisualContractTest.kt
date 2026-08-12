package com.yanjiyu.terminalspike

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.ui.CompactPrimaryNavigationTestTag
import com.yanjiyu.terminalspike.ui.ExpandedPrimaryNavigationTestTag
import com.yanjiyu.terminalspike.ui.LocalWorkspaceScreen
import com.yanjiyu.terminalspike.ui.WorkspaceActiveSessionUi
import com.yanjiyu.terminalspike.ui.WorkspaceNewConnectionTestTag
import com.yanjiyu.terminalspike.ui.WorkspacePinnedHostUi
import com.yanjiyu.terminalspike.ui.WorkspaceRecentSessionUi
import com.yanjiyu.terminalspike.ui.WorkspaceSessionStatus
import com.yanjiyu.terminalspike.ui.WorkspaceUiState
import com.yanjiyu.terminalspike.ui.theme.AppThemeMode
import com.yanjiyu.terminalspike.ui.theme.TerminalSpikeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class Phase3WorkspaceVisualContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun populatedWorkspaceRendersLightSurface() {
        renderWorkspace(width = 599.dp, themeMode = AppThemeMode.LIGHT)
        composeRule.onNodeWithText("Terminal Spike").assertIsDisplayed()
        val lightLuminance = rootLuminance()

        assertTrue("Expected a light Workspace surface, was $lightLuminance", lightLuminance > 0.5f)
    }

    @Test
    fun populatedWorkspaceRendersDarkSurface() {
        renderWorkspace(width = 599.dp, themeMode = AppThemeMode.DARK)
        composeRule.onNodeWithText("Terminal Spike").assertIsDisplayed()
        val darkLuminance = rootLuminance()

        assertTrue("Expected a dark Workspace surface, was $darkLuminance", darkLuminance < 0.5f)
    }

    @Test
    fun compactLargeTextKeepsPrimaryNavigationAndNewConnectionReachable() {
        renderWorkspace(width = 599.dp, fontScale = 2f, height = 1_100.dp)

        composeRule.onNodeWithTag(CompactPrimaryNavigationTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Terminal Spike").assertIsDisplayed()
        composeRule.onNodeWithTag(WorkspaceNewConnectionTestTag)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun populatedMultiSessionWorkspaceRendersAtCompactBreakpoint() {
        renderWorkspace(width = 599.dp)
        composeRule.onNodeWithTag(CompactPrimaryNavigationTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Production").assertIsDisplayed()
        composeRule.onNodeWithText("Staging").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun populatedMultiSessionWorkspaceRendersAtExpandedBreakpoint() {
        renderWorkspace(width = 700.dp)
        composeRule.onNodeWithTag(ExpandedPrimaryNavigationTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Terminal Spike").assertIsDisplayed()
        composeRule.onNodeWithText("Production").assertIsDisplayed()
        composeRule.onNodeWithText("Staging").assertIsDisplayed()
    }

    private fun renderWorkspace(
        width: Dp,
        height: Dp = 900.dp,
        fontScale: Float = 1f,
        themeMode: AppThemeMode = AppThemeMode.LIGHT,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                TerminalSpikeTheme(
                    themeMode = themeMode,
                    updateSystemBarIcons = false,
                ) {
                    Box(
                        Modifier
                            .requiredSize(width, height)
                            .background(MaterialTheme.colorScheme.background)
                            .testTag("workspace-visual-root"),
                    ) {
                        LocalWorkspaceScreen(
                            workspace = populatedWorkspace(),
                            settingsReady = true,
                            onReopenSession = {},
                            onReconnectSession = {},
                            onDisconnectSession = {},
                            onDuplicateSession = {},
                            onConnectPinnedHost = {},
                            onReconnectRecent = {},
                            onQuickConnect = {},
                            onOpenTerminal = {},
                            onOpenConnections = {},
                            onOpenSettings = {},
                            modifier = Modifier.requiredSize(width, height),
                            activityClock = { 120_000L },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun rootLuminance(): Float = composeRule.onNodeWithTag("workspace-visual-root")
        .captureToImage()
        .toPixelMap()[1, 1]
        .luminance()

    private fun populatedWorkspace() = WorkspaceUiState(
        activeSessions = listOf(
            WorkspaceActiveSessionUi(
                id = 1,
                friendlyName = "Production",
                protocol = ConnectionProtocol.SSH,
                status = WorkspaceSessionStatus.CONNECTED,
                terminalTitle = "build",
                lastActivityAtEpochMillis = 100_000,
                canReconnect = false,
                canDisconnect = true,
                canDuplicate = true,
            ),
            WorkspaceActiveSessionUi(
                id = 2,
                friendlyName = "Staging",
                protocol = ConnectionProtocol.SSH,
                status = WorkspaceSessionStatus.DISCONNECTED,
                terminalTitle = null,
                lastActivityAtEpochMillis = 90_000,
                canReconnect = true,
                canDisconnect = false,
                canDuplicate = true,
            ),
            WorkspaceActiveSessionUi(
                id = 3,
                friendlyName = "Logs",
                protocol = ConnectionProtocol.MOSH,
                status = WorkspaceSessionStatus.CONNECTING,
                terminalTitle = null,
                lastActivityAtEpochMillis = 80_000,
                canReconnect = false,
                canDisconnect = true,
                canDuplicate = true,
            ),
        ),
        pinnedHosts = listOf(
            WorkspacePinnedHostUi(
                profileId = 4,
                friendlyName = "Database",
                protocol = ConnectionProtocol.SSH,
                canConnect = true,
            ),
        ),
        recentConnections = listOf(
            WorkspaceRecentSessionUi(
                id = "10000000-0000-4000-8000-000000000001",
                friendlyName = "Archive",
                protocol = ConnectionProtocol.SSH,
                status = WorkspaceSessionStatus.DISCONNECTED,
                terminalTitle = null,
                lastActivityAtEpochMillis = 70_000,
                sourceProfileId = 5,
            ),
        ),
    )
}
