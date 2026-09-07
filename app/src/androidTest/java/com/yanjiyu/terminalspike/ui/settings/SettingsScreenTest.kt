package com.yanjiyu.terminalspike.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.yanjiyu.terminalspike.ui.CompactPrimaryNavigationTestTag
import com.yanjiyu.terminalspike.ui.ExpandedPrimaryNavigationTestTag
import com.yanjiyu.terminalspike.ui.ExpandedToolDetailTestTag
import com.yanjiyu.terminalspike.ui.ExpandedToolSectionListTestTag
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionStatus
import com.yanjiyu.terminalspike.core.backup.BackupContentSummary
import com.yanjiyu.terminalspike.core.backup.BackupImportResult
import com.yanjiyu.terminalspike.core.backup.BackupImportStrategy
import com.yanjiyu.terminalspike.core.backup.BackupMode
import com.yanjiyu.terminalspike.core.model.BellSettings
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.CustomTerminalTheme
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.LinkBehavior
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.ScrollBehavior
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.ui.toUiState
import com.yanjiyu.terminalspike.ui.theme.AppThemeMode
import com.yanjiyu.terminalspike.ui.theme.TerminalSpikeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.hamcrest.CoreMatchers.equalTo

class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsRetainExpectedSurfacePolarityInLightAndDarkThemes() {
        val themeMode = mutableStateOf(AppThemeMode.LIGHT)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                TerminalSpikeTheme(themeMode = themeMode.value, updateSystemBarIcons = false) {
                    Box(
                        Modifier
                            .requiredSize(599.dp, 900.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .testTag("settings-visual-root"),
                    ) {
                        TestSettingsScreen(Modifier.requiredSize(599.dp, 900.dp))
                    }
                }
            }
        }

        val light = settingsRootLuminance()
        composeRule.runOnIdle { themeMode.value = AppThemeMode.DARK }
        val dark = settingsRootLuminance()

        assertTrue("Expected light Settings background, was $light", light > 0.5f)
        assertTrue("Expected dark Settings background, was $dark", dark < 0.5f)
    }

    @Test
    fun expandedSettingsKeepNavigationCategoryListAndDetailVisible() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                TerminalSpikeTheme(themeMode = AppThemeMode.LIGHT, updateSystemBarIcons = false) {
                    Box(Modifier.requiredSize(700.dp, 900.dp)) {
                        TestSettingsScreen(Modifier.requiredSize(700.dp, 900.dp))
                    }
                }
            }
        }

        composeRule.onNodeWithTag(ExpandedPrimaryNavigationTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(ExpandedToolSectionListTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(ExpandedToolDetailTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(AppearanceSettingsTestTag).assertIsDisplayed()
    }

    @Test
    fun clearingSettingsSearchRestoresTheFullCategoryHierarchy() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(412.dp, 900.dp)) {
                    SettingsScreen(
                        state = completeState(),
                        initialCategory = null,
                        knownHosts = emptyList(),
                        moshExtension = MoshExtensionStatus.Absent.toUiState(),
                        actions = noOpActions(),
                        onForgetKnownHost = { _, _ -> },
                        onRefreshMoshExtension = {},
                        onOpenRendererLab = {},
                        onNavigateBack = {},
                        onOpenWorkspace = {},
                        onOpenTerminal = {},
                        onOpenSettings = {},
                        onDismissMessage = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(SettingsCategoryTitleTestTag).assertTextEquals("Settings")
        composeRule.onNodeWithTag(SettingsSearchTestTag).performTextInput("keyboard keys")
        composeRule.onNodeWithText("Appearance").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Clear settings search").performClick()
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeRule.onNodeWithText("Keyboard").assertIsDisplayed()
    }

    @Test
    fun compactSplitScreenAtLargeTextKeepsSettingsHierarchyReachable() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, 360.dp)) {
                        SettingsScreen(
                            state = completeState(),
                            initialCategory = null,
                            knownHosts = emptyList(),
                            moshExtension = MoshExtensionStatus.Absent.toUiState(),
                            actions = noOpActions(),
                            onForgetKnownHost = { _, _ -> },
                            onRefreshMoshExtension = {},
                            onOpenRendererLab = {},
                            onNavigateBack = {},
                            onOpenWorkspace = {},
                            onOpenTerminal = {},
                            onOpenSettings = {},
                            onDismissMessage = {},
                            safeContentInsets = WindowInsets(0),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag(CompactPrimaryNavigationTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsSearchTestTag)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription(SettingsCategoryListContentDescription)
            .performScrollToNode(hasText("About"))
        composeRule.onNodeWithText("About").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("About Terminal Spike")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun searchableHierarchyOpensKeyboardProfileWithVisibleChordKeys() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(412.dp, 900.dp)) {
                    SettingsScreen(
                        state = completeState(),
                        initialCategory = null,
                        knownHosts = emptyList(),
                        moshExtension = MoshExtensionStatus.Absent.toUiState(),
                        actions = noOpActions(),
                        onForgetKnownHost = { _, _ -> },
                        onRefreshMoshExtension = {},
                        onOpenRendererLab = {},
                        onNavigateBack = {},
                        onOpenWorkspace = {},
                        onOpenTerminal = {},
                        onOpenSettings = {},
                        onDismissMessage = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(SettingsSearchTestTag).performTextInput("keyboard keys")
        composeRule.onNodeWithText("Appearance").assertDoesNotExist()
        composeRule.onNodeWithText("Keyboard").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(KeyboardPreviewTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("^C").assertIsDisplayed()
        composeRule.onNodeWithText("^W").assertIsDisplayed()
    }

    @Test
    fun appearanceAndKeyboardChoicesDispatchRealPersistentModelChanges() {
        var selectedMode: AppearanceMode? = null
        var selectedPreset: String? = null
        val actions = noOpActions().copy(
            onAppearanceMode = { selectedMode = it },
            onKeyboardPreset = { selectedPreset = it },
        )
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(412.dp, 900.dp)) {
                    SettingsScreen(
                        state = completeState(),
                        initialCategory = SettingsCategory.APPEARANCE,
                        knownHosts = emptyList(),
                        moshExtension = MoshExtensionStatus.Absent.toUiState(),
                        actions = actions,
                        onForgetKnownHost = { _, _ -> },
                        onRefreshMoshExtension = {},
                        onOpenRendererLab = {},
                        onNavigateBack = {},
                        onOpenWorkspace = {},
                        onOpenTerminal = {},
                        onOpenSettings = {},
                        onDismissMessage = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Dark").performScrollTo().performClick()
        assertEquals(AppearanceMode.DARK, selectedMode)
        composeRule.onNodeWithContentDescription("Open settings").performClick()
        composeRule.onNodeWithText("Keyboard").performScrollTo().performClick()
        composeRule.onNodeWithText("Vim").performScrollTo().performClick()
        assertEquals("vim", selectedPreset)
    }

    @Test
    fun tappingAKeyboardPreviewKeyReplacesThatExactPosition() {
        var replacement: List<KeyboardAction>? = null
        val actions = noOpActions().copy(onKeyboardActions = { replacement = it })
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(412.dp, 900.dp)) {
                    SettingsScreen(
                        state = completeState(),
                        initialCategory = SettingsCategory.KEYBOARD,
                        knownHosts = emptyList(),
                        moshExtension = MoshExtensionStatus.Absent.toUiState(),
                        actions = actions,
                        onForgetKnownHost = { _, _ -> },
                        onRefreshMoshExtension = {},
                        onOpenRendererLab = {},
                        onNavigateBack = {},
                        onOpenWorkspace = {},
                        onOpenTerminal = {},
                        onOpenSettings = {},
                        onDismissMessage = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("$KeyboardPreviewKeyTestTagPrefix${0}")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithText("Replace Esc").assertIsDisplayed()
        composeRule.onNodeWithTag(KeyboardReplacementGridTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Find a key").performTextInput("F1")
        composeRule.onNodeWithContentDescription("Replace with F1")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        composeRule.runOnIdle {
            val updated = requireNotNull(replacement)
            assertEquals(KeyboardPresets.general.actions.size, updated.size)
            assertEquals(KeyboardAction.F1, updated[0])
            assertEquals(KeyboardPresets.general.actions.drop(1), updated.drop(1))
        }
    }

    @Test
    fun accessoryKeyEditorShowsConfiguredRowsAndDefersReplacementUntilSave() {
        var savedActions: List<KeyboardAction>? = null
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(412.dp, 900.dp)) {
                    SettingsScreen(
                        state = completeState(),
                        initialCategory = SettingsCategory.KEYBOARD,
                        knownHosts = emptyList(),
                        moshExtension = MoshExtensionStatus.Absent.toUiState(),
                        actions = noOpActions().copy(onKeyboardActions = { savedActions = it }),
                        onForgetKnownHost = { _, _ -> },
                        onRefreshMoshExtension = {},
                        onOpenRendererLab = {},
                        onNavigateBack = {},
                        onOpenWorkspace = {},
                        onOpenTerminal = {},
                        onOpenSettings = {},
                        onDismissMessage = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(KeyboardSettingsTestTag)
            .performScrollToNode(hasText("Edit accessory keys"))
        composeRule.onNodeWithText("Edit accessory keys").performClick()

        composeRule.onNodeWithTag(KeyboardEditorDeckTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag("$KeyboardEditorRowTestTagPrefix${0}").assertIsDisplayed()
        composeRule.onNodeWithTag("$KeyboardEditorRowTestTagPrefix${1}").assertIsDisplayed()
        composeRule.onNodeWithTag("$KeyboardEditorKeyTestTagPrefix${0}")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithText("Replace Esc").assertIsDisplayed()
        composeRule.onNodeWithText("Find a key").performTextInput("F1")
        composeRule.onNodeWithContentDescription("Replace with F1").performClick()

        composeRule.runOnIdle { assertEquals(null, savedActions) }
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle {
            val saved = requireNotNull(savedActions)
            assertEquals(KeyboardAction.F1, saved[0])
            assertEquals(KeyboardPresets.general.actions.size, saved.size)
        }
    }

    @Test
    fun customThemeEditorPreviewsAndSavesAllPaletteFields() {
        val theme = customTheme()
        var saved: CustomTerminalThemeDraft? = null
        val state = completeState().copy(
            terminalProfile = completeState().terminalProfile?.copy(themeId = theme.id),
            customTerminalThemes = listOf(theme),
        )
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(412.dp, 900.dp)) {
                    SettingsScreen(
                        state = state,
                        initialCategory = SettingsCategory.APPEARANCE,
                        knownHosts = emptyList(),
                        moshExtension = MoshExtensionStatus.Absent.toUiState(),
                        actions = noOpActions().copy(onSaveCustomTheme = { saved = it }),
                        onForgetKnownHost = { _, _ -> },
                        onRefreshMoshExtension = {},
                        onOpenRendererLab = {},
                        onNavigateBack = {},
                        onOpenWorkspace = {},
                        onOpenTerminal = {},
                        onOpenSettings = {},
                        onDismissMessage = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Edit custom theme").performScrollTo().performClick()
        composeRule.onNodeWithTag(CustomThemeEditorTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(CustomThemePreviewTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag("$CustomThemeColourTestTagPrefix${1}")
            .performTextReplacement("#123456")
        composeRule.onNodeWithTag(CustomThemeSaveTestTag).assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(theme.id, saved?.id)
            assertEquals(0xff123456.toInt(), saved?.backgroundArgb)
            assertEquals(16, saved?.ansi16Argb?.size)
        }
    }

    @Test
    fun bundledFontAndRendererPolicyChoicesDispatchTheSelectedProfileChanges() {
        var selectedFont: String? = null
        var boldRendering: Boolean? = null
        var ligatures: Boolean? = null
        var pinchZoom: Boolean? = null
        val actions = noOpActions().copy(
            onTerminalFont = { selectedFont = it },
            onBoldRendering = { boldRendering = it },
            onLigatures = { ligatures = it },
            onPinchZoom = { pinchZoom = it },
        )
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(412.dp, 900.dp)) {
                    SettingsScreen(
                        state = completeState(),
                        initialCategory = SettingsCategory.APPEARANCE,
                        knownHosts = emptyList(),
                        moshExtension = MoshExtensionStatus.Absent.toUiState(),
                        actions = actions,
                        onForgetKnownHost = { _, _ -> },
                        onRefreshMoshExtension = {},
                        onOpenRendererLab = {},
                        onNavigateBack = {},
                        onOpenWorkspace = {},
                        onOpenTerminal = {},
                        onOpenSettings = {},
                        onDismissMessage = {},
                    )
                }
            }
        }

        val appearance = composeRule.onNodeWithTag(AppearanceSettingsTestTag)
        appearance.performScrollToNode(hasText("Source Code Pro"))
        composeRule.onNodeWithText("Source Code Pro").performClick()
        appearance.performScrollToNode(hasText("Bold rendering"))
        composeRule.onNodeWithText("Bold rendering").performClick()
        appearance.performScrollToNode(hasText("Programming ligatures"))
        composeRule.onNodeWithText("Programming ligatures").performClick()
        appearance.performScrollToNode(hasText("Pinch to zoom"))
        composeRule.onNodeWithText("Pinch to zoom").performClick()

        composeRule.runOnIdle {
            assertEquals("source_code_pro", selectedFont)
            assertFalse(requireNotNull(boldRendering))
            assertTrue(requireNotNull(ligatures))
            assertFalse(requireNotNull(pinchZoom))
        }
    }

    @Test
    fun copyOnSelectionDispatchesThroughTheTerminalProfileLinkPolicy() {
        var updatedLinks: LinkBehavior? = null
        val actions = noOpActions().copy(
            onLinks = { transform -> updatedLinks = transform(LinkBehavior()) },
        )
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(412.dp, 900.dp)) {
                    SettingsScreen(
                        state = completeState(),
                        initialCategory = SettingsCategory.TERMINAL,
                        knownHosts = emptyList(),
                        moshExtension = MoshExtensionStatus.Absent.toUiState(),
                        actions = actions,
                        onForgetKnownHost = { _, _ -> },
                        onRefreshMoshExtension = {},
                        onOpenRendererLab = {},
                        onNavigateBack = {},
                        onOpenWorkspace = {},
                        onOpenTerminal = {},
                        onOpenSettings = {},
                        onDismissMessage = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(TerminalSettingsTestTag)
            .performScrollToNode(hasText("Copy on selection"))
        composeRule.onNodeWithText("Copy on selection").performClick()
        composeRule.runOnIdle { assertTrue(requireNotNull(updatedLinks).copyOnSelection) }
    }

    @Test
    fun backupActionsUseTheRequiredRealDocumentPickerLabels() {
        var exportStarted = false
        var restoreStarted = false
        val backupActions = BackupSettingsActions.NONE.copy(
            onBeginExport = { exportStarted = true },
            onBeginRestore = { restoreStarted = true },
        )
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(412.dp, 900.dp)) {
                    SettingsScreen(
                        state = completeState(),
                        initialCategory = SettingsCategory.BACKUP_RESTORE,
                        knownHosts = emptyList(),
                        moshExtension = MoshExtensionStatus.Absent.toUiState(),
                        actions = noOpActions(),
                        onForgetKnownHost = { _, _ -> },
                        onRefreshMoshExtension = {},
                        onOpenRendererLab = {},
                        onNavigateBack = {},
                        onOpenWorkspace = {},
                        onOpenTerminal = {},
                        onOpenSettings = {},
                        onDismissMessage = {},
                        backupActions = backupActions,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Save backup to Drive or device").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Restore from Drive or device").assertIsDisplayed().performClick()
        assertEquals(true, exportStarted)
        assertEquals(true, restoreStarted)
    }

    @Test
    fun exportSetupDoesNotShowModeOrPassphrasePrompts() {
        composeRule.setContent {
            MaterialTheme {
                BackupWorkflowDialogs(
                    state = BackupWorkflowUiState(step = BackupWorkflowStep.EXPORT_SETUP),
                    actions = BackupSettingsActions.NONE,
                )
            }
        }
        composeRule.onNodeWithTag(BackupPassphraseTestTag).assertDoesNotExist()
        composeRule.onNodeWithText("Standard backup").assertDoesNotExist()
        composeRule.onNodeWithText("Full encrypted backup").assertDoesNotExist()
    }

    @Test
    fun restoreDoesNotShowAPassphrasePrompt() {
        composeRule.setContent {
            MaterialTheme {
                BackupWorkflowDialogs(
                    state = BackupWorkflowUiState(step = BackupWorkflowStep.PASSPHRASE_REQUIRED),
                    actions = BackupSettingsActions.NONE,
                )
            }
        }
        composeRule.onNodeWithTag(BackupPassphraseTestTag).assertDoesNotExist()
        composeRule.onNodeWithText("Unlock").assertDoesNotExist()
    }

    @Test
    fun backupPreviewAndResultExposePortableCustomThemeCounts() {
        var state by mutableStateOf(
            BackupWorkflowUiState(
                step = BackupWorkflowStep.COMPLETED,
                completionKind = BackupCompletionKind.EXPORT,
                exportResult = BackupExportUiResult(
                    mode = BackupMode.FULL,
                    bytesWritten = 1024,
                    content = BackupContentSummary(
                        hosts = 0,
                        credentials = 0,
                        sshKeys = 0,
                        knownHosts = 0,
                        snippets = 0,
                        terminalProfiles = 1,
                        terminalThemes = 2,
                        keyboardProfiles = 3,
                        portableCredentialSecrets = 2,
                        portablePrivateKeys = 2,
                        customFonts = 4,
                    ),
                ),
                archive = BackupArchiveUiSummary(
                    header = BackupHeaderUiSummary(
                        mode = BackupMode.STANDARD,
                        createdAtEpochMillis = 1L,
                        appVersionName = "test",
                        payloadSchemaVersion = 1,
                    ),
                    content = BackupContentSummary(
                        hosts = 0,
                        credentials = 0,
                        sshKeys = 0,
                        knownHosts = 0,
                        snippets = 0,
                        terminalProfiles = 1,
                        terminalThemes = 2,
                        keyboardProfiles = 3,
                        portableCredentialSecrets = 2,
                        portablePrivateKeys = 2,
                        customFonts = 4,
                    ),
                    incompatibleRecords = 0,
                    skippedRecords = 0,
                    unresolvedReferences = 0,
                ),
            ),
        )
        composeRule.setContent {
            MaterialTheme { BackupWorkflowDialogs(state, BackupSettingsActions.NONE) }
        }

        composeRule.onNodeWithText(
            "1 terminal profiles · 2 terminal themes · 3 keyboard profiles · 4 portable secrets · 4 custom fonts",
        ).assertIsDisplayed()

        composeRule.runOnIdle {
            state = BackupWorkflowUiState(
                step = BackupWorkflowStep.COMPLETED,
                completionKind = BackupCompletionKind.IMPORT,
                importResult = BackupImportResult(
                    strategy = BackupImportStrategy.MERGE,
                    hostsApplied = 0,
                    credentialsApplied = 0,
                    sshKeysApplied = 0,
                    knownHostsApplied = 0,
                    snippetsApplied = 0,
                    terminalProfilesApplied = 1,
                    terminalThemesApplied = 2,
                    keyboardProfilesApplied = 3,
                    unavailableSecretPlaceholders = 0,
                    skippedRecords = 0,
                    incompatibleRecords = 0,
                    recoveryMarkerRemoved = true,
                    customFontsApplied = 4,
                ),
            )
        }
        composeRule.onNodeWithText(
            "Applied 1 terminal profiles, 2 terminal themes, 3 keyboard profiles and 4 custom fonts.",
        ).assertIsDisplayed()
    }

    @Test
    fun dataManagersUseSettingsDetailsWhileAdvancedSecurityStaysHidden() {
        var sshKeysOpened = false
        var snippetsOpened = false
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(412.dp, 900.dp)) {
                    SettingsScreen(
                        state = completeState(),
                        initialCategory = null,
                        knownHosts = emptyList(),
                        moshExtension = MoshExtensionStatus.Absent.toUiState(),
                        actions = noOpActions(),
                        onForgetKnownHost = { _, _ -> },
                        onRefreshMoshExtension = {},
                        onOpenRendererLab = {},
                        onNavigateBack = {},
                        onOpenWorkspace = {},
                        onOpenTerminal = {},
                        onOpenSettings = {},
                        onOpenSshKeys = { sshKeysOpened = true },
                        onOpenSnippets = { snippetsOpened = true },
                        onDismissMessage = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Security & privacy").assertDoesNotExist()
        composeRule.onNodeWithText("SSH keys").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Manage SSH keys").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(sshKeysOpened) }

        composeRule.onNodeWithContentDescription("Open settings").performClick()
        composeRule.onNodeWithText("Snippets").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Manage snippets").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(snippetsOpened) }
    }

    private fun settingsRootLuminance(): Float = composeRule.onNodeWithTag("settings-visual-root")
        .captureToImage()
        .toPixelMap()[1, 1]
        .luminance()
}

@Composable
private fun TestSettingsScreen(modifier: Modifier = Modifier) {
    Box(modifier) {
        SettingsScreen(
            state = completeState(),
            initialCategory = null,
            knownHosts = emptyList(),
            moshExtension = MoshExtensionStatus.Absent.toUiState(),
            actions = noOpActions(),
            onForgetKnownHost = { _, _ -> },
            onRefreshMoshExtension = {},
            onOpenRendererLab = {},
            onNavigateBack = {},
            onOpenWorkspace = {},
            onOpenTerminal = {},
            onOpenSettings = {},
            onDismissMessage = {},
            safeContentInsets = WindowInsets(0),
        )
    }
}

private fun completeState(): SettingsUiState {
    val keyboardPreset = KeyboardPresets.general
    return SettingsUiState(
        terminalProfile = TerminalProfile(
            id = "df558cdb-05fb-50f3-baf9-e7dd6e911ce5",
            name = "Default terminal",
            themeId = "current",
            fontId = SettingsViewModel.SYSTEM_MONOSPACE_FONT_ID,
            fontSizeSp = 14f,
            lineHeightMultiplier = 1f,
            letterSpacingEm = 0f,
            cursorStyle = CursorStyle.BLOCK,
            scrollbackLines = 20_000,
            bell = BellSettings(),
            scroll = ScrollBehavior(),
            links = LinkBehavior(),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        ),
        keyboardProfile = KeyboardProfile(
            id = "f23f85fd-3122-5f8b-b28a-d0320f402866",
            name = "Default keyboard",
            orderedActions = keyboardPreset.actions,
            layout = keyboardPreset.layout,
            modifierBehavior = keyboardPreset.modifierBehavior,
            hapticFeedbackEnabled = keyboardPreset.hapticFeedbackEnabled,
            keyRepeatEnabled = keyboardPreset.keyRepeatEnabled,
            inputMode = keyboardPreset.inputMode,
            tmuxPrefix = keyboardPreset.tmuxPrefix,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        ),
        profilesLoading = false,
    )
}

private fun noOpActions() = SettingsActions(
    onAppearanceMode = {},
    onDynamicColor = {},
    onAccent = {},
    onTerminalTheme = {},
    onTerminalFont = {},
    onImportFont = {},
    onFontSize = {},
    onLineHeight = {},
    onLetterSpacing = {},
    onResetAppearance = {},
    onScrollback = {},
    onCursorStyle = {},
    onCursorBlink = {},
    onBell = {},
    onLinks = {},
    onScrollBehavior = {},
    onTermValue = {},
    onAlternateHistory = {},
    onMultilinePasteConfirmation = {},
    onResetTerminal = {},
    onKeyboardPreset = {},
    onKeyboardActions = {},
    onKeyboardLayout = {},
    onModifierBehavior = {},
    onKeyboardHaptics = {},
    onKeyRepeat = {},
    onInputMode = {},
    onTmuxPrefix = {},
    onResetKeyboard = {},
    onKeepScreenOn = {},
    onAppLockMode = {},
    onAppLockDelay = {},
    onScreenshotBlocking = {},
)

private fun customTheme() = CustomTerminalTheme(
    id = "10000000-0000-4000-8000-000000000001",
    name = "Ocean",
    foregroundArgb = 0xffeeeeee.toInt(),
    backgroundArgb = 0xff101820.toInt(),
    cursorArgb = 0xffffffff.toInt(),
    selectionArgb = 0xff304050.toInt(),
    ansi16Argb = List(16) { index -> 0xff000000.toInt() or index },
    boldUsesBrightColours = false,
    createdAtEpochMillis = 1,
    updatedAtEpochMillis = 2,
)
