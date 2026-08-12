package com.yanjiyu.terminalspike.core.security.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockControllerTest {
    @Test
    fun enabledPolicyStartsLockedAndUnlocksOnlyAfterSuccess() {
        val controller = resumedController(AppLockMode.ON_BACKGROUND)

        assertEquals(AppLockUiState.AuthenticationRequired, controller.state.value)
        assertTrue(controller.beginAuthentication(1L))
        assertEquals(AppLockUiState.Authenticating, controller.state.value)

        controller.authenticationSucceeded(2L)

        assertEquals(AppLockUiState.Unlocked, controller.state.value)
    }

    @Test
    fun immediatePolicyRelocksOnPauseButIgnoresItsOwnAuthenticationSurface() {
        val controller = resumedController(AppLockMode.IMMEDIATE)
        authenticate(controller)

        controller.onPause(nowMillis = 10L, changingConfigurations = false)
        assertEquals(AppLockUiState.Obscured, controller.state.value)
        controller.onResume(11L)
        assertEquals(AppLockUiState.AuthenticationRequired, controller.state.value)

        assertTrue(controller.beginAuthentication(12L))
        controller.onPause(nowMillis = 13L, changingConfigurations = false)
        controller.authenticationSucceeded(14L)
        controller.onResume(15L)
        assertEquals(AppLockUiState.Unlocked, controller.state.value)
    }

    @Test
    fun backgroundPolicyDoesNotRelockForPauseWithoutStop() {
        val controller = resumedController(AppLockMode.ON_BACKGROUND)
        authenticate(controller)

        controller.onPause(nowMillis = 10L, changingConfigurations = false)
        controller.onResume(11L)
        assertEquals(AppLockUiState.Unlocked, controller.state.value)

        controller.onPause(nowMillis = 12L, changingConfigurations = false)
        controller.onStop(nowMillis = 13L, changingConfigurations = false)
        controller.onResume(14L)
        assertEquals(AppLockUiState.AuthenticationRequired, controller.state.value)
    }

    @Test
    fun delayedPolicyUsesElapsedBackgroundTime() {
        val controller = resumedController(AppLockMode.DELAYED, delayMillis = 20L)
        authenticate(controller)

        controller.onPause(nowMillis = 10L, changingConfigurations = false)
        controller.onStop(nowMillis = 10L, changingConfigurations = false)
        controller.onResume(29L)
        assertEquals(AppLockUiState.Unlocked, controller.state.value)

        controller.onPause(nowMillis = 30L, changingConfigurations = false)
        controller.onStop(nowMillis = 30L, changingConfigurations = false)
        controller.onResume(50L)
        assertEquals(AppLockUiState.AuthenticationRequired, controller.state.value)
    }

    @Test
    fun missingCredentialAndSettingsFailureStayFailClosed() {
        val controller = AppLockController()
        controller.updatePolicy(
            AppLockPolicy(AppLockMode.DELAYED, 30_000L),
            AppLockAvailability.DEVICE_CREDENTIAL_NOT_CONFIGURED,
            0L,
        )
        controller.onResume(1L)

        assertEquals(
            AppLockUiState.Unavailable(AppLockAvailability.DEVICE_CREDENTIAL_NOT_CONFIGURED),
            controller.state.value,
        )
        assertFalse(controller.beginAuthentication(2L))

        controller.onSettingsUnavailable()
        assertEquals(AppLockUiState.SettingsUnavailable, controller.state.value)
    }

    @Test
    fun cancelledAuthenticationNeverRevealsContent() {
        val controller = resumedController(AppLockMode.ON_BACKGROUND)
        assertTrue(controller.beginAuthentication(1L))

        controller.authenticationFailed(AppLockAuthenticationFailure.CANCELLED, 2L)

        assertEquals(
            AppLockUiState.AuthenticationFailed(AppLockAuthenticationFailure.CANCELLED),
            controller.state.value,
        )
        assertTrue(controller.beginAuthentication(3L))
    }

    private fun resumedController(mode: AppLockMode, delayMillis: Long = 0L): AppLockController =
        AppLockController().also { controller ->
            controller.updatePolicy(
                AppLockPolicy(mode, delayMillis),
                AppLockAvailability.AVAILABLE,
                0L,
            )
            controller.onResume(0L)
        }

    private fun authenticate(controller: AppLockController) {
        assertTrue(controller.beginAuthentication(1L))
        controller.authenticationSucceeded(2L)
        assertEquals(AppLockUiState.Unlocked, controller.state.value)
    }
}
