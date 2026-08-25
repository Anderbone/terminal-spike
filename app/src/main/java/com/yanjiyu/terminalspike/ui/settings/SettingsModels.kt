package com.yanjiyu.terminalspike.ui.settings

import androidx.annotation.StringRes
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.data.migration.LegacyIds
import com.yanjiyu.terminalspike.core.data.settings.CURRENT_KEYBOARD_DECK_REVISION
import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.CustomTerminalTheme
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.settings.ImportedTerminalFont
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryAction
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryModifier
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import com.yanjiyu.terminalspike.terminal.view.TerminalLocalAccessoryAction
import com.yanjiyu.terminalspike.terminal.view.encodeTerminalChord
import com.yanjiyu.terminalspike.terminal.view.toAccessoryAction
import com.yanjiyu.terminalspike.ui.UiText
import com.yanjiyu.terminalspike.ui.quantityText
import com.yanjiyu.terminalspike.ui.uiText
import java.util.Locale
import kotlin.math.roundToInt

internal enum class SettingsCategory(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val searchTerms: String,
) {
    APPEARANCE(
        R.string.settings_category_appearance,
        R.string.settings_category_appearance_summary,
        "appearance app theme light dark terminal theme font size line height spacing preview",
    ),
    TERMINAL(
        R.string.settings_category_terminal,
        R.string.settings_category_terminal_summary,
        "terminal links clipboard paste url multiline",
    ),
    KEYBOARD(
        R.string.settings_category_keyboard,
        R.string.settings_category_keyboard_summary,
        "keyboard keys accessory rows layout vim preset voice microphone speech language dictation",
    ),
    SSH_KEYS(
        R.string.settings_category_ssh_keys,
        R.string.settings_category_ssh_keys_summary,
        "ssh keys identities private public import generate fingerprint authentication",
    ),
    SNIPPETS(
        R.string.settings_category_snippets,
        R.string.settings_category_snippets_summary,
        "snippets commands saved insert run terminal shortcuts",
    ),
    SESSIONS_BACKGROUND(
        R.string.settings_category_sessions_background,
        R.string.settings_category_sessions_background_summary,
        "sessions background battery optimisation optimization data saver keep screen awake cpu " +
            "foreground ssh keepalive reconnect network retry tmux selector attach session",
    ),
    NOTIFICATIONS(
        R.string.settings_category_notifications,
        R.string.settings_category_notifications_summary,
        "notifications permission foreground service disconnect reconnect privacy",
    ),
    BACKUP_RESTORE(
        R.string.settings_category_backup_restore,
        R.string.settings_category_backup_restore_summary,
        "backup restore export import drive device file complete",
    ),
    SECURITY(
        R.string.settings_category_security,
        R.string.settings_category_security_summary,
        "security known hosts fingerprints screenshot clipboard osc credentials privacy",
    ),
    MOSH(
        R.string.settings_category_mosh,
        R.string.settings_category_mosh_summary,
        "mosh extension plugin version api licence source signature",
    ),
    ABOUT(
        R.string.settings_category_about,
        R.string.settings_category_about_summary,
        "about version privacy notices licences licenses open source",
    ),
    DEVELOPER(
        R.string.settings_category_developer,
        R.string.settings_category_developer_summary,
        "developer debug renderer diagnostics performance",
    ),
}

internal enum class AppearanceMode(@StringRes val labelRes: Int) {
    SYSTEM(R.string.settings_theme_system),
    LIGHT(R.string.settings_theme_light),
    DARK(R.string.settings_theme_dark),
}

internal enum class AppLockModeOption(@StringRes val labelRes: Int) {
    OFF(R.string.settings_app_lock_off),
    IMMEDIATE(R.string.settings_app_lock_immediate),
    DELAYED(R.string.settings_app_lock_delayed),
    ON_BACKGROUND(R.string.settings_app_lock_on_background),
}

