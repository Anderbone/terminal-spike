package com.yanjiyu.terminalspike.core.security.applock

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class AppLockMode {
    OFF,
    IMMEDIATE,
    DELAYED,
    ON_BACKGROUND,
}

internal data class AppLockPolicy(
    val mode: AppLockMode,
    val delayMillis: Long = 0L,
) {
    init {
        require(delayMillis >= 0L) { "App-lock delay cannot be negative." }
    }
}

internal enum class AppLockAvailability {
    AVAILABLE,
    DEVICE_CREDENTIAL_NOT_CONFIGURED,
    UNSUPPORTED,
}

internal enum class AppLockAuthenticationFailure {
    CANCELLED,
    ERROR,
}

internal sealed interface AppLockUiState {
    data object SettingsLoading : AppLockUiState
    data object SettingsUnavailable : AppLockUiState
    data object Unlocked : AppLockUiState
    data object Obscured : AppLockUiState
    data object AuthenticationRequired : AppLockUiState
    data object Authenticating : AppLockUiState
    data class AuthenticationFailed(val failure: AppLockAuthenticationFailure) : AppLockUiState
    data class Unavailable(val availability: AppLockAvailability) : AppLockUiState
}

/**
 * Process-local app-lock state machine. It never mutates settings or user data and treats missing
 * settings, missing device security and authentication errors as locked states.
 */
internal class AppLockController {
    private val _state = MutableStateFlow<AppLockUiState>(AppLockUiState.SettingsLoading)
    val state: StateFlow<AppLockUiState> = _state.asStateFlow()

    private var policy: AppLockPolicy? = null
    private var availability: AppLockAvailability = AppLockAvailability.UNSUPPORTED
    private var resumed = false
    private var authenticated = false
    private var authenticating = false
    private var backgroundedAtMillis: Long? = null
    private var authenticationFailure: AppLockAuthenticationFailure? = null

    val isEnabled: Boolean
        get() = policy?.mode?.let { it != AppLockMode.OFF } == true

    fun updatePolicy(
        updated: AppLockPolicy,
        updatedAvailability: AppLockAvailability,
        nowMillis: Long,
    ) {
        require(nowMillis >= 0L) { "Monotonic time cannot be negative." }
        val previous = policy
        policy = updated
        availability = updatedAvailability

        if (updated.mode == AppLockMode.OFF) {
            authenticated = true
            authenticating = false
            authenticationFailure = null
            backgroundedAtMillis = null
        } else {
            val newlyEnabled = previous == null || previous.mode == AppLockMode.OFF
            if (newlyEnabled || updatedAvailability != AppLockAvailability.AVAILABLE) {
                authenticated = false
                authenticating = false
                authenticationFailure = null
            }
        }
        publish(nowMillis)
    }

    fun updateAvailability(updated: AppLockAvailability, nowMillis: Long) {
        require(nowMillis >= 0L) { "Monotonic time cannot be negative." }
        if (availability == updated) return
        val wasUnavailable = availability != AppLockAvailability.AVAILABLE
        availability = updated
        if (isEnabled && updated != AppLockAvailability.AVAILABLE) {
            authenticated = false
            authenticating = false
            authenticationFailure = null
        } else if (isEnabled && wasUnavailable) {
            authenticationFailure = null
        }
        publish(nowMillis)
    }

    fun onSettingsUnavailable() {
        policy = null
        authenticated = false
        authenticating = false
        backgroundedAtMillis = null
        authenticationFailure = null
        _state.value = AppLockUiState.SettingsUnavailable
    }

    fun onResume(nowMillis: Long) {
        require(nowMillis >= 0L) { "Monotonic time cannot be negative." }
        resumed = true
        val currentPolicy = policy
        if (
            currentPolicy?.mode == AppLockMode.DELAYED &&
            authenticated &&
            backgroundedAtMillis?.let { elapsedSince(it, nowMillis) >= currentPolicy.delayMillis } == true
        ) {
            authenticated = false
            authenticationFailure = null
        }
        backgroundedAtMillis = null
        publish(nowMillis)
    }

    fun onPause(nowMillis: Long, changingConfigurations: Boolean) {
        require(nowMillis >= 0L) { "Monotonic time cannot be negative." }
        resumed = false
        if (
            !changingConfigurations &&
            !authenticating &&
            policy?.mode == AppLockMode.IMMEDIATE
        ) {
            authenticated = false
            authenticationFailure = null
        }
        publish(nowMillis)
    }

    fun onStop(nowMillis: Long, changingConfigurations: Boolean) {
        require(nowMillis >= 0L) { "Monotonic time cannot be negative." }
        if (!changingConfigurations && !authenticating) {
            when (policy?.mode) {
                AppLockMode.ON_BACKGROUND -> {
                    authenticated = false
                    authenticationFailure = null
                }
                AppLockMode.DELAYED -> backgroundedAtMillis = nowMillis
                else -> Unit
            }
        }
        publish(nowMillis)
    }

    fun beginAuthentication(nowMillis: Long): Boolean {
        require(nowMillis >= 0L) { "Monotonic time cannot be negative." }
        val mayAuthenticate = resumed &&
            isEnabled &&
            availability == AppLockAvailability.AVAILABLE &&
            !authenticated &&
            !authenticating
        if (!mayAuthenticate) return false
        authenticating = true
        authenticationFailure = null
        publish(nowMillis)
        return true
    }

    fun authenticationSucceeded(nowMillis: Long) {
        require(nowMillis >= 0L) { "Monotonic time cannot be negative." }
        if (!authenticating || !isEnabled) return
        authenticating = false
        authenticated = true
        authenticationFailure = null
        backgroundedAtMillis = null
        publish(nowMillis)
    }

    fun authenticationFailed(failure: AppLockAuthenticationFailure, nowMillis: Long) {
        require(nowMillis >= 0L) { "Monotonic time cannot be negative." }
        if (!authenticating || !isEnabled) return
        authenticating = false
        authenticated = false
        authenticationFailure = failure
        publish(nowMillis)
    }

    private fun publish(nowMillis: Long) {
        val currentPolicy = policy
        _state.value = when {
            currentPolicy == null -> AppLockUiState.SettingsLoading
            currentPolicy.mode == AppLockMode.OFF -> AppLockUiState.Unlocked
            availability != AppLockAvailability.AVAILABLE -> AppLockUiState.Unavailable(availability)
            !resumed -> AppLockUiState.Obscured
            authenticating -> AppLockUiState.Authenticating
            authenticated -> AppLockUiState.Unlocked
            authenticationFailure != null -> AppLockUiState.AuthenticationFailed(authenticationFailure!!)
            else -> AppLockUiState.AuthenticationRequired
        }
        // Validate monotonic input on every state-changing path, including simple publishes.
        check(nowMillis >= 0L)
    }

    private fun elapsedSince(thenMillis: Long, nowMillis: Long): Long =
        if (nowMillis >= thenMillis) nowMillis - thenMillis else Long.MAX_VALUE
}

/** Retains one authenticated session across configuration changes, but not process death. */
internal class AppLockViewModel : ViewModel() {
    val controller = AppLockController()
}
