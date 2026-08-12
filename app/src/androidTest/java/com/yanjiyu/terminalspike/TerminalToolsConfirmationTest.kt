package com.yanjiyu.terminalspike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.connection.KnownHostSummary
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionStatus
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import com.yanjiyu.terminalspike.ui.AppDestination
import com.yanjiyu.terminalspike.ui.KeyDeckPreviewRow
import com.yanjiyu.terminalspike.ui.LocalToolsScreen
import com.yanjiyu.terminalspike.ui.ToolSection
import com.yanjiyu.terminalspike.ui.toUiState
import com.yanjiyu.terminalspike.ui.settings.SecuritySettingsTestTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class TerminalToolsConfirmationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun terminalKeyEditorPreviewKeepsAccessibleHeightAndActionSemantics() {
        var focused: TerminalExtraKey? = null
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(240.dp)) {
                    KeyDeckPreviewRow(
                        keys = listOf(TerminalExtraKey.ESC, TerminalExtraKey.TAB),
                        columnCount = 2,
                        focusedKey = null,
                        onFocus = { focused = it },
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Edit ESC terminal key")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(TerminalExtraKey.ESC, focused) }
    }

    @Test
    fun savedHostDeletionRequiresConfirmationAndCancelKeepsTheTarget() {
        val profile = SavedSshProfile(
            id = 7L,
            label = "Production",
            host = "server.example",
            port = 22,
            username = "deploy",
            hasSavedPassword = true,
        )
        var deletedProfileId: Long? = null

        composeRule.setContent {
            MaterialTheme {
                LocalToolsScreen(
                    destination = AppDestination.CONNECTIONS,
                    profiles = listOf(profile),
                    identities = emptyList(),
                    knownHosts = emptyList(),
                    snippets = emptyList(),
                    extraKeys = TerminalExtraKey.DEFAULT_ORDER,
                    moshExtension = MoshExtensionStatus.Checking.toUiState(),
                    onNavigateBack = {},
                    onOpenWorkspace = {},
                    onOpenConnections = {},
                    onOpenSettings = {},
                    onUseProfile = {},
                    onSaveProfile = { _, _, _, _, _ -> },
                    onDeleteProfile = { deletedProfileId = it },
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

        val delete = composeRule.onNodeWithContentDescription("Delete Production profile")
        delete.performClick()
        composeRule.onNodeWithText("Delete Production?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertNull(deletedProfileId) }

        delete.performClick()
        composeRule.onNodeWithText("Delete host").performClick()
        composeRule.runOnIdle { assertEquals(7L, deletedProfileId) }
    }

    @Test
    fun forgettingAHostKeyRequiresEndpointSpecificConfirmation() {
        val knownHost = KnownHostSummary(
            host = "server.example",
            algorithm = "ssh-ed25519",
            sha256Fingerprint = "SHA256:test",
        )
        var forgotten: Pair<String, String>? = null

        composeRule.setContent {
            MaterialTheme {
                LocalToolsScreen(
                    destination = AppDestination.SETTINGS,
                    profiles = emptyList(),
                    identities = emptyList(),
                    knownHosts = listOf(knownHost),
                    snippets = emptyList(),
                    extraKeys = TerminalExtraKey.DEFAULT_ORDER,
                    moshExtension = MoshExtensionStatus.Checking.toUiState(),
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
                    onForgetKnownHost = { host, algorithm -> forgotten = host to algorithm },
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
                    initialSection = ToolSection.SECURITY,
                )
            }
        }

        composeRule.onNodeWithTag(SecuritySettingsTestTag)
            .performScrollToNode(hasText("Forget"))
        composeRule.onNodeWithText("Forget").performClick()
        composeRule.onNodeWithText("Forget server.example?").assertIsDisplayed()
        composeRule.onNodeWithText("Forget host key").performClick()

        composeRule.runOnIdle {
            assertEquals("server.example" to "ssh-ed25519", forgotten)
        }
    }
}