internal enum class AccentPreset(
    val wireCode: String,
    @StringRes val labelRes: Int,
) {
    MINT("mint", R.string.settings_accent_mint),
    BLUE("blue", R.string.settings_accent_blue),
    VIOLET("violet", R.string.settings_accent_violet),
    AMBER("amber", R.string.settings_accent_amber),
    CORAL("coral", R.string.settings_accent_coral),
    ;

    companion object {
        fun fromWireCode(value: String): AccentPreset = entries.firstOrNull { it.wireCode == value } ?: MINT
    }
}

internal enum class VoiceInputLanguage(
    val languageTag: String,
    @StringRes val labelRes: Int,
) {
    DEVICE_DEFAULT("", R.string.settings_voice_language_device),
    ENGLISH_UK("en-GB", R.string.settings_voice_language_english_uk),
    ENGLISH_US("en-US", R.string.settings_voice_language_english_us),
    CHINESE_SIMPLIFIED("zh-CN", R.string.settings_voice_language_chinese_simplified),
    CANTONESE("zh-HK", R.string.settings_voice_language_cantonese),
    JAPANESE("ja-JP", R.string.settings_voice_language_japanese),
    KOREAN("ko-KR", R.string.settings_voice_language_korean),
    FRENCH("fr-FR", R.string.settings_voice_language_french),
    GERMAN("de-DE", R.string.settings_voice_language_german),
    SPANISH("es-ES", R.string.settings_voice_language_spanish),
    ;

    companion object {
        fun fromLanguageTag(value: String): VoiceInputLanguage =
            entries.firstOrNull { it.languageTag == value } ?: DEVICE_DEFAULT
    }
}

internal data class AppPreferences(
    val appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
    val dynamicColorEnabled: Boolean = false,
    val accentPreset: AccentPreset = AccentPreset.MINT,
    val keepaliveIntervalSeconds: Int = 30,
    val reconnectEnabled: Boolean = false,
    val reconnectMaxAttempts: Int = 5,
    val tmuxSessionSelectorEnabled: Boolean = true,
    val keepCpuAwake: Boolean = false,
    val notificationPrivacyEnabled: Boolean = true,
    val disconnectNotificationsEnabled: Boolean = false,
    val reconnectNotificationsEnabled: Boolean = false,
    val keepScreenOnWhileTerminalVisible: Boolean = false,
    val appLockMode: AppLockModeOption = AppLockModeOption.OFF,
    val appLockDelaySeconds: Int = 30,
    val screenshotBlockingEnabled: Boolean = false,
    val sensitiveClipboardClearSeconds: Int = 0,
    val multilinePasteConfirmationEnabled: Boolean = false,
    val voiceInputLanguage: VoiceInputLanguage = VoiceInputLanguage.DEVICE_DEFAULT,
)

internal enum class SensitiveClipboardClearPreset(
    val seconds: Int,
    @StringRes val labelRes: Int,
) {
    OFF(0, R.string.settings_clipboard_clear_off),
    THIRTY_SECONDS(30, R.string.settings_clipboard_clear_30_seconds),
    ONE_MINUTE(60, R.string.settings_clipboard_clear_1_minute),
    FIVE_MINUTES(300, R.string.settings_clipboard_clear_5_minutes),
    ;

    companion object {
        fun fromSeconds(seconds: Int): SensitiveClipboardClearPreset =
            entries.firstOrNull { it.seconds == seconds } ?: OFF
    }
}

internal data class SettingsUiState(
    val preferences: AppPreferences = AppPreferences(),
    val terminalProfile: TerminalProfile? = null,
    val keyboardProfile: KeyboardProfile? = null,
    val importedFonts: List<ImportedTerminalFont> = emptyList(),
    val customTerminalThemes: List<CustomTerminalTheme> = emptyList(),
    val profilesLoading: Boolean = true,
    val writeInProgress: Boolean = false,
    @StringRes val message: Int? = null,
)

