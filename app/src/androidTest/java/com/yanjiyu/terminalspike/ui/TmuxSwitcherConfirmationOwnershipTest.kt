package com.yanjiyu.terminalspike.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yanjiyu.terminalspike.connection.TmuxAvailability
import com.yanjiyu.terminalspike.connection.TmuxSession
import com.yanjiyu.terminalspike.connection.TmuxSessionCatalog
import com.yanjiyu.terminalspike.ui.theme.TerminalSpikeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TmuxSwitcherConfirmationOwnershipTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingHostsWithTheSameTmuxIdDiscardsTheOldConfirmation() {
        val owner = mutableStateOf<Any>(Any())
        val deleted = mutableListOf<String>()
        render(owner = { owner.value }, onDelete = { deleted += it })
        compose.onNodeWithContentDescription("Close tmux session Fixture").performClick()
        compose.onNodeWithText("Delete session").assertIsDisplayed()
        compose.runOnIdle { owner.value = Any() }
        compose.onNodeWithText("Delete session").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), deleted) }
        compose.onNodeWithContentDescription("Close tmux session Fixture").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), deleted) }
        compose.onNodeWithContentDescription("Close tmux session Fixture").performClick()
        compose.onNodeWithText("Delete session").performClick()
        compose.runOnIdle { assertEquals(listOf("\$0"), deleted) }
        compose.onNodeWithText("Delete session").assertDoesNotExist()
    }

    @Test fun aRemovedThenReappearingIdDoesNotResurrectDestructiveIntent() {
        val catalog = mutableStateOf<TmuxSessionCatalog?>(fixtureCatalog())
        val deleted = mutableListOf<String>()
        render(catalog = { catalog.value }, onDelete = { deleted += it })
        compose.onNodeWithContentDescription("Close tmux session Fixture").performClick()
        compose.runOnIdle { catalog.value = null }
        compose.onNodeWithText("Delete session").assertDoesNotExist()
        compose.runOnIdle { catalog.value = fixtureCatalog() }
        compose.onNodeWithContentDescription("Close tmux session Fixture").assertIsDisplayed()
        compose.onNodeWithText("Delete session").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), deleted) }
    }

    @Test fun anUnchangedCatalogRefreshPreservesCancelWithoutDeleting() {
        val catalog = mutableStateOf<TmuxSessionCatalog?>(fixtureCatalog())
        val deleted = mutableListOf<String>()
        render(catalog = { catalog.value }, onDelete = { deleted += it })
        compose.onNodeWithContentDescription("Close tmux session Fixture").performClick()
        compose.runOnIdle {
            catalog.value = fixtureCatalog().copy(activeSessionId = "\$0")
        }
        compose.onNodeWithText("Delete session").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), deleted) }
    }

    private fun render(
        owner: () -> Any = { "fixture-owner" },
        catalog: () -> TmuxSessionCatalog? = { fixtureCatalog() },
        onDelete: (String) -> Unit,
    ) {
        compose.setContent {
            TerminalSpikeTheme(updateSystemBarIcons = false) {
                ActiveTmuxSessionSwitcherDialog(
                    ownerKey = owner(), catalog = catalog(),
                    onDismiss = {}, onRefresh = {}, onSelect = {}, onDelete = onDelete,
                )
            }
        }
    }

    private fun fixtureCatalog() = TmuxSessionCatalog(
        sessions = listOf(TmuxSession("\$0", "Fixture", 1, 0, 1)),
        availability = TmuxAvailability.AVAILABLE,
    )
}
