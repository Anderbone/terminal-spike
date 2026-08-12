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
    fun settingsIndexContainsEveryRequiredProductionCategory() {
        assertEquals(
            listOf(
                SettingsCategory.APPEARANCE,
                SettingsCategory.TERMINAL,
                SettingsCategory.KEYBOARD,
                SettingsCategory.SESSIONS_BACKGROUND,
                SettingsCategory.NOTIFICATIONS,
                SettingsCategory.BACKUP_RESTORE,
                SettingsCategory.SECURITY,
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
            settingsCategoriesForSearch("tmux keys", includeDeveloper = false),
        )
        assertEquals(
            listOf(SettingsCategory.BACKUP_RESTORE),
            settingsCategoriesForSearch("encrypted restore", includeDeveloper = false),
        )
        assertTrue(settingsCategoriesForSearch("developer", includeDeveloper = false).isEmpty())
        assertEquals(
            listOf(SettingsCategory.DEVELOPER),
            settingsCategoriesForSearch("renderer diagnostics", includeDeveloper = true),
        )
    }

    @Test
    fun generalKeyboardPresetIsExactlyNineKeysPerRow() {
        val preset = KeyboardPresets.general
        assertEquals(18, preset.actions.size)
        assertEquals(2, preset.layout.rowCount)
        assertEquals(9, preset.actions.size / preset.layout.rowCount)
        assertEquals(preset.actions.size, preset.actions.distinct().size)
        assertTrue(KeyboardAction.CTRL_C in preset.actions)
        assertTrue(KeyboardAction.CTRL_W in preset.actions)
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

            assertEquals(preset.actions.size, profile.toRuntimeExtraKeysOrNull()?.size)
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