/** Landing-row copy is a projection of committed state, so it refreshes with the state stream. */
internal fun settingsCategorySummary(
    category: SettingsCategory,
    state: SettingsUiState,
): UiText = when (category) {
    SettingsCategory.APPEARANCE -> state.terminalProfile?.let { profile ->
        uiText(
            R.string.settings_category_appearance_live_summary,
            uiText(state.preferences.appearanceMode.labelRes),
            profile.name,
            profile.fontSizeSp.roundToInt(),
        )
    } ?: uiText(category.summaryRes)
    SettingsCategory.TERMINAL -> state.terminalProfile?.let { profile ->
        uiText(
            R.string.settings_category_terminal_live_summary,
            profile.name,
            uiText(
                if (state.preferences.multilinePasteConfirmationEnabled) {
                    R.string.settings_summary_enabled
                } else {
                    R.string.settings_summary_disabled
                },
            ),
        )
    } ?: uiText(category.summaryRes)
    SettingsCategory.KEYBOARD -> state.keyboardProfile?.let { profile ->
        uiText(
            R.string.settings_category_keyboard_live_summary,
            profile.name,
            quantityText(
                R.plurals.settings_category_keyboard_key_count,
                profile.orderedActions.size,
            ),
        )
    } ?: uiText(category.summaryRes)
    SettingsCategory.SESSIONS_BACKGROUND -> uiText(
        R.string.settings_category_sessions_background_live_summary,
        state.preferences.keepaliveIntervalSeconds,
        uiText(
            if (state.preferences.reconnectEnabled) {
                R.string.settings_summary_enabled
            } else {
                R.string.settings_summary_disabled
            },
        ),
        uiText(
            if (state.preferences.keepCpuAwake) {
                R.string.settings_summary_enabled
            } else {
                R.string.settings_summary_disabled
            },
        ),
    )
    SettingsCategory.NOTIFICATIONS -> uiText(
        R.string.settings_category_notifications_live_summary,
        uiText(
            if (state.preferences.notificationPrivacyEnabled) {
                R.string.settings_summary_enabled
            } else {
                R.string.settings_summary_disabled
            },
        ),
        uiText(
            if (
                state.preferences.disconnectNotificationsEnabled ||
                state.preferences.reconnectNotificationsEnabled
            ) {
                R.string.settings_summary_enabled
            } else {
                R.string.settings_summary_disabled
            },
        ),
    )
    SettingsCategory.SECURITY -> uiText(
        R.string.settings_category_security_live_summary,
        uiText(state.preferences.appLockMode.labelRes),
        uiText(
            if (state.preferences.screenshotBlockingEnabled) {
                R.string.settings_summary_enabled
            } else {
                R.string.settings_summary_disabled
            },
        ),
    )
    else -> uiText(category.summaryRes)
}

internal data class CustomTerminalThemeDraft(
    val id: String? = null,
    val name: String,
    val foregroundArgb: Int,
    val backgroundArgb: Int,
    val cursorArgb: Int,
    val selectionArgb: Int,
    val ansi16Argb: List<Int>,
    val boldUsesBrightColours: Boolean = true,
) {
    init {
        require(ansi16Argb.size == CustomTerminalTheme.ANSI_COLOUR_COUNT)
    }
}

internal sealed interface SavedCredentialClearUiState {
    data object Idle : SavedCredentialClearUiState
    data object LoadingPreview : SavedCredentialClearUiState
    data object PreviewFailed : SavedCredentialClearUiState

    data class Confirming(
        val credentialCount: Int,
        val keyIdentityCount: Int,
        val clearing: Boolean = false,
        val failed: Boolean = false,
    ) : SavedCredentialClearUiState

    data class Completed(
        val credentialsDeleted: Int,
        val keyIdentitiesDeleted: Int,
    ) : SavedCredentialClearUiState
}

internal const val CLEAR_SAVED_CREDENTIALS_CONFIRMATION = "CLEAR SAVED CREDENTIALS"

