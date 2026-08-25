package com.yanjiyu.terminalspike.terminal.view

import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.ui.UiText
import com.yanjiyu.terminalspike.ui.uiText
import com.yanjiyu.terminalspike.ui.uiToken
import java.util.Locale

/** Presentation state for a terminal modifier; locked must never be rendered as one-shot armed. */
enum class AccessoryModifierState {
    OFF,
    ARMED,
    LOCKED,
    ;

    val isActive: Boolean
        get() = this != OFF
}

fun accessoryModifierState(armed: Boolean, locked: Boolean): AccessoryModifierState = when {
    locked -> AccessoryModifierState.LOCKED
    armed -> AccessoryModifierState.ARMED
    else -> AccessoryModifierState.OFF
}

enum class TerminalAccessoryModifier {
    CONTROL,
    ALT,
    SHIFT,
}

data class AccessoryModifierSnapshot(
    val control: AccessoryModifierState = AccessoryModifierState.OFF,
    val alt: AccessoryModifierState = AccessoryModifierState.OFF,
    val shift: AccessoryModifierState = AccessoryModifierState.OFF,
) {
    fun stateOf(modifier: TerminalAccessoryModifier): AccessoryModifierState = when (modifier) {
        TerminalAccessoryModifier.CONTROL -> control
        TerminalAccessoryModifier.ALT -> alt
        TerminalAccessoryModifier.SHIFT -> shift
    }

    /** One-shot state is consumed only after the target input sink accepts emitted bytes. */
    fun afterDispatch(
        dispatch: TerminalAccessoryDispatch,
        accepted: Boolean,
    ): AccessoryModifierSnapshot {
        if (!accepted || dispatch !is TerminalAccessoryDispatch.Bytes) return this
        return copy(
            control = control.afterAcceptedByteDispatch(),
            alt = alt.afterAcceptedByteDispatch(),
            shift = shift.afterAcceptedByteDispatch(),
        )
    }
}

private fun AccessoryModifierState.afterAcceptedByteDispatch(): AccessoryModifierState = when (this) {
    AccessoryModifierState.OFF -> AccessoryModifierState.OFF
    AccessoryModifierState.ARMED -> AccessoryModifierState.OFF
    AccessoryModifierState.LOCKED -> AccessoryModifierState.LOCKED
}

enum class TerminalLocalAccessoryAction {
    PASTE,
    SELECT_IMAGES,
    SNIPPETS,
    TMUX_SESSIONS,
    KEYBOARD_SETTINGS,
    HIDE_KEYBOARD,
}

/**
 * A live-deck item. Byte actions, modifiers, and local UI actions are deliberately disjoint so a
 * newly added local action cannot fall through to a terminal transport write.
 */
sealed interface TerminalAccessoryAction {
    val stableId: String
    val label: UiText
    val accessibilityDescription: UiText
    val supportsLongPressRepeat: Boolean
        get() = false

    data class Key(
        val key: TerminalExtraKey,
        override val stableId: String = "key:${key.name.lowercase(Locale.ROOT)}",
    ) : TerminalAccessoryAction {
        init {
            require(key.bytes != null) { "A byte key must contain a terminal byte sequence." }
            require(!key.isModifier && !key.isLocalAction) {
                "Modifiers and local actions require their explicit accessory action type."
            }
        }

        override val label: UiText
            get() = uiToken(key.label)
        override val accessibilityDescription: UiText
            get() = key.accessibilityDescription
        override val supportsLongPressRepeat: Boolean
            get() = key.supportsAccessoryRepeat
    }

    data class Modifier(
        val modifier: TerminalAccessoryModifier,
        override val stableId: String = "modifier:${modifier.name.lowercase(Locale.ROOT)}",
    ) : TerminalAccessoryAction {
        override val label: UiText
            get() = when (modifier) {
                TerminalAccessoryModifier.CONTROL -> uiToken("CTRL")
                TerminalAccessoryModifier.ALT -> uiToken("ALT")
                TerminalAccessoryModifier.SHIFT -> uiToken("SHIFT")
            }
        override val accessibilityDescription: UiText
            get() = when (modifier) {
                TerminalAccessoryModifier.CONTROL -> uiText(R.string.terminal_key_control_modifier)
                TerminalAccessoryModifier.ALT -> uiText(R.string.terminal_key_alt_modifier)
                TerminalAccessoryModifier.SHIFT -> uiText(R.string.terminal_key_shift_modifier)
            }
    }

