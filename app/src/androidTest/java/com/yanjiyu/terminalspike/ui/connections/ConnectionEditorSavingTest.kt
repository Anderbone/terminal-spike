package com.yanjiyu.terminalspike.ui.connections

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.TerminalSpikeComponentTestActivity
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.ui.theme.TerminalSpikeTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConnectionEditorSavingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TerminalSpikeComponentTestActivity>()

    @Test
    fun snippetOptionsStayDisabledUntilSaveSucceeds() {
        val result = CompletableDeferred<Result<Unit>>()
        val visible = mutableStateOf(true)
        val submitted = mutableListOf<Snippet>()
        composeRule.setContent {
            TerminalSpikeTheme {
                if (visible.value) {
                    SnippetEditorDialog(
                        initial = SnippetEditorDraft(name = "List files", command = "ls"),
                        onDismiss = { visible.value = false },
                        onSave = { submitted += it; result.await() },
                    )
                }
            }
        }

        val options = composeRule.onAllNodes(isToggleable()).assertCountEquals(2)
        options[0].performScrollTo().performClick().assertIsOn()
        options[1].assertIsOff()
        composeRule.onNodeWithTag(SnippetEditorSaveTestTag).performClick()

        options[0].assertIsNotEnabled().assertIsOn()
        options[1].assertIsNotEnabled().assertIsOff()
        composeRule.runOnIdle {
            assertEquals(true, submitted.single().appendEnter)
            assertEquals(false, submitted.single().isFavorite)
            result.complete(Result.success(Unit))
        }
        composeRule.onNodeWithTag(SnippetEditorDialogTestTag).assertDoesNotExist()
    }

    @Test
    fun failedSnippetSaveRestoresOptionsAndRetainsDraftForRetry() {
        val firstResult = CompletableDeferred<Result<Unit>>()
        val visible = mutableStateOf(true)
        val submitted = mutableListOf<Snippet>()
        composeRule.setContent {
            TerminalSpikeTheme {
                if (visible.value) {
                    SnippetEditorDialog(
                        initial = SnippetEditorDraft(name = "List files", command = "ls"),
                        onDismiss = { visible.value = false },
                        onSave = {
                            submitted += it
                            if (submitted.size == 1) firstResult.await() else Result.success(Unit)
                        },
                    )
                }
            }
        }

        val options = composeRule.onAllNodes(isToggleable()).assertCountEquals(2)
        options[0].performScrollTo().performClick()
        options[1].performScrollTo().performClick()
        composeRule.onNodeWithTag(SnippetEditorSaveTestTag).performClick()
        options[0].assertIsNotEnabled()
        options[1].assertIsNotEnabled()
        composeRule.runOnIdle { firstResult.complete(Result.failure(IllegalStateException("Save failed"))) }

        composeRule.onNodeWithTag(EditorPersistenceErrorTestTag).assertExists()
        options[0].assertIsEnabled().assertIsOn()
        options[1].assertIsEnabled().assertIsOn().performScrollTo().performClick()
        composeRule.onNodeWithTag(SnippetEditorNameTestTag).assertTextContains("List files")
        composeRule.onNodeWithTag(SnippetEditorCommandTestTag).assertTextContains("ls")
        composeRule.onNodeWithTag(SnippetEditorSaveTestTag).performClick()
        composeRule.onNodeWithTag(SnippetEditorDialogTestTag).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(2, submitted.size)
            assertEquals(true, submitted[0].appendEnter)
            assertEquals(true, submitted[0].isFavorite)
            assertEquals(true, submitted[1].appendEnter)
            assertEquals(false, submitted[1].isFavorite)
        }
    }

    @Test
    fun keyRenameFreezesFieldsAndRestoresDraftAfterFailure() {
        val firstResult = CompletableDeferred<Result<Unit>>()
        val visible = mutableStateOf(true)
        val submitted = mutableListOf<Triple<String, String, String?>>()
        val key = KeyEditorSeed(
            persistentId = "11111111-1111-4111-8111-111111111111",
            name = "Deploy key",
            algorithm = "ssh-ed25519",
            fingerprint = "SHA256:test",
            publicKey = null,
            origin = SshKeyOrigin.IMPORTED,
            passphraseProtected = false,
            privateKeyAvailable = true,
            comment = "Old comment",
        )
        composeRule.setContent {
            TerminalSpikeTheme {
                if (visible.value) {
                    RenameKeyDialog(
                        key = key,
                        onDismiss = { visible.value = false },
                        onRename = { id, name, comment ->
                            submitted += Triple(id, name, comment)
                            if (submitted.size == 1) firstResult.await() else Result.success(Unit)
                        },
                    )
                }
            }
        }
        val name = composeRule.onNodeWithText(composeRule.activity.getString(R.string.key_editor_friendly_name))
        val comment = composeRule.onNodeWithText(composeRule.activity.getString(R.string.key_editor_comment_optional))
        name.performTextClearance()
        name.performTextInput("Renamed key")
        comment.performTextClearance()
        comment.performTextInput("New comment")
        val save = composeRule.onNodeWithText(composeRule.activity.getString(R.string.save))
        save.performClick()
        name.assertIsNotEnabled()
        comment.assertIsNotEnabled()
        composeRule.runOnIdle { firstResult.complete(Result.failure(IllegalStateException("Rename failed"))) }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.key_editor_update_failed)).assertExists()
        name.assertIsEnabled().assertTextContains("Renamed key")
        comment.assertIsEnabled().assertTextContains("New comment")
        comment.performTextClearance()
        comment.performTextInput("Retried comment")
        save.performClick()
        name.assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    Triple(key.persistentId, "Renamed key", "New comment"),
                    Triple(key.persistentId, "Renamed key", "Retried comment"),
                ),
                submitted,
            )
        }
    }
}