internal data class KeyboardPreset(
    val id: String,
    @StringRes val labelRes: Int,
    val actions: List<KeyboardAction>,
    val layout: KeyboardLayout,
    val modifierBehavior: ModifierBehavior,
    val hapticFeedbackEnabled: Boolean = false,
    val keyRepeatEnabled: Boolean = true,
    val inputMode: TerminalInputMode = TerminalInputMode.RAW,
    val tmuxPrefix: String = KeyboardProfile.DEFAULT_TMUX_PREFIX,
) {
    fun matches(profile: KeyboardProfile): Boolean =
        profile.orderedActions == actions &&
            profile.layout == layout &&
            profile.modifierBehavior == modifierBehavior &&
            profile.hapticFeedbackEnabled == hapticFeedbackEnabled &&
            profile.keyRepeatEnabled == keyRepeatEnabled &&
            profile.inputMode == inputMode &&
            profile.tmuxPrefix == tmuxPrefix
}

internal object KeyboardPresets {
    val general = KeyboardPreset(
        id = "general",
        labelRes = R.string.settings_keyboard_preset_general,
        actions = KeyboardAction.DEFAULT_ORDER,
        layout = KeyboardLayout.TWO_ROWS,
        modifierBehavior = ModifierBehavior.ONE_SHOT,
    )

    val tmuxCodex = KeyboardPreset(
        id = "tmux_codex",
        labelRes = R.string.settings_keyboard_preset_tmux_codex,
        actions = listOf(
            KeyboardAction.ESCAPE,
            KeyboardAction.CONTROL,
            KeyboardAction.ALT,
            KeyboardAction.TMUX_PREFIX,
            KeyboardAction.CTRL_C,
            KeyboardAction.CTRL_W,
            KeyboardAction.CTRL_D,
            KeyboardAction.CTRL_R,
            KeyboardAction.PIPE,
            KeyboardAction.CTRL_A,
            KeyboardAction.CTRL_E,
            KeyboardAction.PAGE_UP,
            KeyboardAction.PAGE_DOWN,
            KeyboardAction.HOME,
            KeyboardAction.END,
            KeyboardAction.ARROW_UP,
            KeyboardAction.ARROW_DOWN,
            KeyboardAction.ENTER,
        ),
        layout = KeyboardLayout.TWO_ROWS,
        modifierBehavior = ModifierBehavior.ONE_SHOT,
    )

    val vim = KeyboardPreset(
        id = "vim",
        labelRes = R.string.settings_keyboard_preset_vim,
        actions = listOf(
            KeyboardAction.ESCAPE,
            KeyboardAction.CONTROL,
            KeyboardAction.ALT,
            KeyboardAction.COLON,
            KeyboardAction.SLASH,
            KeyboardAction.CTRL_C,
            KeyboardAction.CTRL_D,
            KeyboardAction.CTRL_U,
            KeyboardAction.CTRL_W,
            KeyboardAction.HOME,
            KeyboardAction.END,
            KeyboardAction.PAGE_UP,
            KeyboardAction.PAGE_DOWN,
            KeyboardAction.ARROW_UP,
            KeyboardAction.ARROW_DOWN,
            KeyboardAction.ARROW_LEFT,
            KeyboardAction.ARROW_RIGHT,
            KeyboardAction.ENTER,
        ),
        layout = KeyboardLayout.TWO_ROWS,
        modifierBehavior = ModifierBehavior.ONE_SHOT,
    )

    val minimal = KeyboardPreset(
        id = "minimal",
        labelRes = R.string.settings_keyboard_preset_minimal,
        actions = listOf(
            KeyboardAction.ESCAPE,
            KeyboardAction.CONTROL,
            KeyboardAction.ALT,
            KeyboardAction.TAB,
            KeyboardAction.ARROW_UP,
            KeyboardAction.ARROW_DOWN,
            KeyboardAction.ARROW_LEFT,
            KeyboardAction.ARROW_RIGHT,
            KeyboardAction.ENTER,
        ),
        layout = KeyboardLayout.ONE_ROW,
        modifierBehavior = ModifierBehavior.ONE_SHOT,
    )

    val selectable = listOf(general, vim, minimal)

