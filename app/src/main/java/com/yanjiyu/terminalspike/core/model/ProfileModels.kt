package com.yanjiyu.terminalspike.core.model

data class Snippet(
    val id: String,
    val name: String,
    val group: String? = null,
    val command: String,
    val tapAction: SnippetTapAction = SnippetTapAction.INSERT,
    val appendEnter: Boolean = false,
    val confirmMultilineExecution: Boolean = true,
    val isFavorite: Boolean = false,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        requireCanonicalUuid(id, "snippet ID")
        requirePlainText(name, "snippet name", ModelLimits.MAX_DISPLAY_NAME_LENGTH)
        requireOptionalPlainText(group, "snippet group", ModelLimits.MAX_GROUP_LENGTH)
        requireCommand(command, "snippet command", ModelLimits.MAX_COMMAND_LENGTH)
        require(
            tapAction != SnippetTapAction.SEND_IMMEDIATELY ||
                ('\n' !in command && '\r' !in command) ||
                confirmMultilineExecution,
        ) { "Multiline snippets sent immediately must require confirmation." }
        requireEpochMillis(createdAtEpochMillis, "created timestamp")
        requireEpochMillis(updatedAtEpochMillis, "updated timestamp")
        requireTimestampOrder(createdAtEpochMillis, updatedAtEpochMillis, "updated timestamp")
    }
}

data class BellSettings(
    val visualBellEnabled: Boolean = false,
    val vibrationBellEnabled: Boolean = false,
    val audibleBellEnabled: Boolean = false,
)

data class ScrollBehavior(
    val touchMode: TouchScrollMode = TouchScrollMode.AUTO,
    val twoFingerLocalScrollOverride: Boolean = true,
    val jumpToBottomOnKeyboardInput: Boolean = true,
    val keepViewportPositionOnOutput: Boolean = true,
)

data class LinkBehavior(
    val detectPlainTextUrls: Boolean = true,
    val osc8HyperlinksEnabled: Boolean = true,
    val remoteClipboardMode: RemoteClipboardMode = RemoteClipboardMode.ASK,
    val copyOnSelection: Boolean = false,
)

data class TerminalProfile(
    val id: String,
    val name: String,
    /** Stable built-in or custom theme reference, not a display label. */
    val themeId: String,
    /** Stable bundled or imported font reference, not a file path. */
    val fontId: String,
    val fontSizeSp: Float,
    val lineHeightMultiplier: Float,
    val letterSpacingEm: Float,
    val cursorStyle: CursorStyle,
    val cursorBlinkEnabled: Boolean = true,
    val scrollbackLines: Int,
    val bell: BellSettings = BellSettings(),
    val scroll: ScrollBehavior = ScrollBehavior(),
    val links: LinkBehavior = LinkBehavior(),
    val termValue: String = DEFAULT_TERM_VALUE,
    val preserveAlternateScreenHistory: Boolean = true,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val boldRenderingEnabled: Boolean = true,
    val ligaturesEnabled: Boolean = false,
    val pinchZoomEnabled: Boolean = true,
) {
    init {
        requireCanonicalUuid(id, "terminal profile ID")
        requirePlainText(name, "terminal profile name", ModelLimits.MAX_DISPLAY_NAME_LENGTH)
        requireIdentifier(themeId, "theme ID", ModelLimits.MAX_PROFILE_REFERENCE_LENGTH)
        requireIdentifier(fontId, "font ID", ModelLimits.MAX_PROFILE_REFERENCE_LENGTH)
        require(fontSizeSp.isFinite() && fontSizeSp in ModelLimits.MIN_FONT_SIZE_SP..ModelLimits.MAX_FONT_SIZE_SP) {
            "Font size is outside the supported range."
        }
        require(
            lineHeightMultiplier.isFinite() &&
                lineHeightMultiplier in ModelLimits.MIN_LINE_HEIGHT_MULTIPLIER..ModelLimits.MAX_LINE_HEIGHT_MULTIPLIER,
        ) { "Line height is outside the supported range." }
        require(
            letterSpacingEm.isFinite() &&
                letterSpacingEm in ModelLimits.MIN_LETTER_SPACING_EM..ModelLimits.MAX_LETTER_SPACING_EM,
        ) { "Letter spacing is outside the supported range." }
        require(scrollbackLines in 0..ModelLimits.MAX_SCROLLBACK_LINES) {
            "Scrollback size is outside the supported range."
        }
        requireIdentifier(termValue, "TERM value", ModelLimits.MAX_TERM_LENGTH)
        requireEpochMillis(createdAtEpochMillis, "created timestamp")
        requireEpochMillis(updatedAtEpochMillis, "updated timestamp")
        requireTimestampOrder(createdAtEpochMillis, updatedAtEpochMillis, "updated timestamp")
    }

    companion object {
        const val DEFAULT_TERM_VALUE = "xterm-256color"
    }
}

data class KeyboardProfile(
    val id: String,
    val name: String,
    val orderedActions: List<KeyboardAction>,
    val layout: KeyboardLayout,
    val modifierBehavior: ModifierBehavior,
    val hapticFeedbackEnabled: Boolean,
    val keyRepeatEnabled: Boolean,
    val inputMode: TerminalInputMode = TerminalInputMode.RAW,
    /** Human-readable terminal chord such as C-b; the TMUX_PREFIX action emits this value. */
    val tmuxPrefix: String = DEFAULT_TMUX_PREFIX,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        requireCanonicalUuid(id, "keyboard profile ID")
        requirePlainText(name, "keyboard profile name", ModelLimits.MAX_DISPLAY_NAME_LENGTH)
        require(orderedActions.isNotEmpty() && orderedActions.size <= KeyboardAction.entries.size) {
            "Keyboard profile must contain a bounded, non-empty action list."
        }
        require(orderedActions.distinct().size == orderedActions.size) {
            "Keyboard profile actions must be unique."
        }
        requireTmuxPrefix(tmuxPrefix)
        requireEpochMillis(createdAtEpochMillis, "created timestamp")
        requireEpochMillis(updatedAtEpochMillis, "updated timestamp")
        requireTimestampOrder(createdAtEpochMillis, updatedAtEpochMillis, "updated timestamp")
    }

    companion object {
        const val DEFAULT_TMUX_PREFIX = "C-b"
    }
}