    data class TmuxPrefix(
        val chord: String,
        override val stableId: String = "configured:tmux_prefix",
    ) : TerminalAccessoryAction {
        init {
            require(encodeTerminalChord(chord) != null) {
                "The configured tmux prefix is not a supported terminal chord."
            }
        }

        override val label: UiText = uiText(R.string.terminal_key_tmux_prefix_label)
        override val accessibilityDescription: UiText =
            uiText(R.string.terminal_key_tmux_prefix_description)
    }

    data class Local(
        val action: TerminalLocalAccessoryAction,
        override val stableId: String = "local:${action.name.lowercase(Locale.ROOT)}",
    ) : TerminalAccessoryAction {
        override val label: UiText
            get() = when (action) {
                TerminalLocalAccessoryAction.PASTE -> uiText(R.string.terminal_key_paste_label)
                TerminalLocalAccessoryAction.SELECT_IMAGES ->
                    uiText(R.string.terminal_key_select_images_label)
                TerminalLocalAccessoryAction.SNIPPETS -> uiText(R.string.terminal_key_snippets_label)
                TerminalLocalAccessoryAction.TMUX_SESSIONS ->
                    uiText(R.string.terminal_key_tmux_sessions_label)
                TerminalLocalAccessoryAction.KEYBOARD_SETTINGS -> uiText(R.string.terminal_key_settings_label)
                TerminalLocalAccessoryAction.HIDE_KEYBOARD -> uiText(R.string.terminal_key_hide_label)
            }
        override val accessibilityDescription: UiText
            get() = when (action) {
                TerminalLocalAccessoryAction.PASTE -> uiText(R.string.terminal_key_paste_description)
                TerminalLocalAccessoryAction.SELECT_IMAGES ->
                    uiText(R.string.terminal_key_select_images_description)
                TerminalLocalAccessoryAction.SNIPPETS -> uiText(R.string.terminal_key_snippets_description)
                TerminalLocalAccessoryAction.TMUX_SESSIONS ->
                    uiText(R.string.terminal_key_tmux_sessions_description)
                TerminalLocalAccessoryAction.KEYBOARD_SETTINGS -> uiText(R.string.terminal_key_settings_description)
                TerminalLocalAccessoryAction.HIDE_KEYBOARD -> uiText(R.string.terminal_key_hide_keyboard)
            }
    }
}

sealed interface TerminalAccessoryDispatch {
    data class Bytes(val value: ByteArray) : TerminalAccessoryDispatch
    data class ToggleModifier(val modifier: TerminalAccessoryModifier) : TerminalAccessoryDispatch
    data class Local(val action: TerminalLocalAccessoryAction) : TerminalAccessoryDispatch
    data class Unsupported(val explanation: UiText) : TerminalAccessoryDispatch
}

fun TerminalExtraKey.toAccessoryAction(
    stableId: String = "key:${name.lowercase(Locale.ROOT)}",
): TerminalAccessoryAction = when (this) {
    TerminalExtraKey.CTRL -> TerminalAccessoryAction.Modifier(
        modifier = TerminalAccessoryModifier.CONTROL,
        stableId = stableId,
    )
    TerminalExtraKey.ALT -> TerminalAccessoryAction.Modifier(
        modifier = TerminalAccessoryModifier.ALT,
        stableId = stableId,
    )
    TerminalExtraKey.HIDE_KEYBOARD -> TerminalAccessoryAction.Local(
        action = TerminalLocalAccessoryAction.HIDE_KEYBOARD,
        stableId = stableId,
    )
    else -> TerminalAccessoryAction.Key(this, stableId)
}

/** Resolves one deck item without performing local UI work or touching a terminal transport. */
fun TerminalAccessoryAction.resolve(
    modifiers: AccessoryModifierSnapshot = AccessoryModifierSnapshot(),
): TerminalAccessoryDispatch = when (this) {
    is TerminalAccessoryAction.Modifier -> TerminalAccessoryDispatch.ToggleModifier(modifier)
    is TerminalAccessoryAction.Local -> TerminalAccessoryDispatch.Local(action)
    is TerminalAccessoryAction.TmuxPrefix -> TerminalAccessoryDispatch.Bytes(
        requireNotNull(encodeTerminalChord(chord)),
    )
    is TerminalAccessoryAction.Key -> key.resolveBytes(modifiers)
}

