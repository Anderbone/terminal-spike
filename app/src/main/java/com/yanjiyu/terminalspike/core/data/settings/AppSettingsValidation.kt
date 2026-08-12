package com.yanjiyu.terminalspike.core.data.settings

import com.yanjiyu.terminalspike.core.model.ModelLimits
import java.util.UUID

/** Stable, non-sensitive field codes suitable for recovery UI and diagnostics. */
internal enum class AppSettingsViolation(val fieldCode: String) {
    THEME_MODE("theme_mode"),
    ACCENT_PRESET("accent_preset"),
    DEFAULT_TERMINAL_PROFILE_ID("default_terminal_profile_id"),
    DEFAULT_KEYBOARD_PROFILE_ID("default_keyboard_profile_id"),
    KEEPALIVE_INTERVAL("keepalive_interval_seconds"),
    RECONNECT_MAX_ATTEMPTS("reconnect_max_attempts"),
    APP_LOCK_MODE("app_lock_mode"),
    APP_LOCK_DELAY("app_lock_delay_seconds"),
    SENSITIVE_CLIPBOARD_CLEAR_DELAY("sensitive_clipboard_clear_seconds"),
    OSC52_POLICY("osc52_policy"),
    LAST_BACKUP_MODE("last_backup_mode"),
}

/**
 * A parseable settings payload whose values cannot be used safely by this app version.
 *
 * DataStore reads and migrations deliberately propagate this exception. No corruption handler is
 * installed, so the source bytes remain available for an explicit recovery flow.
 */
internal class InvalidAppSettingsException(
    violations: Collection<AppSettingsViolation>,
) : IllegalStateException(
    "App settings require recovery (${violations.map(AppSettingsViolation::fieldCode).sorted().joinToString()}).",
) {
    val violations: Set<AppSettingsViolation> = violations.toSet()
}

internal object AppSettingsValidator {
    private const val MAX_RECONNECT_ATTEMPTS = 100L
    private const val MAX_DELAY_SECONDS = 86_400L
    private const val MAX_SETTING_IDENTIFIER_BYTES = 128
    private val supportedBackupModes = setOf("standard", "full")

    fun requireValidCurrent(settings: AppSettings): AppSettings {
        if (settings.schemaRevision != CURRENT_APP_SETTINGS_SCHEMA) {
            throw IllegalArgumentException(
                "Expected app-settings schema $CURRENT_APP_SETTINGS_SCHEMA before semantic validation.",
            )
        }

        val violations = buildSet {
            if (settings.themeMode !in SUPPORTED_THEME_MODES) add(AppSettingsViolation.THEME_MODE)
            if (settings.accentPreset.isNotEmpty() && !settings.accentPreset.isValidIdentifier()) {
                add(AppSettingsViolation.ACCENT_PRESET)
            }
            if (
                settings.defaultTerminalProfileId.isNotEmpty() &&
                !settings.defaultTerminalProfileId.isCanonicalUuid()
            ) {
                add(AppSettingsViolation.DEFAULT_TERMINAL_PROFILE_ID)
            }
            if (
                settings.defaultKeyboardProfileId.isNotEmpty() &&
                !settings.defaultKeyboardProfileId.isCanonicalUuid()
            ) {
                add(AppSettingsViolation.DEFAULT_KEYBOARD_PROFILE_ID)
            }

            val keepalive = settings.keepaliveIntervalSeconds.toUnsignedLong()
            if (
                keepalive != 0L &&
                keepalive !in ModelLimits.MIN_KEEPALIVE_SECONDS.toLong()..
                    ModelLimits.MAX_KEEPALIVE_SECONDS.toLong()
            ) {
                add(AppSettingsViolation.KEEPALIVE_INTERVAL)
            }
            if (settings.reconnectMaxAttempts.toUnsignedLong() > MAX_RECONNECT_ATTEMPTS) {
                add(AppSettingsViolation.RECONNECT_MAX_ATTEMPTS)
            }

            if (settings.appLockMode !in SUPPORTED_APP_LOCK_MODES) add(AppSettingsViolation.APP_LOCK_MODE)
            if (settings.appLockDelaySeconds.toUnsignedLong() > MAX_DELAY_SECONDS) {
                add(AppSettingsViolation.APP_LOCK_DELAY)
            }
            if (settings.sensitiveClipboardClearSeconds.toUnsignedLong() > MAX_DELAY_SECONDS) {
                add(AppSettingsViolation.SENSITIVE_CLIPBOARD_CLEAR_DELAY)
            }
            if (settings.osc52Policy !in SUPPORTED_OSC52_POLICIES) add(AppSettingsViolation.OSC52_POLICY)
            if (settings.lastBackupMode.isNotEmpty() && settings.lastBackupMode !in supportedBackupModes) {
                add(AppSettingsViolation.LAST_BACKUP_MODE)
            }
        }
        if (violations.isNotEmpty()) throw InvalidAppSettingsException(violations)
        return settings
    }

    private fun String.isCanonicalUuid(): Boolean =
        length == CANONICAL_UUID_LENGTH && runCatching { UUID.fromString(this).toString() }.getOrNull() == this

    private fun String.isValidIdentifier(): Boolean =
        isNotEmpty() &&
            toByteArray(Charsets.UTF_8).size <= MAX_SETTING_IDENTIFIER_BYTES &&
            all { character -> character.isLetterOrDigit() || character in "._:+-@" }

    private fun Int.toUnsignedLong(): Long = Integer.toUnsignedLong(this)

    private const val CANONICAL_UUID_LENGTH = 36

    private val SUPPORTED_THEME_MODES = setOf(
        AppSettings.ThemeMode.THEME_MODE_SYSTEM,
        AppSettings.ThemeMode.THEME_MODE_LIGHT,
        AppSettings.ThemeMode.THEME_MODE_DARK,
    )
    private val SUPPORTED_APP_LOCK_MODES = setOf(
        AppSettings.AppLockMode.APP_LOCK_MODE_OFF,
        AppSettings.AppLockMode.APP_LOCK_MODE_IMMEDIATE,
        AppSettings.AppLockMode.APP_LOCK_MODE_DELAYED,
        AppSettings.AppLockMode.APP_LOCK_MODE_ON_BACKGROUND,
    )
    private val SUPPORTED_OSC52_POLICIES = setOf(
        AppSettings.Osc52Policy.OSC52_POLICY_DISABLED,
        AppSettings.Osc52Policy.OSC52_POLICY_ASK,
    )
}
