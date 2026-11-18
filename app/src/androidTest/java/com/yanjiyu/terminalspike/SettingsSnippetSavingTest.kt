package com.yanjiyu.terminalspike

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.pressBack
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionStatus
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.ui.SettingsToolsScreen
import com.yanjiyu.terminalspike.ui.SettingsSnippetEditorTestTag
import com.yanjiyu.terminalspike.ui.SettingsSnippetSaveErrorTestTag
import com.yanjiyu.terminalspike.ui.SnippetEditorDialog
import com.yanjiyu.terminalspike.ui.ToolSection
import com.yanjiyu.terminalspike.ui.toUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Component tests use an isolated toolbar preference file and never send commands or open SSH. */
class SettingsSnippetSavingTest {
    private val preferences = IsolatedPreferencesRule("snippet-toolbar")
    private val authoritativeHidden = mutableStateOf(emptySet<Long>())
    private val compose = createAndroidComposeRule<TerminalSpikeComponentTestActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(preferences).around(compose)

    @Test
    fun successfulCallbackCannotHideADefaultWithoutAuthoritativePublication() {
        render(publishVisibility = false) { Result.success(Unit) }
        edit("Continue")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithTag(SettingsSnippetEditorTestTag).assertDoesNotExist()
        compose.onNodeWithText("Continue").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(hidden(-1))
            assertTrue(preferences.preferences.all.isEmpty())
        }
    }

    @Test
    fun authoritativeVisibilityHidesTextAndCodexWithoutASaveOrPreferenceWrite() {
        authoritativeHidden.value = setOf(-1L, 0L)
        render { error("Rendering authoritative visibility must not save") }
        compose.onNodeWithText("Continue").assertDoesNotExist()
        compose.onNodeWithText("New Codex").assertDoesNotExist()
        compose.onNodeWithText("Git commit & push").assertIsDisplayed()
        compose.runOnIdle { assertTrue(preferences.preferences.all.isEmpty()) }
    }

    @Test
    fun rejectedDefaultTextSaveKeepsTheDefaultAndEditor() {
        val submitted = mutableListOf<Long?>()
        render { submitted += it; Result.failure(IllegalStateException("fixture rejected save")) }
        edit("Continue")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(listOf(-1L), submitted)
            assertFalse("A rejected replacement must not hide its default", hidden(-1))
        }
        compose.onNodeWithText("Edit command snippet").assertIsDisplayed()
    }

    @Test
    fun rejectedCodexSaveKeepsTheDedicatedDefault() {
        render { Result.failure(IllegalStateException("fixture rejected Codex save")) }
        edit("New Codex")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertFalse(hidden(0)) }
        compose.onNodeWithText("Edit command snippet").assertIsDisplayed()
    }

    @Test
    fun failedSavedSnippetWriteKeepsItsEditor() {
        render(listOf(CommandSnippet(7, "Saved fixture", "pwd", false))) {
            Result.failure(IllegalStateException("fixture storage failure"))
        }
        edit("Saved fixture")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Edit command snippet").assertIsDisplayed()
        compose.runOnIdle { assertTrue(preferences.preferences.all.isEmpty()) }
    }

    @Test
    fun successfulDefaultReplacementHidesOnlyThatDefaultAndClosesTheEditor() {
        render { Result.success(Unit) }
        edit("Continue")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Edit command snippet").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(hidden(-1))
            assertFalse(hidden(-2))
            assertFalse(hidden(0))
        }
    }

    @Test
    fun pendingSaveFreezesTheDraftAndDefaultUntilItCommits() {
        val result = CompletableDeferred<Result<Unit>>()
        var calls = 0
        render { calls++; result.await() }
        edit("Continue")
        compose.onNodeWithText("Save").performClick()
        val editableText = SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)
        compose.onNode(editableText and hasText("Continue")).assertIsNotEnabled()
        compose.onNode(editableText and hasText("continue")).assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.onNodeWithText("Saving…").assertIsNotEnabled().performClick()
        pressBack()
        compose.onNodeWithText("Edit command snippet").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, calls)
            assertFalse(hidden(-1))
            result.complete(Result.success(Unit))
        }
        compose.onNodeWithText("Edit command snippet").assertDoesNotExist()
        compose.runOnIdle { assertTrue(hidden(-1)) }
    }

    @Test
    fun failedSaveKeepsTheEditedDraftAndAllowsRetry() {
        val first = CompletableDeferred<Result<Unit>>()
        var calls = 0
        render { if (++calls == 1) first.await() else Result.success(Unit) }
        edit("Continue")
        compose.onNode(hasSetTextAction() and hasText("Continue")).performTextReplacement("My Continue")
        compose.onNode(hasSetTextAction() and hasText("continue")).performTextReplacement("printf fixture")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { first.complete(Result.failure(IllegalStateException("fixture write failed"))) }
        compose.onNodeWithTag(SettingsSnippetSaveErrorTestTag).assertExists()
        compose.onNode(hasSetTextAction() and hasText("My Continue")).assertIsEnabled()
        compose.onNode(hasSetTextAction() and hasText("printf fixture")).assertIsEnabled()
        compose.runOnIdle { assertFalse(hidden(-1)) }
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithTag(SettingsSnippetEditorTestTag).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, calls)
            assertTrue(hidden(-1))
        }
    }

    @Test
    fun throwingSaveShowsTheFixedFailureMessageWithoutClosingOrHiding() {
        render { throw IllegalStateException("fixture detail must not be shown") }
        edit("Continue")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithTag(SettingsSnippetSaveErrorTestTag).assertExists()
        compose.onNodeWithText("fixture detail must not be shown").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("Continue")).assertIsEnabled()
        compose.runOnIdle { assertFalse(hidden(-1)) }
    }

    @Test
    fun anOldCompletionCannotDismissTheReplacementEditor() {
        val result = CompletableDeferred<Result<Unit>>()
        val selected = mutableStateOf(CommandSnippet(7, "First fixture", "pwd", false))
        var dismissed = 0
        compose.setContent {
            MaterialTheme {
                SnippetEditorDialog(
                    snippet = selected.value,
                    onDismiss = { dismissed++ },
                    onSave = { _, _, _ -> withContext(NonCancellable) { result.await() } },
                )
            }
        }
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Saving…").assertIsNotEnabled()
        compose.runOnIdle { selected.value = CommandSnippet(8, "Second fixture", "ls", true) }
        compose.onNode(hasSetTextAction() and hasText("Second fixture")).assertIsEnabled()
        compose.runOnIdle { result.complete(Result.success(Unit)) }
        compose.onNode(hasSetTextAction() and hasText("Second fixture")).assertTextContains("Second fixture")
        compose.runOnIdle { assertEquals(0, dismissed) }
    }

    @Test
    fun newSnippetEditorKeepsTheDraftOnFailure() {
        var submittedId: Long? = 99L
        render { submittedId = it; Result.failure(IllegalStateException("fixture new snippet write failed")) }
        compose.onNodeWithText("Add snippet").performClick()
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("New fixture")
        compose.onNode(hasSetTextAction() and hasText("Command")).performTextReplacement("pwd")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("New command snippet").assertIsDisplayed()
        compose.onNodeWithTag(SettingsSnippetSaveErrorTestTag).assertExists()
        compose.onNode(hasSetTextAction() and hasText("New fixture")).assertIsEnabled()
        compose.runOnIdle {
            assertEquals(null, submittedId)
            assertTrue(preferences.preferences.all.isEmpty())
        }
    }

    private fun hidden(id: Long): Boolean = id in authoritativeHidden.value

    private fun edit(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNodeWithText("Edit command snippet").assertIsDisplayed()
    }

    private fun render(
        snippets: List<CommandSnippet> = emptyList(),
        publishVisibility: Boolean = true,
        save: suspend (Long?) -> Result<Unit>,
    ) {
        compose.setContent {
            val context = LocalContext.current
            val isolatedContext = remember(context) { preferences.wrap(context) }
            CompositionLocalProvider(LocalContext provides isolatedContext) {
                MaterialTheme {
                    SettingsToolsScreen(
                        knownHosts = emptyList(),
                        snippets = snippets,
                        moshExtension = MoshExtensionStatus.Checking.toUiState(),
                        onNavigateBack = {}, onOpenWorkspace = {}, onOpenTerminal = {}, onOpenSettings = {},
                        onForgetKnownHost = { _, _ -> }, onSaveSnippet = { _, _, _, id ->
                            save(id).also { result ->
                                if (publishVisibility && result.isSuccess && id != null && id <= 0) {
                                    authoritativeHidden.value = authoritativeHidden.value + id
                                }
                            }
                        },
                        hiddenDefaultSnippetIds = authoritativeHidden.value,
                        onHideDefaultSnippet = { authoritativeHidden.value = authoritativeHidden.value + it },
                        onDeleteSnippet = {},
                        onRefreshMoshExtension = {}, onOpenRendererLab = {}, initialSection = ToolSection.SNIPPETS,
                    )
                }
            }
        }
    }
}
