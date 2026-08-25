package com.yanjiyu.terminalspike.terminal.view

import androidx.annotation.StringRes
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.ui.UiText
import com.yanjiyu.terminalspike.ui.uiText

enum class TerminalExtraKey(
    val label: String,
    val bytes: ByteArray? = null,
    val controlBytes: ByteArray? = null,
    @StringRes private val accessibilityDescriptionRes: Int? = null,
) {
    ESC("ESC", TerminalKeySequences.ESCAPE),
    CTRL("CTRL", accessibilityDescriptionRes = R.string.terminal_key_control_modifier),
    ALT("ALT", accessibilityDescriptionRes = R.string.terminal_key_alt_modifier),
    TAB("TAB", TerminalKeySequences.TAB),
    ENTER("↵", TerminalKeySequences.ENTER),
    BACKSPACE("⌫", TerminalKeySequences.BACKSPACE),
    INSERT("INS", TerminalKeySequences.INSERT),
    UP("↑", TerminalKeySequences.ARROW_UP, byteArrayOf(0x1b, 0x5b, 0x31, 0x3b, 0x35, 0x41)),
    DOWN("↓", TerminalKeySequences.ARROW_DOWN, byteArrayOf(0x1b, 0x5b, 0x31, 0x3b, 0x35, 0x42)),
    LEFT("←", TerminalKeySequences.ARROW_LEFT, byteArrayOf(0x1b, 0x5b, 0x31, 0x3b, 0x35, 0x44)),
    RIGHT("→", TerminalKeySequences.ARROW_RIGHT, byteArrayOf(0x1b, 0x5b, 0x31, 0x3b, 0x35, 0x43)),
    PAGE_UP("PGUP", TerminalKeySequences.PAGE_UP, byteArrayOf(0x1b, 0x5b, 0x35, 0x3b, 0x35, 0x7e)),
    PAGE_DOWN("PGDN", TerminalKeySequences.PAGE_DOWN, byteArrayOf(0x1b, 0x5b, 0x36, 0x3b, 0x35, 0x7e)),
    HOME("HOME", TerminalKeySequences.HOME),
    END("END", TerminalKeySequences.END),
    DELETE("DEL", TerminalKeySequences.DELETE),
    CTRL_C("^C", controlByte('C'), accessibilityDescriptionRes = R.string.terminal_key_control_c),
    CTRL_D("^D", controlByte('D'), accessibilityDescriptionRes = R.string.terminal_key_control_d),
    CTRL_Z("^Z", controlByte('Z'), accessibilityDescriptionRes = R.string.terminal_key_control_z),
    CTRL_A("^A", controlByte('A'), accessibilityDescriptionRes = R.string.terminal_key_control_a),
    CTRL_B("^B", controlByte('B'), accessibilityDescriptionRes = R.string.terminal_key_control_b),
    CTRL_E("^E", controlByte('E'), accessibilityDescriptionRes = R.string.terminal_key_control_e),
    CTRL_R("^R", controlByte('R'), accessibilityDescriptionRes = R.string.terminal_key_control_r),
    CTRL_W("^W", controlByte('W'), accessibilityDescriptionRes = R.string.terminal_key_control_w),
    CTRL_L("^L", controlByte('L'), accessibilityDescriptionRes = R.string.terminal_key_control_l),
    CTRL_U("^U", controlByte('U'), accessibilityDescriptionRes = R.string.terminal_key_control_u),
    CTRL_K("^K", controlByte('K'), accessibilityDescriptionRes = R.string.terminal_key_control_k),
    SLASH("/", textBytes("/")),
    PIPE("|", textBytes("|")),
    DASH("-", textBytes("-")),
    TILDE("~", textBytes("~")),
    BACKTICK("`", textBytes("`")),
    BACKSLASH("\\", textBytes("\\")),
    COLON(":", textBytes(":")),
    SEMICOLON(";", textBytes(";")),
    AT("@", textBytes("@")),
    HASH("#", textBytes("#")),
    DOLLAR("\$", textBytes("\$")),
    EQUALS("=", textBytes("=")),
    SPACE("SPACE", textBytes(" ")),
    EXCLAMATION("!", textBytes("!")),
    QUESTION("?", textBytes("?")),
    ASTERISK("*", textBytes("*")),
    PLUS("+", textBytes("+")),
    UNDERSCORE("_", textBytes("_")),
    PERIOD(".", textBytes(".")),
    COMMA(",", textBytes(",")),
    LEFT_PAREN("(", textBytes("(")),
    RIGHT_PAREN(")", textBytes(")")),
    LEFT_BRACKET("[", textBytes("[")),
    RIGHT_BRACKET("]", textBytes("]")),
    LEFT_BRACE("{", textBytes("{")),
    RIGHT_BRACE("}", textBytes("}")),
    SINGLE_QUOTE("'", textBytes("'")),
    DOUBLE_QUOTE("\"", textBytes("\"")),
    LESS_THAN("<", textBytes("<")),
    GREATER_THAN(">", textBytes(">")),
    AMPERSAND("&", textBytes("&")),
    CARET("^", textBytes("^")),
    PERCENT("%", textBytes("%")),
    F1("F1", TerminalKeySequences.F1),
    F2("F2", TerminalKeySequences.F2),
    F3("F3", TerminalKeySequences.F3),
    F4("F4", TerminalKeySequences.F4),
    F5("F5", TerminalKeySequences.F5),
    F6("F6", TerminalKeySequences.F6),
    F7("F7", TerminalKeySequences.F7),
    F8("F8", TerminalKeySequences.F8),
    F9("F9", TerminalKeySequences.F9),
    F10("F10", TerminalKeySequences.F10),
    F11("F11", TerminalKeySequences.F11),
    F12("F12", TerminalKeySequences.F12),
    HIDE_KEYBOARD("HIDE KB", accessibilityDescriptionRes = R.string.terminal_key_hide_keyboard),
    ;

    val isModifier: Boolean get() = this == CTRL || this == ALT
    val isLocalAction: Boolean get() = this == HIDE_KEYBOARD
    val accessibilityDescription: UiText
        get() = accessibilityDescriptionRes?.let(::uiText)
            ?: uiText(R.string.terminal_key_description, label)

    companion object {
        /** Exact v1 shipped deck, used only to distinguish that untouched default from custom decks. */
        internal val LEGACY_DEFAULT_ORDER: List<TerminalExtraKey> = listOf(
            ESC,
            SLASH,
            AT,
            DOLLAR,
            HOME,
            UP,
            END,
            PAGE_UP,
            CTRL_B,
            TAB,
            CTRL,
            CTRL_C,
            CTRL_W,
            LEFT,
            DOWN,
            RIGHT,
            HIDE_KEYBOARD,
        )

        /** Exact paged v2 default, upgraded only when the user never customized its order. */
        internal val PAGED_DEFAULT_ORDER: List<TerminalExtraKey> = listOf(
            ESC,
            CTRL,
            ALT,
            TAB,
            CTRL_C,
            CTRL_W,
            CTRL_D,
            CTRL_L,
            CTRL_R,
            CTRL_U,
            CTRL_A,
            CTRL_E,
            HOME,
            UP,
            END,
            PAGE_UP,
            LEFT,
            DOWN,
            RIGHT,
            CTRL_B,
            SLASH,
            AT,
            HIDE_KEYBOARD,
        )

        /** The previous 18-key live deck, retained to upgrade untouched defaults. */
        internal val PREVIOUS_DEFAULT_ORDER: List<TerminalExtraKey> = listOf(
            ESC,
            SLASH,
            AT,
            DOLLAR,
            HOME,
            UP,
            END,
            PAGE_UP,
            CTRL_B,
            TAB,
            CTRL,
            CTRL_C,
            CTRL_W,
            LEFT,
            DOWN,
            RIGHT,
            ENTER,
            HIDE_KEYBOARD,
        )

        /** The complete shipped live deck: exactly two phone rows of ten direct actions. */
        val DEFAULT_ORDER: List<TerminalExtraKey> = listOf(
            ESC,
            SLASH,
            AT,
            DOLLAR,
            HOME,
            UP,
            END,
            PAGE_UP,
            CTRL_B,
            BACKSPACE,
            TAB,
            CTRL,
            ALT,
            CTRL_C,
            CTRL_W,
            LEFT,
            DOWN,
            RIGHT,
            ENTER,
            HIDE_KEYBOARD,
        )
    }
}

private fun controlByte(character: Char): ByteArray = byteArrayOf((character.code and 0x1f).toByte())

private fun textBytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)