    fun selectedId(profile: KeyboardProfile): String = selectable.firstOrNull { it.matches(profile) }?.id ?: "custom"
}

internal data class KeyboardDeckMigrationPlan(
    val shouldRecordCompletion: Boolean,
    val upgradedProfile: KeyboardProfile?,
)

/**
 * Plans the one-time repair without mutating a profile. The full persisted seed fingerprint is
 * intentional: a user edit to any keyboard setting makes the profile custom and therefore
 * ineligible, even when its actions still happen to match the General preset.
 */
internal fun planKeyboardDeckMigration(
    completedRevision: Int,
    canonicalProfile: KeyboardProfile?,
    nowEpochMillis: Long,
): KeyboardDeckMigrationPlan {
    if (completedRevision >= CURRENT_KEYBOARD_DECK_REVISION) {
        return KeyboardDeckMigrationPlan(
            shouldRecordCompletion = false,
            upgradedProfile = null,
        )
    }
    val upgraded = canonicalProfile
        ?.takeIf(KeyboardProfile::isUntouchedShippedKeyboardDeck)
        ?.copy(
            orderedActions = KeyboardPresets.general.actions,
            layout = KeyboardPresets.general.layout,
            updatedAtEpochMillis = maxOf(nowEpochMillis, canonicalProfile.updatedAtEpochMillis),
        )
    return KeyboardDeckMigrationPlan(
        shouldRecordCompletion = true,
        upgradedProfile = upgraded,
    )
}

internal fun KeyboardProfile.isUntouchedShippedKeyboardDeck(): Boolean =
    id == LegacyIds.defaultKeyboardProfile &&
        name == SHIPPED_KEYBOARD_PROFILE_NAME &&
        (
            (
                layout == KeyboardLayout.ONE_ROW &&
                    orderedActions in shippedOneRowKeyboardActionOrders &&
                    createdAtEpochMillis == updatedAtEpochMillis
                ) ||
                (
                    layout == KeyboardLayout.TWO_ROWS &&
                        orderedActions in previousGeneralKeyboardActionOrders
                )
            ) &&
        modifierBehavior == KeyboardPresets.general.modifierBehavior &&
        hapticFeedbackEnabled == KeyboardPresets.general.hapticFeedbackEnabled &&
        keyRepeatEnabled == KeyboardPresets.general.keyRepeatEnabled &&
        inputMode == KeyboardPresets.general.inputMode &&
        tmuxPrefix == KeyboardPresets.general.tmuxPrefix

private const val SHIPPED_KEYBOARD_PROFILE_NAME = "Default"

private val previousGeneralKeyboardActionOrders: List<List<KeyboardAction>> = listOf(
    listOf(
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
        KeyboardAction.ALT,
        KeyboardAction.CTRL_C,
        KeyboardAction.CTRL_W,
        KeyboardAction.ARROW_LEFT,
        KeyboardAction.ARROW_DOWN,
        KeyboardAction.ARROW_RIGHT,
        KeyboardAction.ENTER,
        KeyboardAction.HIDE_KEYBOARD,
    ),
    listOf(
        KeyboardAction.ESCAPE,
        KeyboardAction.SLASH,
        KeyboardAction.AT_SIGN,
        KeyboardAction.DOLLAR,
        KeyboardAction.HOME,
        KeyboardAction.ARROW_UP,
        KeyboardAction.END,
        KeyboardAction.PAGE_UP,
        KeyboardAction.PASTE,
        KeyboardAction.BACKSPACE,
        KeyboardAction.TAB,
        KeyboardAction.CONTROL,
        KeyboardAction.ALT,
        KeyboardAction.CTRL_C,
        KeyboardAction.CTRL_W,
        KeyboardAction.ARROW_LEFT,
        KeyboardAction.ARROW_DOWN,
        KeyboardAction.ARROW_RIGHT,
        KeyboardAction.ENTER,
        KeyboardAction.HIDE_KEYBOARD,
    ),
    listOf(
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
    ),
    listOf(
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
    ),
)

