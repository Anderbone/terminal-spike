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
