package com.yanjiyu.terminalspike.settings

data class SettingsLoadResult(
    val settings: UserSettings,
    val warning: String? = null,
    val failure: SettingsLoadFailure? = null,
)

enum class SettingsLoadFailure {
    KEY_UNAVAILABLE,
    CORRUPT_OR_UNSUPPORTED,
    /** Authoritative Room or Proto DataStore data could not be read and was preserved in place. */
    APP_DATA_UNAVAILABLE,
}