private val shippedOneRowKeyboardActionOrders: List<List<KeyboardAction>> = listOf(
    listOf(
        KeyboardAction.ESCAPE,
        KeyboardAction.SLASH,
        KeyboardAction.AT_SIGN,
        KeyboardAction.DOLLAR,
        KeyboardAction.HOME,
        KeyboardAction.ARROW_UP,
        KeyboardAction.END,
        KeyboardAction.PAGE_UP,
        KeyboardAction.CTRL_B,
        KeyboardAction.TAB,
        KeyboardAction.CONTROL,
        KeyboardAction.CTRL_C,
        KeyboardAction.CTRL_W,
        KeyboardAction.ARROW_LEFT,
        KeyboardAction.ARROW_DOWN,
        KeyboardAction.ARROW_RIGHT,
        KeyboardAction.HIDE_KEYBOARD,
    ),
    listOf(
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
        KeyboardAction.ARROW_UP,
        KeyboardAction.END,
        KeyboardAction.PAGE_UP,
        KeyboardAction.ARROW_LEFT,
        KeyboardAction.ARROW_DOWN,
        KeyboardAction.ARROW_RIGHT,
        KeyboardAction.CTRL_B,
        KeyboardAction.SLASH,
        KeyboardAction.AT_SIGN,
        KeyboardAction.HIDE_KEYBOARD,
    ),
    *previousGeneralKeyboardActionOrders.toTypedArray(),
    KeyboardPresets.general.actions,
)

internal val runtimeSupportedKeyboardActions: List<KeyboardAction> =
    KeyboardAction.entries.filterNot { it == KeyboardAction.TMUX_PREFIX }

/**
 * Projects every persisted keyboard action into a truthful runtime type. Local actions remain
 * local, modifiers remain state transitions, and only Key/TmuxPrefix actions can resolve bytes.
 */
internal fun KeyboardProfile.toRuntimeAccessoryActionsOrNull(): List<TerminalAccessoryAction>? {
    val runtime = ArrayList<TerminalAccessoryAction>(orderedActions.size)
    orderedActions.forEach { action ->
        runtime += action.toRuntimeAccessoryActionOrNull(tmuxPrefix) ?: return null
    }
    return runtime
}

private fun KeyboardAction.toRuntimeAccessoryActionOrNull(
    tmuxPrefix: String,
): TerminalAccessoryAction? = when (this) {
    KeyboardAction.SHIFT -> TerminalAccessoryAction.Modifier(
        modifier = TerminalAccessoryModifier.SHIFT,
        stableId = wireCode,
    )
    KeyboardAction.TMUX_PREFIX -> encodeTerminalChord(tmuxPrefix)?.let {
        TerminalAccessoryAction.TmuxPrefix(chord = tmuxPrefix, stableId = wireCode)
    }
    KeyboardAction.PASTE -> TerminalAccessoryAction.Local(
        action = TerminalLocalAccessoryAction.PASTE,
        stableId = wireCode,
    )
    KeyboardAction.SELECT_IMAGES -> TerminalAccessoryAction.Local(
        action = TerminalLocalAccessoryAction.SELECT_IMAGES,
        stableId = wireCode,
    )
    KeyboardAction.SNIPPETS -> TerminalAccessoryAction.Local(
        action = TerminalLocalAccessoryAction.SNIPPETS,
        stableId = wireCode,
    )
    KeyboardAction.TMUX_SESSIONS -> TerminalAccessoryAction.Local(
        action = TerminalLocalAccessoryAction.TMUX_SESSIONS,
        stableId = wireCode,
    )
    KeyboardAction.KEYBOARD_SETTINGS -> TerminalAccessoryAction.Local(
        action = TerminalLocalAccessoryAction.KEYBOARD_SETTINGS,
        stableId = wireCode,
    )
    else -> toTerminalExtraKeyOrNull()?.toAccessoryAction(stableId = wireCode)
}

