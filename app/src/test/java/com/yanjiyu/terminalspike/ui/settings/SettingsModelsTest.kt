package com.yanjiyu.terminalspike.ui.settings

import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsSerializer
import com.yanjiyu.terminalspike.core.data.settings.CURRENT_KEYBOARD_DECK_REVISION
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryAction
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryModifier
import com.yanjiyu.terminalspike.terminal.view.TerminalLocalAccessoryAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelsTest {
    @Test
    fun voiceInputLanguageUsesStableBcp47TagsAndSafeFallback() {
        assertEquals(VoiceInputLanguage.ENGLISH_UK, VoiceInputLanguage.fromLanguageTag("en-GB"))
        assertEquals(VoiceInputLanguage.CANTONESE, VoiceInputLanguage.fromLanguageTag("zh-HK"))
        assertEquals(VoiceInputLanguage.DEVICE_DEFAULT, VoiceInputLanguage.fromLanguageTag("unknown"))
        assertEquals(VoiceInputLanguage.entries.size, VoiceInputLanguage.entries.map { it.languageTag }.distinct().size)
    }

    @Test
    fun settingsIndexContainsEveryRequiredProductionCategory() {
        assertEquals(
            listOf(
                SettingsCategory.APPEARANCE,
                SettingsCategory.TERMINAL,
                SettingsCategory.KEYBOARD,
                SettingsCategory.SSH_KEYS,
                SettingsCategory.SNIPPETS,
                SettingsCategory.SESSIONS_BACKGROUND,
                SettingsCategory.NOTIFICATIONS,
                SettingsCategory.BACKUP_RESTORE,
                SettingsCategory.SECURITY,
                SettingsCategory.LOCAL_ARCH,
                SettingsCategory.MOSH,
                SettingsCategory.ABOUT,
                SettingsCategory.DEVELOPER,
            ),
            SettingsCategory.entries,
        )
    }

    @Test
    fun settingsSearchUsesAllTermsAndKeepsDeveloperDebugOnly() {
        assertEquals(
            listOf(SettingsCategory.KEYBOARD),
            settingsCategoriesForSearch("keyboard keys", includeDeveloper = false),
        )
        assertEquals(
            listOf(SettingsCategory.BACKUP_RESTORE),
            settingsCategoriesForSearch("backup restore", includeDeveloper = false),
        )
        assertTrue(settingsCategoriesForSearch("developer", includeDeveloper = false).isEmpty())
        assertEquals(
            listOf(SettingsCategory.DEVELOPER),
            settingsCategoriesForSearch("renderer diagnostics", includeDeveloper = true),
        )
        assertTrue(SettingsCategory.SECURITY in settingsCategoriesForSearch("", includeDeveloper = true))
        assertTrue(SettingsCategory.SSH_KEYS in settingsCategoriesForSearch("", includeDeveloper = true))
        assertTrue(SettingsCategory.SNIPPETS in settingsCategoriesForSearch("", includeDeveloper = true))
    }

    @Test
    fun generalKeyboardPresetIsExactlyTenKeysPerRow() {
        val preset = KeyboardPresets.general
        assertEquals(
            listOf(
                KeyboardAction.ESCAPE,
                KeyboardAction.SLASH,
                KeyboardAction.AT_SIGN,
                KeyboardAction.DOLLAR,
                KeyboardAction.SELECT_IMAGES,
                KeyboardAction.HOME,
                KeyboardAction.ARROW_UP,
                KeyboardAction.END,
                KeyboardAction.PAGE_UP,
                KeyboardAction.BACKSPACE,
                KeyboardAction.TAB,
                KeyboardAction.CONTROL,
                KeyboardAction.TMUX_SESSIONS,
                KeyboardAction.CTRL_C,
                KeyboardAction.CTRL_W,
                KeyboardAction.ARROW_LEFT,
                KeyboardAction.ARROW_DOWN,
                KeyboardAction.ARROW_RIGHT,
                KeyboardAction.ENTER,
                KeyboardAction.HIDE_KEYBOARD,
            ),
            preset.actions,
        )
        assertEquals(20, preset.actions.size)
        assertEquals(2, preset.layout.rowCount)
        assertEquals(10, preset.actions.size / preset.layout.rowCount)
        assertEquals(preset.actions.size, preset.actions.distinct().size)
        assertTrue(KeyboardAction.CTRL_C in preset.actions)
        assertTrue(KeyboardAction.CTRL_W in preset.actions)
        assertEquals(
            preset.actions.indexOf(KeyboardAction.HOME) - 1,
            preset.actions.indexOf(KeyboardAction.SELECT_IMAGES),
        )
        val runtime = KeyboardProfile(
            id = "f23f85fd-3122-5f8b-b28a-d0320f402866",
            name = "Default runtime",
            orderedActions = preset.actions,
            layout = preset.layout,
            modifierBehavior = preset.modifierBehavior,
            hapticFeedbackEnabled = preset.hapticFeedbackEnabled,
            keyRepeatEnabled = preset.keyRepeatEnabled,
            inputMode = preset.inputMode,
            tmuxPrefix = preset.tmuxPrefix,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        ).toRuntimeAccessoryActionsOrNull()
        assertEquals(
            TerminalLocalAccessoryAction.SELECT_IMAGES,
            (runtime?.get(4) as TerminalAccessoryAction.Local).action,
        )
        assertEquals(
            TerminalLocalAccessoryAction.TMUX_SESSIONS,
            (runtime[12] as TerminalAccessoryAction.Local).action,
        )
    }

    @Test
    fun tmuxPresetRemainsIndependentFromTheGeneralDeck() {
        assertTrue(KeyboardAction.TMUX_PREFIX in KeyboardPresets.tmuxCodex.actions)
        assertFalse(KeyboardPresets.tmuxCodex.actions == KeyboardPresets.general.actions)
    }

    @Test
    fun shippedKeyboardPresetsMapToRealRuntimeKeys() {
        KeyboardPresets.selectable.forEach { preset ->
            val profile = KeyboardProfile(
                id = "f23f85fd-3122-5f8b-b28a-d0320f402866",
                name = "Preset",
                orderedActions = preset.actions,
                layout = preset.layout,
                modifierBehavior = preset.modifierBehavior,
                hapticFeedbackEnabled = preset.hapticFeedbackEnabled,
                keyRepeatEnabled = preset.keyRepeatEnabled,
                inputMode = preset.inputMode,
                tmuxPrefix = preset.tmuxPrefix,
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
            )

            assertEquals(preset.actions.size, profile.toRuntimeAccessoryActionsOrNull()?.size)
        }
    }

    @Test
    fun everyPersistedActionMapsWithoutCollapsingLocalActionsIntoBytes() {
        val profile = keyboardProfile(
            actions = listOf(
                KeyboardAction.ESCAPE,
                KeyboardAction.SHIFT,
                KeyboardAction.TMUX_PREFIX,
                KeyboardAction.PASTE,
                KeyboardAction.SNIPPETS,
                KeyboardAction.KEYBOARD_SETTINGS,
                KeyboardAction.HIDE_KEYBOARD,
            ),
        )

        val runtime = requireNotNull(profile.toRuntimeAccessoryActionsOrNull())

        assertEquals(profile.orderedActions.map(KeyboardAction::wireCode), runtime.map { it.stableId })
        assertEquals(TerminalAccessoryModifier.SHIFT, (runtime[1] as TerminalAccessoryAction.Modifier).modifier)
        assertTrue(runtime[2] is TerminalAccessoryAction.TmuxPrefix)
        assertEquals(TerminalLocalAccessoryAction.PASTE, (runtime[3] as TerminalAccessoryAction.Local).action)
        assertEquals(TerminalLocalAccessoryAction.SNIPPETS, (runtime[4] as TerminalAccessoryAction.Local).action)
        assertEquals(
            TerminalLocalAccessoryAction.KEYBOARD_SETTINGS,
            (runtime[5] as TerminalAccessoryAction.Local).action,
        )
        assertEquals(
            TerminalLocalAccessoryAction.HIDE_KEYBOARD,
            (runtime[6] as TerminalAccessoryAction.Local).action,
        )
    }

    @Test
    fun stableReorderPreservesExactActionSet() {
        val original = listOf(
            KeyboardAction.ESCAPE,
            KeyboardAction.CONTROL,
            KeyboardAction.PASTE,
            KeyboardAction.SNIPPETS,
        )

        val after = original
            .moveStableAction(KeyboardAction.PASTE.wireCode, KeyboardActionMove.BEFORE)
            .moveStableAction(KeyboardAction.PASTE.wireCode, KeyboardActionMove.AFTER)

        assertEquals(original, after)
        assertEquals(original.toSet(), after.toSet())
        assertEquals(original.size, after.size)
        assertEquals(
            original,
            original.moveStableAction(KeyboardAction.ESCAPE.wireCode, KeyboardActionMove.BEFORE),
        )
        assertEquals(original, original.moveStableAction("missing", KeyboardActionMove.AFTER))
    }

    @Test
    fun exactShippedProfileIsEligibleForOneTimeTwoRowUpgrade() {
        val preset = KeyboardPresets.general
        val profile = shippedOneRowProfile()

        assertTrue(profile.isUntouchedShippedKeyboardDeck())
        val plan = planKeyboardDeckMigration(
            completedRevision = 0,
            canonicalProfile = profile,
            nowEpochMillis = 2L,
        )
        assertTrue(plan.shouldRecordCompletion)
        assertEquals(preset.actions, plan.upgradedProfile?.orderedActions)
        assertEquals(KeyboardLayout.TWO_ROWS, plan.upgradedProfile?.layout)
        assertEquals(2L, plan.upgradedProfile?.updatedAtEpochMillis)

        val alreadyCompleted = planKeyboardDeckMigration(
            completedRevision = CURRENT_KEYBOARD_DECK_REVISION,
            canonicalProfile = profile,
            nowEpochMillis = 3L,
        )
        assertFalse(alreadyCompleted.shouldRecordCompletion)
        assertEquals(null, alreadyCompleted.upgradedProfile)
    }

    @Test
    fun previousUntouchedTwoRowGeneralPresetUpgradesToTheReferenceDeck() {
        val previousGeneral = listOf(
            KeyboardAction.ESCAPE,
            KeyboardAction.CONTROL,
            KeyboardAction.ALT,
            KeyboardAction.TAB,
            KeyboardAction.CTRL_C,
            KeyboardAction.CTRL_W,
            KeyboardAction.CTRL_D,
            KeyboardAction.CTRL_L,
            KeyboardAction.CTRL_R,
            KeyboardAction.CTRL_U,
            KeyboardAction.CTRL_A,
            KeyboardAction.CTRL_E,
            KeyboardAction.HOME,
            KeyboardAction.END,
            KeyboardAction.ARROW_UP,
            KeyboardAction.ARROW_DOWN,
            KeyboardAction.ARROW_LEFT,
            KeyboardAction.ARROW_RIGHT,
        )
        val profile = shippedOneRowProfile().copy(
            orderedActions = previousGeneral,
            layout = KeyboardLayout.TWO_ROWS,
            updatedAtEpochMillis = 5L,
        )

        val plan = planKeyboardDeckMigration(
            completedRevision = CURRENT_KEYBOARD_DECK_REVISION - 1,
            canonicalProfile = profile,
            nowEpochMillis = 6L,
        )

        assertTrue(plan.shouldRecordCompletion)
        assertEquals(KeyboardAction.DEFAULT_ORDER, plan.upgradedProfile?.orderedActions)
        assertEquals(KeyboardLayout.TWO_ROWS, plan.upgradedProfile?.layout)
        assertEquals(6L, plan.upgradedProfile?.updatedAtEpochMillis)
    }

    @Test
    fun previousEighteenKeyReferenceDeckUpgradesToTenKeysPerRow() {
        val previousReferenceDeck = listOf(
            KeyboardAction.ESCAPE,
            KeyboardAction.SLASH,
            KeyboardAction.AT_SIGN,
            KeyboardAction.DOLLAR,
            KeyboardAction.HOME,
            KeyboardAction.ARROW_UP,
            KeyboardAction.END,
            KeyboardAction.PAGE_UP,
            KeyboardAction.PASTE,
            KeyboardAction.TAB,
            KeyboardAction.CONTROL,
            KeyboardAction.CTRL_C,
            KeyboardAction.CTRL_W,
            KeyboardAction.ARROW_LEFT,
            KeyboardAction.ARROW_DOWN,
            KeyboardAction.ARROW_RIGHT,
            KeyboardAction.ENTER,
            KeyboardAction.HIDE_KEYBOARD,
        )
        val profile = shippedOneRowProfile().copy(
            orderedActions = previousReferenceDeck,
            layout = KeyboardLayout.TWO_ROWS,
            updatedAtEpochMillis = 5L,
        )

        val plan = planKeyboardDeckMigration(
            completedRevision = CURRENT_KEYBOARD_DECK_REVISION - 1,
            canonicalProfile = profile,
            nowEpochMillis = 6L,
        )

        assertTrue(plan.shouldRecordCompletion)
        assertEquals(KeyboardAction.DEFAULT_ORDER, plan.upgradedProfile?.orderedActions)
    }

    @Test
    fun shippedTmuxAndPasteDeckReplacesPasteWithImageSelectionBeforeHome() {
        val previousReferenceDeck = listOf(
            KeyboardAction.ESCAPE,
            KeyboardAction.SLASH,
            KeyboardAction.AT_SIGN,
            KeyboardAction.DOLLAR,
            KeyboardAction.PASTE,
            KeyboardAction.HOME,
            KeyboardAction.ARROW_UP,
            KeyboardAction.END,
            KeyboardAction.PAGE_UP,
            KeyboardAction.BACKSPACE,
            KeyboardAction.TAB,
            KeyboardAction.CONTROL,
            KeyboardAction.TMUX_SESSIONS,
            KeyboardAction.CTRL_C,
            KeyboardAction.CTRL_W,
            KeyboardAction.ARROW_LEFT,
            KeyboardAction.ARROW_DOWN,
            KeyboardAction.ARROW_RIGHT,
            KeyboardAction.ENTER,
            KeyboardAction.HIDE_KEYBOARD,
        )
        val profile = shippedOneRowProfile().copy(
            orderedActions = previousReferenceDeck,
            layout = KeyboardLayout.TWO_ROWS,
            updatedAtEpochMillis = 5L,
        )

        val plan = planKeyboardDeckMigration(
            completedRevision = CURRENT_KEYBOARD_DECK_REVISION - 1,
            canonicalProfile = profile,
            nowEpochMillis = 6L,
        )

        assertTrue(plan.shouldRecordCompletion)
        assertEquals(KeyboardAction.DEFAULT_ORDER, plan.upgradedProfile?.orderedActions)
        assertEquals(
            plan.upgradedProfile?.orderedActions?.indexOf(KeyboardAction.HOME)?.minus(1),
            plan.upgradedProfile?.orderedActions?.indexOf(KeyboardAction.SELECT_IMAGES),
        )
        assertFalse(KeyboardAction.PASTE in plan.upgradedProfile?.orderedActions.orEmpty())
    }

    @Test
    fun everyPersistedCustomizationDisqualifiesAutomaticKeyboardRepair() {
        val profile = shippedOneRowProfile()
        val customizedProfiles = listOf(
            profile.copy(id = "bf6ae508-5142-481d-8623-eaa7afaf134f"),
            profile.copy(name = "My keyboard"),
            profile.copy(orderedActions = profile.orderedActions.reversed()),
            profile.copy(modifierBehavior = ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK),
            profile.copy(hapticFeedbackEnabled = true),
            profile.copy(keyRepeatEnabled = false),
            profile.copy(inputMode = TerminalInputMode.TEXT),
            profile.copy(tmuxPrefix = "C-a"),
            profile.copy(updatedAtEpochMillis = 2L),
        )

        customizedProfiles.forEach { customized ->
            assertFalse(customized.isUntouchedShippedKeyboardDeck())
            val plan = planKeyboardDeckMigration(
                completedRevision = 0,
                canonicalProfile = customized,
                nowEpochMillis = 3L,
            )
            assertTrue(plan.shouldRecordCompletion)
            assertEquals(null, plan.upgradedProfile)
        }
    }

    @Test
    fun presetMatchingDetectsPersistedCustomProfiles() {
        val general = KeyboardPresets.general
        val profile = KeyboardProfile(
            id = "f23f85fd-3122-5f8b-b28a-d0320f402866",
            name = "Default keyboard",
            orderedActions = general.actions,
            layout = general.layout,
            modifierBehavior = general.modifierBehavior,
            hapticFeedbackEnabled = general.hapticFeedbackEnabled,
            keyRepeatEnabled = general.keyRepeatEnabled,
            inputMode = general.inputMode,
            tmuxPrefix = general.tmuxPrefix,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )

        assertEquals(general.id, KeyboardPresets.selectedId(profile))
        assertEquals(
            "custom",
            KeyboardPresets.selectedId(profile.copy(orderedActions = general.actions.reversed())),
        )
    }

    @Test
    fun persistedGlobalSettingsMapToAppearanceAndPrivacyPreferences() {
        val settings = AppSettingsSerializer.defaultValue.toBuilder()
            .setThemeMode(AppSettings.ThemeMode.THEME_MODE_DARK)
            .setDynamicColorEnabled(true)
            .setAccentPreset("coral")
            .setKeepaliveIntervalSeconds(60)
            .setReconnectEnabled(true)
            .setReconnectMaxAttempts(7)
            .setKeepCpuAwake(true)
            .setNotificationPrivacyEnabled(false)
            .setDisconnectNotificationsEnabled(true)
            .setReconnectNotificationsEnabled(true)
            .setKeepScreenOnWhileTerminalVisible(true)
            .setAppLockMode(AppSettings.AppLockMode.APP_LOCK_MODE_DELAYED)
            .setAppLockDelaySeconds(60)
            .setScreenshotBlockingEnabled(true)
            .setSensitiveClipboardClearSeconds(60)
            .setMultilinePasteConfirmationEnabled(false)
            .build()

        val preferences = settings.toPreferences()

        assertEquals(AppearanceMode.DARK, preferences.appearanceMode)
        assertEquals(AccentPreset.CORAL, preferences.accentPreset)
        assertTrue(preferences.dynamicColorEnabled)
        assertEquals(60, preferences.keepaliveIntervalSeconds)
        assertTrue(preferences.reconnectEnabled)
        assertEquals(7, preferences.reconnectMaxAttempts)
        assertTrue(preferences.tmuxSessionSelectorEnabled)
        assertTrue(preferences.keepCpuAwake)
        assertFalse(preferences.notificationPrivacyEnabled)
        assertTrue(preferences.disconnectNotificationsEnabled)
        assertTrue(preferences.reconnectNotificationsEnabled)
        assertTrue(preferences.keepScreenOnWhileTerminalVisible)
        assertEquals(AppLockModeOption.DELAYED, preferences.appLockMode)
        assertEquals(60, preferences.appLockDelaySeconds)
        assertTrue(preferences.screenshotBlockingEnabled)
        assertEquals(60, preferences.sensitiveClipboardClearSeconds)
        assertFalse(preferences.multilinePasteConfirmationEnabled)
    }

    @Test
    fun tmuxSelectorUsesAnEnabledByDefaultWireSetting() {
        assertTrue(AppSettingsSerializer.defaultValue.toPreferences().tmuxSessionSelectorEnabled)
        assertFalse(
            AppSettingsSerializer.defaultValue.toBuilder()
                .setTmuxSessionSelectorDisabled(true)
                .build()
                .toPreferences()
                .tmuxSessionSelectorEnabled,
        )
    }

    private fun shippedOneRowProfile(): KeyboardProfile {
        val preset = KeyboardPresets.general
        return KeyboardProfile(
            id = "f23f85fd-3122-5f8b-b28a-d0320f402866",
            name = "Default",
            orderedActions = preset.actions,
            layout = KeyboardLayout.ONE_ROW,
            modifierBehavior = preset.modifierBehavior,
            hapticFeedbackEnabled = preset.hapticFeedbackEnabled,
            keyRepeatEnabled = preset.keyRepeatEnabled,
            inputMode = preset.inputMode,
            tmuxPrefix = preset.tmuxPrefix,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
    }

    private fun keyboardProfile(actions: List<KeyboardAction>): KeyboardProfile = KeyboardProfile(
        id = "f23f85fd-3122-5f8b-b28a-d0320f402866",
        name = "Accessory",
        orderedActions = actions,
        layout = KeyboardLayout.TWO_ROWS,
        modifierBehavior = ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK,
        hapticFeedbackEnabled = false,
        keyRepeatEnabled = true,
        inputMode = TerminalInputMode.RAW,
        tmuxPrefix = "C-b",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )
}
