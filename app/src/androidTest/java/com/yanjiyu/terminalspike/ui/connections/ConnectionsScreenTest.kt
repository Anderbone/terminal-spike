package com.yanjiyu.terminalspike.ui.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import com.yanjiyu.terminalspike.TerminalSpikeComponentTestActivity
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.ui.CompactPrimaryNavigationTestTag
import com.yanjiyu.terminalspike.ui.ExpandedPrimaryNavigationTestTag
import com.yanjiyu.terminalspike.ui.theme.AppThemeMode
import com.yanjiyu.terminalspike.ui.theme.TerminalSpikeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.hamcrest.Matchers.equalTo

class ConnectionsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TerminalSpikeComponentTestActivity>()

    @Test
    fun hostCatalogHeaderKeepsScreenIdentityBesideItsPrimaryAction() {
        render(state = populatedState())

        composeRule.onNode(
            hasText("Connections") and hasAnyAncestor(hasTestTag(ConnectionsHeaderTestTag)),
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Hosts, keys, and snippets", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add host").assertIsDisplayed()
    }

    @Test
    fun newHostKeepsOnlyEssentialsVisibleAndRequiresOptInToSavePassword() {
        render(
            state = editorState(),
            callbacks = callbacks(onSaveHost = { Result.success(Unit) }),
        )

        composeRule.onNodeWithContentDescription("Add host").performClick()

        composeRule.onNodeWithTag(HostEditorHostnameTestTag).assertExists()
        composeRule.onNodeWithTag(HostEditorUsernameTestTag).assertExists()
        composeRule.onNodeWithTag(HostEditorPortTestTag).assertTextContains("22")
        composeRule.onNodeWithTag(HostEditorSecretTestTag).assertExists()
        composeRule.onNodeWithTag(HostEditorSavePasswordTestTag).assertIsOff()
        composeRule.onNodeWithTag(HostEditorNameTestTag).assertExists()
        composeRule.onNodeWithText("Protocol").assertExists()
        composeRule.onNodeWithText("Profiles").assertDoesNotExist()
        composeRule.onNodeWithTag(HostEditorNearbySshButtonTestTag).assertDoesNotExist()

        composeRule.onNodeWithText("Show advanced settings").performScrollTo().performClick()

        composeRule.onNodeWithText("Profiles").assertExists()
        composeRule.onNodeWithTag(HostEditorNearbySshButtonTestTag).assertExists()
    }

    @Test
    fun existingEncryptedPasswordIsPresentedAsSavedWithoutExposingItsValue() {
        render(
            state = editorState(savedSecretAvailable = true),
            callbacks = callbacks(onSaveHost = { Result.success(Unit) }),
        )

        composeRule.onNodeWithContentDescription("Host actions for Production").performClick()
        composeRule.onNodeWithText("Edit").performClick()

        composeRule.onNodeWithTag(HostEditorSavePasswordTestTag).assertIsOn()
        composeRule.onNodeWithTag(HostEditorSecretTestTag).assertExists()
        composeRule.onNodeWithText("Leave blank to retain the encrypted saved password.")
            .assertExists()
    }

    @Test
    fun compactHostCatalogShowsPolishedMetadataAndDispatchesConnect() {
        var connectedId: String? = null
        render(
            state = populatedState(),
            callbacks = callbacks(onConnectHost = { connectedId = it }),
            width = 599.dp,
        )

        composeRule.onNodeWithTag(CompactPrimaryNavigationTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(ConnectionsCompactListTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(ConnectionsExpandedDetailTestTag).assertDoesNotExist()
        composeRule.onNodeWithText("Production").assertIsDisplayed()
        composeRule.onNodeWithText("deploy@prod.example:2222").assertIsDisplayed()
        composeRule.onNodeWithText("SSH").assertIsDisplayed()
        composeRule.onNodeWithText("Connected").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Host actions for Production").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Connect to Production").performClick()

        composeRule.runOnIdle { assertEquals(HOST_ID, connectedId) }
    }

    @Test
    fun terminalPrimaryDestinationDispatchesCallback() {
        var openedTerminal = false
        render(
            state = populatedState(),
            onOpenTerminal = { openedTerminal = true },
        )

        composeRule.onNodeWithContentDescription("Open terminal").performClick()

        composeRule.runOnIdle { assertTrue(openedTerminal) }
    }

    @Test
    fun hostDeleteFromOverflowRequiresConfirmation() {
        var deletedId: String? = null
        render(
            state = populatedState(),
            callbacks = callbacks(onDeleteHost = { deletedId = it }),
        )

        composeRule.onNodeWithContentDescription("Host actions for Production").performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithText("Delete Production?").assertIsDisplayed()
        composeRule.runOnIdle { assertNull(deletedId) }
        composeRule.onNodeWithText("Delete host").performClick()

        composeRule.runOnIdle { assertEquals(HOST_ID, deletedId) }
    }

    @Test
    fun editHostDialogDeletesTheHostAfterConfirmation() {
        var deletedId: String? = null
        render(
            state = editorState(),
            callbacks = callbacks(
                onDeleteHost = { deletedId = it },
                onSaveHost = { Result.success(Unit) },
            ),
        )

        composeRule.onNodeWithContentDescription("Host actions for Production").performClick()
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithTag(HostEditorDeleteTestTag).performClick()
        composeRule.onNodeWithText("Delete Production?").assertIsDisplayed()
        composeRule.runOnIdle { assertNull(deletedId) }
        composeRule.onNodeWithTag(HostEditorConfirmDeleteTestTag).performClick()

        composeRule.runOnIdle { assertEquals(HOST_ID, deletedId) }
        composeRule.onNodeWithTag(HostEditorDialogTestTag).assertDoesNotExist()
    }

    @Test
    fun inUseKeyDeleteShowsReferenceCountAndNeverDispatchesDeletion() {
        var deletedId: String? = null
        render(
            state = editorState(keyReferenceCount = 2).copy(selectedTab = ConnectionsTab.KEYS),
            callbacks = callbacks(onDeleteKey = { deletedId = it }),
        )

        composeRule.onNodeWithContentDescription("Key actions for Deploy key").performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithText("Deploy key is in use").assertIsDisplayed()
        composeRule.onNodeWithText("Used by 2 saved authentication records", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Delete key").assertDoesNotExist()
        composeRule.onNodeWithText("Close").performClick()

        composeRule.runOnIdle { assertNull(deletedId) }
    }

    @Test
    fun dirtyMoshEditorConfirmsDiscardBeforeOpeningExtensionStatus() {
        var statusOpenCount = 0
        render(
            state = editorState(protocol = ConnectionProtocol.MOSH),
            callbacks = callbacks(
                onSaveHost = { Result.success(Unit) },
                onOpenMoshStatus = { statusOpenCount += 1 },
            ),
        )

        composeRule.onNodeWithContentDescription("Host actions for Production").performClick()
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithTag(HostEditorNameTestTag).performTextInput(" changed")
        composeRule.onNodeWithText("View Mosh extension status").performScrollTo().performClick()

        composeRule.onNodeWithText("Discard host changes?").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, statusOpenCount) }
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.runOnIdle { assertEquals(1, statusOpenCount) }
        composeRule.onNodeWithTag(HostEditorDialogTestTag).assertDoesNotExist()
    }

    @Test
    fun failedHostSaveKeepsDraftButRequiresTransientSecretReentry() {
        var attempts = 0
        val receivedSecrets = mutableListOf<String>()
        render(
            state = editorState(),
            callbacks = callbacks(
                onSaveHost = { submission ->
                    attempts += 1
                    receivedSecrets += submission.secret.concatToString()
                    if (attempts == 1) {
                        Result.failure(IllegalStateException("simulated write failure"))
                    } else {
                        Result.success(Unit)
                    }
                },
            ),
        )

        composeRule.onNodeWithContentDescription("Host actions for Production").performClick()
        composeRule.onNodeWithText("Edit").performClick()
        onView(withTagValue(equalTo(HostEditorSecretTestTag)))
            .perform(replaceText("retry-secret"))
        composeRule.onNodeWithTag(HostEditorSaveTestTag).performClick()

        waitForNodeWithTag(EditorPersistenceErrorTestTag)
        composeRule.onNodeWithTag(EditorPersistenceErrorTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(HostEditorDialogTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(HostEditorSaveTestTag).performClick()
        composeRule.runOnIdle { assertEquals(1, attempts) }
        onView(withTagValue(equalTo(HostEditorSecretTestTag)))
            .perform(replaceText("retry-secret"))
        composeRule.onNodeWithTag(HostEditorSaveTestTag).performClick()

        waitForNoNodeWithTag(HostEditorDialogTestTag)
        composeRule.runOnIdle {
            assertEquals(2, attempts)
            assertEquals(listOf("retry-secret", "retry-secret"), receivedSecrets)
        }
        composeRule.onNodeWithTag(HostEditorDialogTestTag).assertDoesNotExist()
    }

    @Test
    fun pendingHostAndNonSecretDraftRestoreWhileTransientSecretRequiresReentry() {
        var saves = 0
        val restorationTester = StateRestorationTester(composeRule)
        val state = editorState(savedSecretAvailable = true)
        val retainedDrafts = mutableMapOf<String, HostEditorDraft>()
        val callbacks = callbacks(
            onSaveHost = {
                saves += 1
                Result.success(Unit)
            },
        ).copy(
            onResolveHostEditorDraft = { token, fallback ->
                retainedDrafts.getOrPut(token) { fallback }
            },
            onRetainHostEditorDraft = { token, draft -> retainedDrafts[token] = draft },
            onClearHostEditorDraft = { token -> retainedDrafts.remove(token) },
        )
        restorationTester.setContent {
            TerminalSpikeTheme(themeMode = AppThemeMode.LIGHT, updateSystemBarIcons = false) {
                ConnectionsScreen(
                    state = state,
                    callbacks = callbacks,
                    onOpenWorkspace = {},
                    onOpenTerminal = {},
                    onOpenSettings = {},
                    modifier = Modifier.requiredSize(599.dp, 900.dp),
                    nowEpochMillis = 200_000L,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Host actions for Production").performClick()
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithTag(HostEditorNameTestTag).performTextClearance()
        composeRule.onNodeWithTag(HostEditorNameTestTag).performTextInput("Restored draft")
        onView(withTagValue(equalTo(HostEditorSecretTestTag)))
            .perform(replaceText("must-not-restore"))

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(HostEditorDialogTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(HostEditorNameTestTag).assertTextContains("Restored draft")
        composeRule.onNodeWithTag(EditorSecretReentryTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(HostEditorSaveTestTag).performClick()
        composeRule.onNodeWithText("Re-enter the password after reopening this editor.")
            .assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, saves) }
    }

    @Test
    fun nearbySshSelectionPrefillsOnlyEndpointFields() {
        val discovery = FakeNearbySshDiscoveryController(
            startState = NearbySshDiscoveryState.Searching(
                pickerMode = NearbySshPickerMode.IN_APP,
                services = listOf(NearbySshServiceCandidate("office", "Office server")),
            ),
            selectedEndpoint = NearbySshEndpoint("Office server", "office.local", 2222),
        )
        render(
            state = editorState(),
            callbacks = callbacks(
                onSaveHost = { Result.success(Unit) },
                onResolveHostEditorDraft = { _, initial ->
                    initial.copy(username = "keep-user")
                },
                nearbySshDiscoveryControllerFactory = NearbySshDiscoveryControllerFactory {
                    discovery
                },
            ),
        )

        composeRule.onNodeWithContentDescription("Add host").performClick()
        composeRule.onNodeWithText("Show advanced settings").performScrollTo().performClick()
        composeRule.onNodeWithTag(HostEditorNearbySshButtonTestTag).performScrollTo().performClick()
        composeRule.onNodeWithTag(NearbySshDiscoveryDialogTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Office server").performClick()

        composeRule.onNodeWithTag(HostEditorNameTestTag).assertTextContains("Office server")
        composeRule.onNodeWithTag(HostEditorHostnameTestTag).assertTextContains("office.local")
        composeRule.onNodeWithTag(HostEditorPortTestTag).assertTextContains("2222")
        composeRule.onNodeWithTag(HostEditorUsernameTestTag).assertTextContains("keep-user")
        composeRule.onNodeWithText(
            "Filled in Office server. Username and authentication were not changed.",
        ).performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("office", discovery.selectedCandidateId) }
    }

    @Test
    fun nearbySshNoResultsIsHonestAndEditorDismissStopsController() {
        val discovery = FakeNearbySshDiscoveryController(
            startState = NearbySshDiscoveryState.Unavailable(
                NearbySshPickerMode.IN_APP,
                NearbySshDiscoveryFailure.TIMED_OUT,
            ),
        )
        render(
            state = editorState(),
            callbacks = callbacks(
                onSaveHost = { Result.success(Unit) },
                nearbySshDiscoveryControllerFactory = NearbySshDiscoveryControllerFactory {
                    discovery
                },
            ),
        )

        composeRule.onNodeWithContentDescription("Add host").performClick()
        composeRule.onNodeWithText("Show advanced settings").performScrollTo().performClick()
        composeRule.onNodeWithTag(HostEditorNearbySshButtonTestTag).performScrollTo().performClick()
        composeRule.onAllNodesWithText("Nearby SSH detection timed out", substring = true)[0]
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.runOnIdle {
            assertTrue(discovery.cancelCount >= 2)
            assertEquals(1, discovery.closeCount)
        }
        composeRule.onNodeWithTag(HostEditorDialogTestTag).assertDoesNotExist()
    }

    @Test
    fun failedKeyGenerationRequiresPassphraseReentryAndDismissesOnlyAfterSuccess() {
        val visible = mutableStateOf(true)
        var attempts = 0
        val passphrases = mutableListOf<String>()
        composeRule.setContent {
            TerminalSpikeTheme(themeMode = AppThemeMode.LIGHT, updateSystemBarIcons = false) {
                if (visible.value) {
                    GenerateKeyDialog(
                        onDismiss = { visible.value = false },
                        onGenerate = { request ->
                            attempts += 1
                            passphrases += request.passphrase.concatToString()
                            if (attempts == 1) Result.failure(IllegalStateException("failure"))
                            else Result.success(Unit)
                        },
                    )
                }
            }
        }

        setTextWithoutOpeningIme(GenerateKeyNameTestTag, "Deploy key")
        onView(withTagValue(equalTo(GenerateKeyPassphraseTestTag)))
            .perform(replaceText("retry-passphrase"))
        onView(withTagValue(equalTo(GenerateKeyConfirmPassphraseTestTag)))
            .perform(replaceText("retry-passphrase"))
        closeSoftKeyboard()
        composeRule.onNodeWithTag(GenerateKeySubmitTestTag).performClick()
        composeRule.onNodeWithText("Could not generate this SSH key", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(GenerateKeySubmitTestTag).performClick()
        composeRule.runOnIdle { assertEquals(1, attempts) }
        onView(withTagValue(equalTo(GenerateKeyPassphraseTestTag)))
            .perform(replaceText("retry-passphrase"))
        onView(withTagValue(equalTo(GenerateKeyConfirmPassphraseTestTag)))
            .perform(replaceText("retry-passphrase"))
        composeRule.onNodeWithTag(GenerateKeySubmitTestTag).performClick()

        composeRule.runOnIdle {
            assertEquals(2, attempts)
            assertEquals(listOf("retry-passphrase", "retry-passphrase"), passphrases)
        }
        composeRule.onNodeWithTag(GenerateKeyDialogTestTag).assertDoesNotExist()
    }

    @Test
    fun searchFilterAndSortControlsDispatchControlledStateCallbacks() {
        var query: String? = null
        var sort: HostSort? = null
        var favourites: Boolean? = null
        val renderedState = mutableStateOf(populatedState())
        composeRule.setContent {
            TerminalSpikeTheme(themeMode = AppThemeMode.LIGHT, updateSystemBarIcons = false) {
                ConnectionsScreen(
                    state = renderedState.value,
                    callbacks = callbacks(
                        onSearch = { value ->
                            query = value
                            renderedState.value = renderedState.value.copy(searchQuery = value)
                        },
                        onSort = { value ->
                            sort = value
                            renderedState.value = renderedState.value.copy(hostSort = value)
                        },
                        onFavourites = { value ->
                            favourites = value
                            renderedState.value = renderedState.value.copy(
                                hostFilters = renderedState.value.hostFilters.copy(
                                    favouritesOnly = value,
                                ),
                            )
                        },
                    ),
                    onOpenWorkspace = {},
                    onOpenTerminal = {},
                    onOpenSettings = {},
                    modifier = Modifier.requiredSize(599.dp, 900.dp),
                    nowEpochMillis = 200_000L,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Show host filters and sorting").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Favourites").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Favourites").performClick()
        composeRule.onNodeWithText("Sort: Name").performClick()
        composeRule.onNodeWithText("Recent").performClick()
        composeRule.onNodeWithContentDescription("Search hosts").performTextInput("prod")

        composeRule.waitUntil(timeoutMillis = 5_000) { renderedState.value.searchQuery == "prod" }
        composeRule.runOnIdle {
            assertEquals("prod", query)
            assertEquals(HostSort.RECENT, sort)
            assertEquals(true, favourites)
        }
    }

    @Test
    fun loadingErrorEmptyAndNoResultsAreHonestAndActionable() {
        var retries = 0
        val renderedState = mutableStateOf<ConnectionsUiState>(
            ConnectionsUiState(loadState = ConnectionsLoadState.Loading),
        )
        composeRule.setContent {
            TerminalSpikeTheme(themeMode = AppThemeMode.LIGHT, updateSystemBarIcons = false) {
                ConnectionsScreen(
                    state = renderedState.value,
                    callbacks = callbacks(onRetry = { retries += 1 }),
                    onOpenWorkspace = {},
                    onOpenTerminal = {},
                    onOpenSettings = {},
                    modifier = Modifier.requiredSize(599.dp, 900.dp),
                    nowEpochMillis = 200_000L,
                )
            }
        }
        composeRule.onNodeWithTag(ConnectionsLoadingTestTag).assertIsDisplayed()

        composeRule.runOnIdle {
            renderedState.value = ConnectionsUiState(
                loadState = ConnectionsLoadState.Error("Catalog could not be read."),
            )
        }
        composeRule.onNodeWithTag(ConnectionsErrorTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Catalog could not be read.").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()

        composeRule.runOnIdle {
            renderedState.value = ConnectionsUiState(
                loadState = ConnectionsLoadState.Ready(emptyList(), emptyList(), emptyList()),
            )
        }
        composeRule.onNodeWithText("No hosts yet").assertIsDisplayed()

        composeRule.runOnIdle {
            renderedState.value = populatedState().copy(searchQuery = "does-not-exist")
        }
        composeRule.onNodeWithText("No matching hosts").assertIsDisplayed()
        composeRule.onNodeWithText("Clear search and filters").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun keyTabShowsOnlyPublicMetadataAndOffersExplicitActions() {
        var copied: String? = null
        render(
            state = populatedState().copy(selectedTab = ConnectionsTab.KEYS),
            callbacks = callbacks(onCopyPublicKey = { copied = it }),
        )

        composeRule.onNodeWithText("Deploy key").assertIsDisplayed()
        composeRule.onNodeWithText("ssh-ed25519").assertIsDisplayed()
        composeRule.onNodeWithText("Imported").assertIsDisplayed()
        composeRule.onNodeWithText("PRIVATE KEY").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Key actions for Deploy key").performClick()
        composeRule.onNodeWithText("Copy public key").performClick()

        composeRule.runOnIdle { assertEquals(KEY_ID, copied) }
    }

    @Test
    fun multilineImmediateSnippetConfirmsBeforeRunAndSanitizesPreview() {
        var runId: String? = null
        render(
            state = populatedState().copy(selectedTab = ConnectionsTab.SNIPPETS),
            callbacks = callbacks(onRunSnippet = { runId = it }),
        )

        composeRule.onNodeWithText("printf '�[31m'", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Run Deploy safely in terminal").performClick()
        composeRule.onNodeWithText("Run Deploy safely?").assertIsDisplayed()
        composeRule.runOnIdle { assertNull(runId) }
        composeRule.onNodeWithText("Run snippet").performClick()

        composeRule.runOnIdle { assertEquals(SNIPPET_ID, runId) }
    }

    @Test
    fun expandedWidthUsesCatalogListAndDetailWhileLargeTextRemainsReachable() {
        render(
            state = populatedState(),
            width = 1_000.dp,
            height = 1_100.dp,
            fontScale = 2f,
        )

        composeRule.onNodeWithTag(ExpandedPrimaryNavigationTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(ConnectionsExpandedListTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(ConnectionsExpandedDetailTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Endpoint").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open settings").assertIsDisplayed()
    }

    @Test
    fun compactSplitScreenAtLargeTextKeepsHostActionsReachableAndTouchSized() {
        render(
            state = populatedState(),
            width = 320.dp,
            height = 360.dp,
            fontScale = 2f,
        )

        composeRule.onNodeWithTag(CompactPrimaryNavigationTestTag).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Search hosts").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Add host")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(ConnectionsCompactListTestTag)
            .performScrollToNode(hasContentDescription("Connect to Production"))
        composeRule.onNodeWithContentDescription("Connect to Production")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Host actions for Production")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun compactSplitScreenAtStandardTextKeepsSearchAvailable() {
        render(
            state = populatedState(),
            width = 320.dp,
            height = 480.dp,
        )

        composeRule.onNodeWithContentDescription("Search hosts").assertIsDisplayed()
    }

    @Test
    fun expandedHostRowConnectsInOneTapWhileDetailsRemainSeparate() {
        var connectionCount = 0
        render(
            state = populatedState(),
            callbacks = callbacks(onConnectHost = { connectionCount += 1 }),
            width = 1_000.dp,
            height = 900.dp,
        )

        composeRule.onNodeWithContentDescription("Connect to Production").performClick()
        composeRule.runOnIdle { assertEquals(1, connectionCount) }

        composeRule.onNodeWithContentDescription("Show details for Production").performClick()
        composeRule.runOnIdle { assertEquals(1, connectionCount) }
        composeRule.onNodeWithText("Endpoint").assertIsDisplayed()
    }

    @Test
    fun lightAndDarkThemesRetainTheirExpectedSurfacePolarity() {
        val themeMode = mutableStateOf(AppThemeMode.LIGHT)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                TerminalSpikeTheme(themeMode = themeMode.value, updateSystemBarIcons = false) {
                    Box(
                        Modifier
                            .requiredSize(599.dp, 900.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .testTag("connections-visual-root"),
                    ) {
                        ConnectionsScreen(
                            state = populatedState(),
                            callbacks = callbacks(),
                            onOpenWorkspace = {},
                            onOpenTerminal = {},
                            onOpenSettings = {},
                            modifier = Modifier.requiredSize(599.dp, 900.dp),
                            nowEpochMillis = 200_000L,
                        )
                    }
                }
            }
        }
        val light = rootLuminance()
        composeRule.runOnIdle { themeMode.value = AppThemeMode.DARK }
        val dark = rootLuminance()

        assertTrue("Expected light background, was $light", light > 0.5f)
        assertTrue("Expected dark background, was $dark", dark < 0.5f)
    }

    private fun render(
        state: ConnectionsUiState,
        callbacks: ConnectionsCallbacks = callbacks(),
        onOpenTerminal: () -> Unit = {},
        width: Dp = 599.dp,
        height: Dp = 900.dp,
        fontScale: Float = 1f,
        themeMode: AppThemeMode = AppThemeMode.LIGHT,
        safeContentInsets: WindowInsets = WindowInsets(0),
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                TerminalSpikeTheme(themeMode = themeMode, updateSystemBarIcons = false) {
                    Box(
                        Modifier
                            .requiredSize(width, height)
                            .background(MaterialTheme.colorScheme.background)
                            .testTag("connections-visual-root"),
                    ) {
                        ConnectionsScreen(
                            state = state,
                            callbacks = callbacks,
                            onOpenWorkspace = {},
                            onOpenTerminal = onOpenTerminal,
                            onOpenSettings = {},
                            modifier = Modifier.requiredSize(width, height),
                            safeContentInsets = safeContentInsets,
                            nowEpochMillis = 200_000L,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun rootLuminance(): Float = composeRule.onNodeWithTag("connections-visual-root")
        .captureToImage()
        .toPixelMap()[1, 1]
        .luminance()

    private fun waitForNodeWithTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForNoNodeWithTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun callbacks(
        onSearch: (String) -> Unit = {},
        onSort: (HostSort) -> Unit = {},
        onFavourites: (Boolean) -> Unit = {},
        onRetry: () -> Unit = {},
        onConnectHost: ((String) -> Unit)? = {},
        onDeleteHost: ((String) -> Unit)? = {},
        onDeleteKey: ((String) -> Unit)? = {},
        onCopyPublicKey: ((String) -> Unit)? = {},
        onRunSnippet: ((String) -> Unit)? = {},
        onSaveHost: (suspend (HostEditorSubmission) -> Result<Unit>)? = null,
        onOpenMoshStatus: (() -> Unit)? = null,
        onResolveHostEditorDraft: ((String, HostEditorDraft) -> HostEditorDraft)? = null,
        nearbySshDiscoveryControllerFactory: NearbySshDiscoveryControllerFactory? = null,
    ) = ConnectionsCallbacks(
        onTabSelected = {},
        onSearchQueryChanged = onSearch,
        onHostSortSelected = onSort,
        onFavouritesOnlyChanged = onFavourites,
        onHostGroupSelected = {},
        onClearHostFilters = {},
        onRetry = onRetry,
        onAddHost = {},
        onConnectHost = onConnectHost,
        onEditHost = {},
        onDeleteHost = onDeleteHost,
        onImportKey = {},
        onGenerateKey = {},
        onCopyPublicKey = onCopyPublicKey,
        onViewKeyFingerprint = {},
        onRenameKey = {},
        onDeleteKey = onDeleteKey,
        onAddSnippet = {},
        onInsertSnippet = {},
        onRunSnippet = onRunSnippet,
        onCopySnippet = {},
        onEditSnippet = {},
        onDeleteSnippet = {},
        onSaveHost = onSaveHost,
        onResolveHostEditorDraft = onResolveHostEditorDraft,
        onOpenMoshStatus = onOpenMoshStatus,
        nearbySshDiscoveryControllerFactory = nearbySshDiscoveryControllerFactory,
    )

    private fun setTextWithoutOpeningIme(testTag: String, text: String) {
        val action = composeRule.onNodeWithTag(testTag)
            .fetchSemanticsNode()
            .config[SemanticsActions.SetText]
        composeRule.runOnIdle {
            assertTrue(action.action?.invoke(AnnotatedString(text)) == true)
        }
    }

    private fun editorState(
        protocol: ConnectionProtocol = ConnectionProtocol.SSH,
        savedSecretAvailable: Boolean = false,
        keyReferenceCount: Int = 0,
    ): ConnectionsUiState {
        val base = populatedState()
        val ready = base.loadState as ConnectionsLoadState.Ready
        return base.copy(
            loadState = ready.copy(
                hosts = ready.hosts.map { it.copy(protocol = protocol) },
                editorCatalog = ConnectionsEditorCatalog(
                    hosts = listOf(
                        HostEditorSeed(
                            draft = HostEditorDraft(
                                persistentId = HOST_ID,
                                displayName = "Production",
                                protocol = protocol,
                                hostname = "prod.example",
                                port = "2222",
                                username = "deploy",
                            ),
                            savedSecretAvailable = savedSecretAvailable,
                        ),
                    ),
                    keys = listOf(
                        KeyEditorSeed(
                            persistentId = KEY_ID,
                            name = "Deploy key",
                            algorithm = "ssh-ed25519",
                            fingerprint = "SHA256:1234567890abcdefghijklmnopqrstuv",
                            publicKey = "ssh-ed25519 AAAATEST deploy",
                            origin = SshKeyOrigin.IMPORTED,
                            passphraseProtected = true,
                            privateKeyAvailable = true,
                            comment = "production deploy",
                            referenceCount = keyReferenceCount,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun populatedState() = ConnectionsUiState(
        loadState = ConnectionsLoadState.Ready(
            hosts = listOf(
                HostRowUi(
                    id = HOST_ID,
                    displayName = "Production",
                    username = "deploy",
                    hostname = "prod.example",
                    port = 2222,
                    protocol = ConnectionProtocol.SSH,
                    isFavourite = true,
                    group = "Work",
                    tags = listOf("critical"),
                    activeSessionStatus = HostActiveSessionStatus.CONNECTED,
                    activeSessionCount = 1,
                    lastSessionActivityAtEpochMillis = 190_000L,
                ),
            ),
            keys = listOf(
                KeyRowUi(
                    id = KEY_ID,
                    name = "Deploy key",
                    algorithm = "ssh-ed25519",
                    fingerprint = "SHA256:1234567890abcdefghijklmnopqrstuv",
                    comment = "production deploy",
                    origin = SshKeyOrigin.IMPORTED,
                    isPassphraseProtected = true,
                ),
            ),
            snippets = listOf(
                SnippetRowUi(
                    id = SNIPPET_ID,
                    name = "Deploy safely",
                    group = "Release",
                    command = "printf '\u001B[31m'\necho deploy",
                    tapAction = SnippetTapAction.SEND_IMMEDIATELY,
                    appendEnter = true,
                    confirmMultilineExecution = true,
                    isFavourite = true,
                ),
            ),
        ),
    )

    private companion object {
        const val HOST_ID = "10000000-0000-4000-8000-000000000001"
        const val KEY_ID = "10000000-0000-4000-8000-000000000002"
        const val SNIPPET_ID = "10000000-0000-4000-8000-000000000003"
    }
}

private class FakeNearbySshDiscoveryController(
    private val startState: NearbySshDiscoveryState,
    private val selectedEndpoint: NearbySshEndpoint? = null,
) : NearbySshDiscoveryController {
    private val mutableState = MutableStateFlow<NearbySshDiscoveryState>(NearbySshDiscoveryState.Idle)

    override val state: StateFlow<NearbySshDiscoveryState> = mutableState
    var selectedCandidateId: String? = null
        private set
    var cancelCount = 0
        private set
    var closeCount = 0
        private set

    override fun start() {
        mutableState.value = startState
    }

    override fun select(candidateId: String) {
        selectedCandidateId = candidateId
        selectedEndpoint?.let { mutableState.value = NearbySshDiscoveryState.Selected(it) }
    }

    override fun cancel() {
        cancelCount += 1
        mutableState.value = NearbySshDiscoveryState.Idle
    }

    override fun close() {
        closeCount += 1
    }
}
