package com.yanjiyu.terminalspike

import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeDataState
import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeRecoveryFailure
import com.yanjiyu.terminalspike.core.security.applock.AppLockUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityStartupGateTest {
    @Test
    fun appLockStaysInFrontOfUnresolvedAuthority() {
        assertEquals(
            MainRoot.APP_LOCK,
            selectMainRoot(
                settingsLoaded = true,
                lockState = AppLockUiState.AuthenticationRequired,
                authorityState = AuthoritativeDataState.RecoveryRequired,
            ),
        )
    }

    @Test
    fun settingsLoadingStaysInFrontOfRecoveryFailure() {
        assertEquals(
            MainRoot.APP_LOCK,
            selectMainRoot(
                settingsLoaded = false,
                lockState = AppLockUiState.SettingsLoading,
                authorityState = recoveryFailed(),
            ),
        )
    }

    @Test
    fun unlockedAppShowsOnlyRecoveryUntilAuthorityIsReady() {
        assertEquals(
            MainRoot.STARTUP_RECOVERY,
            selectMainRoot(
                settingsLoaded = true,
                lockState = AppLockUiState.Unlocked,
                authorityState = AuthoritativeDataState.Checking,
            ),
        )
        assertEquals(
            MainRoot.STARTUP_RECOVERY,
            selectMainRoot(
                settingsLoaded = true,
                lockState = AppLockUiState.Unlocked,
                authorityState = recoveryFailed(),
            ),
        )
        assertEquals(
            MainRoot.CONTENT,
            selectMainRoot(
                settingsLoaded = true,
                lockState = AppLockUiState.Unlocked,
                authorityState = AuthoritativeDataState.Ready(generation = 1L),
            ),
        )
    }

    @Test
    fun inProcessRecoveryRequirementStartsTheSameResolver() {
        assertTrue(shouldResolveStartup(AuthoritativeDataState.RecoveryRequired))
        assertFalse(shouldResolveStartup(AuthoritativeDataState.Checking))
        assertFalse(shouldResolveStartup(recoveryFailed()))
        assertFalse(shouldResolveStartup(AuthoritativeDataState.Ready(generation = 1L)))
    }

    @Test
    fun settingsRetryAlsoRetriesAHiddenRecoveryFailure() {
        assertTrue(shouldRetryStartupFromSettingsGate(recoveryFailed()))
        assertFalse(
            shouldRetryStartupFromSettingsGate(AuthoritativeDataState.RecoveryRequired),
        )
        assertFalse(
            shouldRetryStartupFromSettingsGate(AuthoritativeDataState.Ready(generation = 1L)),
        )
    }

    @Test
    fun disconnectAllConfirmationWaitsForEveryGateAndCurrentSessions() {
        assertFalse(shouldShowDisconnectAllConfirmation(false, MainRoot.CONTENT, 2))
        assertFalse(shouldShowDisconnectAllConfirmation(true, MainRoot.APP_LOCK, 2))
        assertFalse(shouldShowDisconnectAllConfirmation(true, MainRoot.STARTUP_RECOVERY, 2))
        assertFalse(shouldShowDisconnectAllConfirmation(true, MainRoot.CONTENT, 0))
        assertTrue(shouldShowDisconnectAllConfirmation(true, MainRoot.CONTENT, 2))
    }

    @Test
    fun unresolvedAndFailedAuthorityAlwaysSecureTheWindow() {
        listOf(
            AuthoritativeDataState.Checking,
            AuthoritativeDataState.RecoveryRequired,
            recoveryFailed(),
        ).forEach { state ->
            assertTrue(
                shouldSecureMainWindow(
                    settingsLoaded = true,
                    screenshotBlockingEnabled = false,
                    lockState = AppLockUiState.Unlocked,
                    authorityState = state,
                ),
            )
        }
    }

    @Test
    fun readyUnlockedWindowFollowsScreenshotPreference() {
        val ready = AuthoritativeDataState.Ready(generation = 2L)

        assertFalse(
            shouldSecureMainWindow(
                settingsLoaded = true,
                screenshotBlockingEnabled = false,
                lockState = AppLockUiState.Unlocked,
                authorityState = ready,
            ),
        )
        assertTrue(
            shouldSecureMainWindow(
                settingsLoaded = true,
                screenshotBlockingEnabled = true,
                lockState = AppLockUiState.Unlocked,
                authorityState = ready,
            ),
        )
    }

    private fun recoveryFailed() = AuthoritativeDataState.RecoveryFailed(
        AuthoritativeRecoveryFailure.RECOVERY_UNAVAILABLE,
    )
}
