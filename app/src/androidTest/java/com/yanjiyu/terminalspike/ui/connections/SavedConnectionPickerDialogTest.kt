package com.yanjiyu.terminalspike.ui.connections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.ui.theme.TerminalSpikeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SavedConnectionPickerDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listsSavedConnectionsAndReturnsTheSelectedHost() {
        var selectedHostId: String? = null
        composeRule.setContent {
            TerminalSpikeTheme {
                SavedConnectionPickerDialog(
                    loadState = readyState(host("production", "Production")),
                    onDismiss = {},
                    onOpenConnections = {},
                    onSelectHost = { selectedHostId = it.draft.persistentId },
                )
            }
        }

        composeRule.onNodeWithTag(SavedConnectionPickerTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Production").assertIsDisplayed()
        composeRule.onNodeWithText("deploy@prod.example:2222").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Connect to Production").performClick()

        composeRule.runOnIdle { assertEquals("production", selectedHostId) }
    }

    @Test
    fun emptyCatalogDirectsTheUserToConnections() {
        var openedConnections = false
        composeRule.setContent {
            TerminalSpikeTheme {
                SavedConnectionPickerDialog(
                    loadState = readyState(),
                    onDismiss = {},
                    onOpenConnections = { openedConnections = true },
                    onSelectHost = {},
                )
            }
        }

        composeRule.onNodeWithText(
            "No saved connections yet. Add one from the Connections workspace first.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Go to Connections").performClick()

        composeRule.runOnIdle { assertTrue(openedConnections) }
    }

    @Test
    fun newSessionListsBothProtocolsAndFilesDoNotOpenATerminal() {
        var selectedHostId: String? = null
        var filesHostId: String? = null
        var localOpened = false
        val ssh = host("ssh", "SSH server")
        val mosh = host("mosh", "Mosh server").let {
            it.copy(draft = it.draft.copy(protocol = ConnectionProtocol.MOSH))
        }
        composeRule.setContent {
            TerminalSpikeTheme {
                SavedConnectionPickerDialog(
                    loadState = readyState(ssh, mosh),
                    onDismiss = {},
                    onOpenConnections = {},
                    onSelectHost = { selectedHostId = it.draft.persistentId },
                    onOpenFiles = { filesHostId = it.draft.persistentId },
                    onLocalArch = { localOpened = true },
                )
            }
        }
        composeRule.onNodeWithTag("new-session-picker").assertIsDisplayed()
        composeRule.onNodeWithText("SSH server").assertIsDisplayed()
        composeRule.onNodeWithText("Mosh server").assertIsDisplayed()
        composeRule.onNodeWithTag("saved-connection-files-mosh").performClick()
        composeRule.runOnIdle {
            assertEquals("mosh", filesHostId)
            assertEquals(null, selectedHostId)
        }
        composeRule.onNodeWithContentDescription("Connect to SSH server").performClick()
        composeRule.runOnIdle { assertEquals("ssh", selectedHostId) }
        composeRule.onNodeWithTag("new-local-arch").performClick()
        composeRule.runOnIdle { assertTrue(localOpened) }
    }

    private fun readyState(vararg hosts: HostEditorSeed) = ConnectionsLoadState.Ready(
        hosts = emptyList(),
        keys = emptyList(),
        snippets = emptyList(),
        editorCatalog = ConnectionsEditorCatalog(hosts = hosts.toList()),
    )

    private fun host(id: String, name: String) = HostEditorSeed(
        draft = HostEditorDraft(
            persistentId = id,
            displayName = name,
            protocol = ConnectionProtocol.SSH,
            hostname = "prod.example",
            port = "2222",
            username = "deploy",
        ),
        savedSecretAvailable = true,
    )
}