internal fun defaultRuntimeAccessoryActions(): List<TerminalAccessoryAction> =
    KeyboardAction.DEFAULT_ORDER.map { action ->
        requireNotNull(
            action.toRuntimeAccessoryActionOrNull(KeyboardProfile.DEFAULT_TMUX_PREFIX),
        )
    }

internal fun KeyboardProfile.toRuntimeExtraKeysOrNull(): List<TerminalExtraKey>? {
    val keys = ArrayList<TerminalExtraKey>(orderedActions.size)
    orderedActions.forEach { action ->
        val key = if (action == KeyboardAction.TMUX_PREFIX) {
            tmuxPrefix.toTerminalExtraKeyOrNull()
        } else {
            action.toTerminalExtraKeyOrNull()
        } ?: return null
        if (key in keys) return null
        keys += key
    }
    return keys
}

internal fun String.isRuntimeTmuxPrefix(): Boolean = encodeTerminalChord(this) != null

internal enum class KeyboardActionMove {
    BEFORE,
    AFTER,
}

/** Moves one stable action by one position and proves the operation preserved the exact set. */
internal fun List<KeyboardAction>.moveStableAction(
    actionId: String,
    move: KeyboardActionMove,
): List<KeyboardAction> {
    require(size == distinct().size) { "Keyboard actions must be unique before reordering." }
    val from = indexOfFirst { it.wireCode == actionId }
    if (from < 0) return this
    val to = when (move) {
        KeyboardActionMove.BEFORE -> from - 1
        KeyboardActionMove.AFTER -> from + 1
    }
    if (to !in indices) return this
    val reordered = toMutableList().apply { add(to, removeAt(from)) }
    check(reordered.size == size && reordered.toSet() == toSet()) {
        "Keyboard reorder changed the configured action set."
    }
    return reordered
}

private fun String.toTerminalExtraKeyOrNull(): TerminalExtraKey? = when (uppercase(Locale.ROOT)) {
    "C-A" -> TerminalExtraKey.CTRL_A
    "C-B" -> TerminalExtraKey.CTRL_B
    "C-C" -> TerminalExtraKey.CTRL_C
    "C-D" -> TerminalExtraKey.CTRL_D
    "C-E" -> TerminalExtraKey.CTRL_E
    "C-K" -> TerminalExtraKey.CTRL_K
    "C-L" -> TerminalExtraKey.CTRL_L
    "C-R" -> TerminalExtraKey.CTRL_R
    "C-U" -> TerminalExtraKey.CTRL_U
    "C-W" -> TerminalExtraKey.CTRL_W
    "C-Z" -> TerminalExtraKey.CTRL_Z
    else -> null
}

private fun KeyboardAction.toTerminalExtraKeyOrNull(): TerminalExtraKey? = when (this) {
    KeyboardAction.ESCAPE -> TerminalExtraKey.ESC
    KeyboardAction.CONTROL -> TerminalExtraKey.CTRL
    KeyboardAction.ALT -> TerminalExtraKey.ALT
    KeyboardAction.ARROW_UP -> TerminalExtraKey.UP
    KeyboardAction.ARROW_DOWN -> TerminalExtraKey.DOWN
    KeyboardAction.ARROW_LEFT -> TerminalExtraKey.LEFT
    KeyboardAction.ARROW_RIGHT -> TerminalExtraKey.RIGHT
    KeyboardAction.HYPHEN -> TerminalExtraKey.DASH
    KeyboardAction.AT_SIGN -> TerminalExtraKey.AT
    KeyboardAction.SHIFT,
    KeyboardAction.TMUX_PREFIX,
    KeyboardAction.PASTE,
    KeyboardAction.SELECT_IMAGES,
    KeyboardAction.SNIPPETS,
    KeyboardAction.TMUX_SESSIONS,
    KeyboardAction.KEYBOARD_SETTINGS,
    -> null
    else -> runCatching { TerminalExtraKey.valueOf(name) }.getOrNull()
}

