package com.yanjiyu.terminalspike.core.security.applock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import com.yanjiyu.terminalspike.R

internal sealed interface AppLockAuthenticationResult {
    data object Succeeded : AppLockAuthenticationResult
    data object Cancelled : AppLockAuthenticationResult
    data object Error : AppLockAuthenticationResult
    data class Unavailable(val availability: AppLockAvailability) : AppLockAuthenticationResult
}

/** Activity-owned adapter around framework biometric and device-credential authentication. */
internal class AndroidAppLockAuthenticator(
    private val activity: Activity,
    private val launchDeviceCredential: (Intent) -> Unit,
) {
    private enum class Stage { BIOMETRIC, DEVICE_CREDENTIAL }

    private data class Attempt(
        val id: Long,
        val callback: (AppLockAuthenticationResult) -> Unit,
        var stage: Stage,
    )

    private val keyguardManager = activity.getSystemService(KeyguardManager::class.java)
    private var nextAttemptId = 0L
    private var attempt: Attempt? = null
    private var cancellationSignal: CancellationSignal? = null

    fun availability(): AppLockAvailability = availabilityFor(activity)

    fun authenticate(callback: (AppLockAuthenticationResult) -> Unit) {
        check(attempt == null) { "An app-lock authentication attempt is already active." }
        val currentAvailability = availability()
        if (currentAvailability != AppLockAvailability.AVAILABLE) {
            callback(AppLockAuthenticationResult.Unavailable(currentAvailability))
            return
        }

        val current = Attempt(++nextAttemptId, callback, Stage.DEVICE_CREDENTIAL)
        attempt = current
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> showCombinedPrompt(current)
            Build.VERSION.SDK_INT == Build.VERSION_CODES.P -> showApi28BiometricPrompt(current)
            else -> showDeviceCredential(current)
        }
    }

    /** Receives the result from the Activity Result API launcher owned by MainActivity. */
    fun onDeviceCredentialResult(succeeded: Boolean) {
        val current = attempt ?: return
        if (current.stage != Stage.DEVICE_CREDENTIAL) return
        finish(
            current,
            if (succeeded) AppLockAuthenticationResult.Succeeded else AppLockAuthenticationResult.Cancelled,
        )
    }

    fun cancel() {
        val current = attempt ?: return
        attempt = null
        cancellationSignal?.cancel()
        cancellationSignal = null
        current.callback(AppLockAuthenticationResult.Cancelled)
    }

    @Suppress("DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun showCombinedPrompt(current: Attempt) {
        current.stage = Stage.BIOMETRIC
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.app_lock_prompt_title))
            .setSubtitle(activity.getString(R.string.app_lock_prompt_subtitle))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            builder.setDeviceCredentialAllowed(true)
        }
        showPrompt(current, builder.build(), allowCredentialFallback = false)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun showApi28BiometricPrompt(current: Attempt) {
        current.stage = Stage.BIOMETRIC
        val prompt = runCatching {
            BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(R.string.app_lock_prompt_title))
                .setSubtitle(activity.getString(R.string.app_lock_prompt_subtitle))
                .setNegativeButton(
                    activity.getString(R.string.app_lock_use_screen_lock),
                    activity.mainExecutor,
                ) { _, _ -> showDeviceCredential(current) }
                .build()
        }.getOrElse {
            showDeviceCredential(current)
            return
        }
        showPrompt(current, prompt, allowCredentialFallback = true)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun showPrompt(
        current: Attempt,
        prompt: BiometricPrompt,
        allowCredentialFallback: Boolean,
    ) {
        val signal = CancellationSignal()
        cancellationSignal = signal
        runCatching {
            prompt.authenticate(
                signal,
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        finish(current, AppLockAuthenticationResult.Succeeded)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (attempt?.id != current.id || current.stage != Stage.BIOMETRIC) return
                        if (allowCredentialFallback && errorCode.shouldUseCredentialFallback()) {
                            showDeviceCredential(current)
                        } else {
                            finish(
                                current,
                                if (errorCode.isUserCancellation()) {
                                    AppLockAuthenticationResult.Cancelled
                                } else {
                                    AppLockAuthenticationResult.Error
                                },
                            )
                        }
                    }

                    // A non-match is not terminal: the framework prompt remains open for retry.
                    override fun onAuthenticationFailed() = Unit
                },
            )
        }.onFailure {
            if (allowCredentialFallback) {
                showDeviceCredential(current)
            } else {
                finish(current, AppLockAuthenticationResult.Error)
            }
        }
    }

    private fun showDeviceCredential(current: Attempt) {
        if (attempt?.id != current.id) return
        current.stage = Stage.DEVICE_CREDENTIAL
        cancellationSignal = null
        val intent = keyguardManager?.createConfirmDeviceCredentialIntent(
            activity.getString(R.string.app_lock_prompt_title),
            activity.getString(R.string.app_lock_prompt_subtitle),
        )
        if (intent == null) {
            finish(
                current,
                AppLockAuthenticationResult.Unavailable(
                    if (keyguardManager == null) {
                        AppLockAvailability.UNSUPPORTED
                    } else {
                        AppLockAvailability.DEVICE_CREDENTIAL_NOT_CONFIGURED
                    },
                ),
            )
            return
        }
        runCatching { launchDeviceCredential(intent) }
            .onFailure { finish(current, AppLockAuthenticationResult.Error) }
    }

    private fun finish(current: Attempt, result: AppLockAuthenticationResult) {
        if (attempt?.id != current.id) return
        attempt = null
        cancellationSignal = null
        current.callback(result)
    }

    companion object {
        fun availabilityFor(context: Context): AppLockAvailability {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return AppLockAvailability.UNSUPPORTED
            val keyguard = context.getSystemService(KeyguardManager::class.java)
                ?: return AppLockAvailability.UNSUPPORTED
            return if (keyguard.isDeviceSecure) {
                AppLockAvailability.AVAILABLE
            } else {
                AppLockAvailability.DEVICE_CREDENTIAL_NOT_CONFIGURED
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private fun Int.isUserCancellation(): Boolean =
    this == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
        this == BiometricPrompt.BIOMETRIC_ERROR_CANCELED

@RequiresApi(Build.VERSION_CODES.P)
private fun Int.shouldUseCredentialFallback(): Boolean = when (this) {
    BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
    BiometricPrompt.BIOMETRIC_ERROR_UNABLE_TO_PROCESS,
    BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT,
    BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
    BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
    BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS,
    BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
    -> true
    else -> false
}