private fun TerminalExtraKey.resolveBytes(
    modifiers: AccessoryModifierSnapshot,
): TerminalAccessoryDispatch {
    val control = modifiers.control.isActive
    val alt = modifiers.alt.isActive
    val shift = modifiers.shift.isActive

    val modifiedNavigation = modifiedNavigationBytes(
        key = this,
        control = control,
        alt = alt,
        shift = shift,
    )
    if (modifiedNavigation != null) return TerminalAccessoryDispatch.Bytes(modifiedNavigation)

    if (this == TerminalExtraKey.TAB && shift) {
        if (control) {
            return TerminalAccessoryDispatch.Unsupported(
                uiText(R.string.terminal_key_control_shift_tab_unsupported),
            )
        }
        val shiftedTab = TerminalKeySequences.SHIFT_TAB
        return TerminalAccessoryDispatch.Bytes(if (alt) TerminalKeySequences.ESCAPE + shiftedTab else shiftedTab)
    }

    val base = requireNotNull(bytes)
    val directControlChord = this in directControlChordKeys
    val baseAscii = base.singleAsciiOrNull()
    if (shift && !directControlChord && (baseAscii == null || baseAscii !in ' '..'~')) {
        return TerminalAccessoryDispatch.Unsupported(
            uiText(R.string.terminal_key_shift_unsupported, uiToken(label)),
        )
    }

    val shifted = if (shift && !directControlChord) {
        byteArrayOf(shiftUsAscii(requireNotNull(baseAscii)).code.toByte())
    } else {
        base
    }
    val controlled = if (control && directControlChord) {
        shifted
    } else if (control) {
        val ascii = shifted.singleAsciiOrNull()
        ascii?.let(::controlByteForAscii)?.let { byteArrayOf(it) } ?: controlBytes
            ?: return TerminalAccessoryDispatch.Unsupported(
                uiText(R.string.terminal_key_control_unsupported, uiToken(label)),
            )
    } else {
        shifted
    }
    val outgoing = if (alt) TerminalKeySequences.ESCAPE + controlled else controlled.copyOf()
    return TerminalAccessoryDispatch.Bytes(outgoing)
}

private fun modifiedNavigationBytes(
    key: TerminalExtraKey,
    control: Boolean,
    alt: Boolean,
    shift: Boolean,
): ByteArray? {
    if (!control && !alt && !shift) return null
    val modifierParameter = 1 +
        (if (shift) 1 else 0) +
        (if (alt) 2 else 0) +
        (if (control) 4 else 0)
    return when (key) {
        TerminalExtraKey.UP -> modifiedCsi("1", modifierParameter, 'A')
        TerminalExtraKey.DOWN -> modifiedCsi("1", modifierParameter, 'B')
        TerminalExtraKey.RIGHT -> modifiedCsi("1", modifierParameter, 'C')
        TerminalExtraKey.LEFT -> modifiedCsi("1", modifierParameter, 'D')
        TerminalExtraKey.HOME -> modifiedCsi("1", modifierParameter, 'H')
        TerminalExtraKey.END -> modifiedCsi("1", modifierParameter, 'F')
        TerminalExtraKey.INSERT -> modifiedTilde("2", modifierParameter)
        TerminalExtraKey.DELETE -> modifiedTilde("3", modifierParameter)
        TerminalExtraKey.PAGE_UP -> modifiedTilde("5", modifierParameter)
        TerminalExtraKey.PAGE_DOWN -> modifiedTilde("6", modifierParameter)
        TerminalExtraKey.F1 -> modifiedCsi("1", modifierParameter, 'P')
        TerminalExtraKey.F2 -> modifiedCsi("1", modifierParameter, 'Q')
        TerminalExtraKey.F3 -> modifiedCsi("1", modifierParameter, 'R')
        TerminalExtraKey.F4 -> modifiedCsi("1", modifierParameter, 'S')
        TerminalExtraKey.F5 -> modifiedTilde("15", modifierParameter)
        TerminalExtraKey.F6 -> modifiedTilde("17", modifierParameter)
        TerminalExtraKey.F7 -> modifiedTilde("18", modifierParameter)
        TerminalExtraKey.F8 -> modifiedTilde("19", modifierParameter)
        TerminalExtraKey.F9 -> modifiedTilde("20", modifierParameter)
        TerminalExtraKey.F10 -> modifiedTilde("21", modifierParameter)
        TerminalExtraKey.F11 -> modifiedTilde("23", modifierParameter)
        TerminalExtraKey.F12 -> modifiedTilde("24", modifierParameter)
        else -> null
    }
}