internal fun settingsCategoriesForSearch(
    query: String,
    includeDeveloper: Boolean,
): List<SettingsCategory> {
    val normalizedTerms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
    return SettingsCategory.entries.filter { category ->
        category in userFacingSettingsCategories &&
        (includeDeveloper || category != SettingsCategory.DEVELOPER) &&
            normalizedTerms.all { term -> term in category.searchTerms }
    }
}

internal val userFacingSettingsCategories: Set<SettingsCategory> = setOf(
    SettingsCategory.APPEARANCE,
    SettingsCategory.TERMINAL,
    SettingsCategory.KEYBOARD,
    SettingsCategory.SSH_KEYS,
    SettingsCategory.SNIPPETS,
    SettingsCategory.SESSIONS_BACKGROUND,
    SettingsCategory.NOTIFICATIONS,
    SettingsCategory.BACKUP_RESTORE,
    SettingsCategory.MOSH,
    SettingsCategory.ABOUT,
    SettingsCategory.DEVELOPER,
)

internal fun AppSettings.toPreferences(): AppPreferences = AppPreferences(
    appearanceMode = when (themeMode) {
        AppSettings.ThemeMode.THEME_MODE_LIGHT -> AppearanceMode.LIGHT
        AppSettings.ThemeMode.THEME_MODE_DARK -> AppearanceMode.DARK
        else -> AppearanceMode.SYSTEM
    },
    dynamicColorEnabled = dynamicColorEnabled,
    accentPreset = AccentPreset.fromWireCode(accentPreset),
    keepaliveIntervalSeconds = keepaliveIntervalSeconds,
    reconnectEnabled = reconnectEnabled,
    reconnectMaxAttempts = reconnectMaxAttempts,
    tmuxSessionSelectorEnabled = !tmuxSessionSelectorDisabled,
    keepCpuAwake = keepCpuAwake,
    notificationPrivacyEnabled = notificationPrivacyEnabled,
    disconnectNotificationsEnabled = disconnectNotificationsEnabled,
    reconnectNotificationsEnabled = reconnectNotificationsEnabled,
    keepScreenOnWhileTerminalVisible = keepScreenOnWhileTerminalVisible,
    appLockMode = when (appLockMode) {
        AppSettings.AppLockMode.APP_LOCK_MODE_IMMEDIATE -> AppLockModeOption.IMMEDIATE
        AppSettings.AppLockMode.APP_LOCK_MODE_DELAYED -> AppLockModeOption.DELAYED
        AppSettings.AppLockMode.APP_LOCK_MODE_ON_BACKGROUND -> AppLockModeOption.ON_BACKGROUND
        else -> AppLockModeOption.OFF
    },
    appLockDelaySeconds = appLockDelaySeconds,
    screenshotBlockingEnabled = screenshotBlockingEnabled,
    sensitiveClipboardClearSeconds = sensitiveClipboardClearSeconds,
    multilinePasteConfirmationEnabled = multilinePasteConfirmationEnabled,
    voiceInputLanguage = VoiceInputLanguage.fromLanguageTag(voiceInputLanguageTag),
)

internal fun AppearanceMode.toProto(): AppSettings.ThemeMode = when (this) {
    AppearanceMode.SYSTEM -> AppSettings.ThemeMode.THEME_MODE_SYSTEM
    AppearanceMode.LIGHT -> AppSettings.ThemeMode.THEME_MODE_LIGHT
    AppearanceMode.DARK -> AppSettings.ThemeMode.THEME_MODE_DARK
}

internal fun AppLockModeOption.toProto(): AppSettings.AppLockMode = when (this) {
    AppLockModeOption.OFF -> AppSettings.AppLockMode.APP_LOCK_MODE_OFF
    AppLockModeOption.IMMEDIATE -> AppSettings.AppLockMode.APP_LOCK_MODE_IMMEDIATE
    AppLockModeOption.DELAYED -> AppSettings.AppLockMode.APP_LOCK_MODE_DELAYED
    AppLockModeOption.ON_BACKGROUND -> AppSettings.AppLockMode.APP_LOCK_MODE_ON_BACKGROUND
}
