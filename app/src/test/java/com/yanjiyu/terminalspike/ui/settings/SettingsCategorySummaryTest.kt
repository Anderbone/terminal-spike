package com.yanjiyu.terminalspike.ui.settings

import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.ui.uiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SettingsCategorySummaryTest {
    @Test
    fun summariesProjectCurrentCommittedSettings() {
        val initial = SettingsUiState()
        val updated = initial.copy(
            preferences = initial.preferences.copy(
                keepaliveIntervalSeconds = 90,
                reconnectEnabled = true,
                keepCpuAwake = true,
                appLockMode = AppLockModeOption.IMMEDIATE,
                screenshotBlockingEnabled = true,
            ),
        )

        assertNotEquals(
            settingsCategorySummary(SettingsCategory.SESSIONS_BACKGROUND, initial),
            settingsCategorySummary(SettingsCategory.SESSIONS_BACKGROUND, updated),
        )
        assertNotEquals(
            settingsCategorySummary(SettingsCategory.SECURITY, initial),
            settingsCategorySummary(SettingsCategory.SECURITY, updated),
        )
    }

    @Test
    fun reconstructedStateProducesTheSameLandingSummary() {
        val preferences = AppPreferences(
            notificationPrivacyEnabled = false,
            disconnectNotificationsEnabled = true,
        )

        assertEquals(
            settingsCategorySummary(
                SettingsCategory.NOTIFICATIONS,
                SettingsUiState(preferences = preferences),
            ),
            settingsCategorySummary(
                SettingsCategory.NOTIFICATIONS,
                SettingsUiState(preferences = preferences.copy()),
            ),
        )
    }

    @Test
    fun categoriesWithoutCommittedControlsKeepTheirResourceSummary() {
        assertEquals(
            uiText(R.string.settings_category_about_summary),
            settingsCategorySummary(SettingsCategory.ABOUT, SettingsUiState()),
        )
    }
}
