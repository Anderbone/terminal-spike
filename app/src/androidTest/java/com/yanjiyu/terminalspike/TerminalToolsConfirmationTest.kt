package com.yanjiyu.terminalspike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
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
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import com.yanjiyu.terminalspike.terminal.view.toAccessoryAction
import com.yanjiyu.terminalspike.ui.SettingsToolsScreen
import com.yanjiyu.terminalspike.ui.TerminalShortcutPreview
import com.yanjiyu.terminalspike.ui.ToolSection
import com.yanjiyu.terminalspike.ui.settings.SecuritySettingsTestTag
import com.yanjiyu.terminalspike.ui.toUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class TerminalToolsConfirmationTest {
    val composeRule = createComposeRule()
    private val order = IsolatedPreferencesRule("connection_order")

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(order).around(composeRule)

    @Test
    fun terminalKeyEditorPreviewKeepsAccessibleHeightAndActionSemantics() {
        var focused: Int? = null
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(240.dp)) {
                    TerminalShortcutPreview(
                        actions = listOf(TerminalExtraKey.ESC.toAccessoryAction(), TerminalExtraKey.TAB.toAccessoryAction()),
                        layout = KeyboardLayout.TWO_ROWS,
                        keyTagPrefix = "active-key-preview-",
                        onKeyClick = { focused = it },
                        onEditKeys = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("active-key-preview-0")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals("Terminal key ESC")
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(0, focused) }
    }

    @Test
    fun savedHostDeletionRequiresConfirmationAndCancelKeepsTheTarget() {
        var deletedProfileId: String? = null
        composeRule.setContent {
            MaterialTheme {
                ConnectionCatalogFixture(order, onDeleteHost = { deletedProfileId = it })
            }
        }

        fun openDelete() {
            composeRule.onNodeWithContentDescription("Host actions for Production").performClick()
            composeRule.onNodeWithText("Delete").performClick()
        }
        openDelete()
        composeRule.onNodeWithText("Delete Production?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertNull(deletedProfileId) }

        openDelete()
        composeRule.onNodeWithText("Delete host").performClick()
        composeRule.runOnIdle { assertEquals(CatalogFixtureHostId, deletedProfileId) }
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
                SettingsToolsScreen(
                    knownHosts = listOf(knownHost),
                    snippets = emptyList(),
                    moshExtension = MoshExtensionStatus.Checking.toUiState(),
                    onNavigateBack = {},
                    onOpenWorkspace = {},
                    onOpenTerminal = {},
                    onOpenSettings = {},
                    onForgetKnownHost = { host, algorithm -> forgotten = host to algorithm },
                    onSaveSnippet = { _, _, _, _ -> Result.success(Unit) },
                    onDeleteSnippet = {},
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
