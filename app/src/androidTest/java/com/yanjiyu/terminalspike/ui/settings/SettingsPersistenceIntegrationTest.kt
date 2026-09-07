package com.yanjiyu.terminalspike.ui.settings

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPersistenceIntegrationTest {
    @Test
    fun globalTerminalAndKeyboardChangesSurviveFreshActivityAndViewModel() = runBlocking {
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        val first = scenario.settingsViewModel()
        first.awaitReady()
        val original = PersistedSettingsSample(
            accentPreset = first.uiState.value.preferences.accentPreset,
            fontSizeSp = requireNotNull(first.uiState.value.terminalProfile).fontSizeSp,
            modifierBehavior = requireNotNull(first.uiState.value.keyboardProfile).modifierBehavior,
        )
        val changed = PersistedSettingsSample(
            accentPreset = if (original.accentPreset == AccentPreset.CORAL) {
                AccentPreset.BLUE
            } else {
                AccentPreset.CORAL
            },
            fontSizeSp = if (original.fontSizeSp == 17f) 18f else 17f,
            modifierBehavior = if (
                original.modifierBehavior == ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK
            ) {
                ModifierBehavior.ONE_SHOT
            } else {
                ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK
            },
        )

        try {
            first.setAccentPreset(changed.accentPreset)
            first.updateFontSize(changed.fontSizeSp)
            first.updateModifierBehavior(changed.modifierBehavior)
            first.awaitSample(changed)

            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val recreated = scenario.settingsViewModel()
            recreated.awaitReady()
            recreated.awaitSample(changed)
            assertFalse(recreated.uiState.value.writeInProgress)
        } finally {
            val restoring = scenario.settingsViewModel()
            restoring.awaitReady()
            restoring.setAccentPreset(original.accentPreset)
            restoring.updateFontSize(original.fontSizeSp)
            restoring.updateModifierBehavior(original.modifierBehavior)
            restoring.awaitSample(original)
            scenario.close()
        }
    }

    private fun ActivityScenario<MainActivity>.settingsViewModel(): SettingsViewModel {
        var model: SettingsViewModel? = null
        onActivity { activity ->
            model = ViewModelProvider(activity)[SettingsViewModel::class.java]
        }
        return requireNotNull(model)
    }

    private suspend fun SettingsViewModel.awaitReady() = withTimeout(TIMEOUT_MILLIS) {
        while (uiState.value.profilesLoading || uiState.value.terminalProfile == null ||
            uiState.value.keyboardProfile == null
        ) {
            delay(25L)
        }
    }

    private suspend fun SettingsViewModel.awaitSample(expected: PersistedSettingsSample) =
        withTimeout(TIMEOUT_MILLIS) {
            while (true) {
                val state = uiState.value
                if (
                    !state.writeInProgress &&
                    state.preferences.accentPreset == expected.accentPreset &&
                    state.terminalProfile?.fontSizeSp == expected.fontSizeSp &&
                    state.keyboardProfile?.modifierBehavior == expected.modifierBehavior
                ) {
                    break
                }
                delay(25L)
            }
            assertEquals(expected.accentPreset, uiState.value.preferences.accentPreset)
            assertEquals(expected.fontSizeSp, uiState.value.terminalProfile?.fontSizeSp)
            assertEquals(expected.modifierBehavior, uiState.value.keyboardProfile?.modifierBehavior)
        }

    private data class PersistedSettingsSample(
        val accentPreset: AccentPreset,
        val fontSizeSp: Float,
        val modifierBehavior: ModifierBehavior,
    )

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
    }
}