/** Explicit US-ASCII shift-pair table used by accessory printable keys. */
fun shiftUsAscii(character: Char): Char = when (character) {
    in 'a'..'z' -> character.uppercaseChar()
    '1' -> '!'
    '2' -> '@'
    '3' -> '#'
    '4' -> '$'
    '5' -> '%'
    '6' -> '^'
    '7' -> '&'
    '8' -> '*'
    '9' -> '('
    '0' -> ')'
    '-' -> '_'
    '=' -> '+'
    '[' -> '{'
    ']' -> '}'
    '\\' -> '|'
    ';' -> ':'
    '\'' -> '"'
    ',' -> '<'
    '.' -> '>'
    '/' -> '?'
    '`' -> '~'
    else -> character
}

/** Encodes the bounded chord syntax accepted by KeyboardProfile. */
fun encodeTerminalChord(chord: String): ByteArray? {
    if (chord.length == 1 && !chord.single().isWhitespace() && !chord.single().isISOControl()) {
        return chord.toByteArray(Charsets.UTF_8)
    }
    val parts = chord.split('-', limit = 2)
    if (parts.size != 2 || parts.first() !in setOf("C", "M")) return null
    val keyBytes = when (val key = parts.last()) {
        "Space" -> byteArrayOf(' '.code.toByte())
        "Tab" -> TerminalKeySequences.TAB
        "Enter" -> TerminalKeySequences.ENTER
        "Escape" -> TerminalKeySequences.ESCAPE
        else -> if (key.length == 1 && !key.single().isWhitespace() && !key.single().isISOControl()) {
            key.toByteArray(Charsets.UTF_8)
        } else {
            return null
        }
    }
    return if (parts.first() == "M") {
        TerminalKeySequences.ESCAPE + keyBytes
    } else {
        when (parts.last()) {
            "Space" -> byteArrayOf(0)
            "Tab" -> TerminalKeySequences.TAB
            "Enter" -> TerminalKeySequences.ENTER
            "Escape" -> TerminalKeySequences.ESCAPE
            else -> keyBytes.singleAsciiOrNull()?.let(::controlByteForAscii)?.let { byteArrayOf(it) }
        }
    }
}

private fun ByteArray.singleAsciiOrNull(): Char? =
    takeIf { size == 1 && first().toInt() in 0..0x7f }?.first()?.toInt()?.toChar()

private fun controlByteForAscii(character: Char): Byte? = when (character) {
    in 'a'..'z', in 'A'..'Z' -> (character.uppercaseChar().code and 0x1f).toByte()
    ' ', '@' -> 0
    '[' -> 0x1b
    '\\' -> 0x1c
    ']' -> 0x1d
    '^' -> 0x1e
    '_' -> 0x1f
    '?' -> 0x7f
    else -> null
}

private fun modifiedCsi(prefix: String, modifier: Int, final: Char): ByteArray =
    "\u001B[$prefix;$modifier$final".toByteArray(Charsets.US_ASCII)

private fun modifiedTilde(prefix: String, modifier: Int): ByteArray =
    "\u001B[$prefix;$modifier~".toByteArray(Charsets.US_ASCII)

private val directControlChordKeys = setOf(
    TerminalExtraKey.CTRL_A,
    TerminalExtraKey.CTRL_B,
    TerminalExtraKey.CTRL_C,
    TerminalExtraKey.CTRL_D,
    TerminalExtraKey.CTRL_E,
    TerminalExtraKey.CTRL_K,
    TerminalExtraKey.CTRL_L,
    TerminalExtraKey.CTRL_R,
    TerminalExtraKey.CTRL_U,
    TerminalExtraKey.CTRL_W,
    TerminalExtraKey.CTRL_Z,
)

val TerminalExtraKey.supportsAccessoryRepeat: Boolean
    get() = this == TerminalExtraKey.UP ||
        this == TerminalExtraKey.DOWN ||
        this == TerminalExtraKey.LEFT ||
        this == TerminalExtraKey.RIGHT ||
        this == TerminalExtraKey.PAGE_UP ||
        this == TerminalExtraKey.PAGE_DOWN ||
        this == TerminalExtraKey.HOME ||
        this == TerminalExtraKey.END ||
        this == TerminalExtraKey.BACKSPACE ||
        this == TerminalExtraKey.DELETE
