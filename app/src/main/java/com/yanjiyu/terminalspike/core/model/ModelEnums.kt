package com.yanjiyu.terminalspike.core.model

/** A persisted value whose wire representation is deliberately independent of its Kotlin name. */
interface WireCoded {
    val wireCode: String
}

enum class ConnectionProtocol(override val wireCode: String) : WireCoded {
    SSH("ssh"),
    MOSH("mosh"),
    ;

    companion object {
        fun fromWireCode(wireCode: String): ConnectionProtocol = decodeWireCode(wireCode, entries)
    }
}

enum class SshCredentialKind(override val wireCode: String) : WireCoded {
    PASSWORD("password"),
    PRIVATE_KEY("private_key"),
    KEYBOARD_INTERACTIVE("keyboard_interactive"),
    ;

    companion object {
        fun fromWireCode(wireCode: String): SshCredentialKind = decodeWireCode(wireCode, entries)
    }
}

enum class SshKeyOrigin(override val wireCode: String) : WireCoded {
    IMPORTED("imported"),
    GENERATED("generated"),
    ;

    companion object {
        fun fromWireCode(wireCode: String): SshKeyOrigin = decodeWireCode(wireCode, entries)
    }
}

enum class SnippetTapAction(override val wireCode: String) : WireCoded {
    INSERT("insert"),
    SEND_IMMEDIATELY("send_immediately"),
    ;

    companion object {
        fun fromWireCode(wireCode: String): SnippetTapAction = decodeWireCode(wireCode, entries)
    }
}

enum class CursorStyle(override val wireCode: String) : WireCoded {
    BLOCK("block"),
    UNDERLINE("underline"),
    BEAM("beam"),
    ;

    companion object {
        fun fromWireCode(wireCode: String): CursorStyle = decodeWireCode(wireCode, entries)
    }
}

enum class TouchScrollMode(override val wireCode: String) : WireCoded {
    AUTO("auto"),
    LOCAL_SCROLLBACK("local_scrollback"),
    REMOTE_MOUSE("remote_mouse"),
    ;

    companion object {
        fun fromWireCode(wireCode: String): TouchScrollMode = decodeWireCode(wireCode, entries)
    }
}

enum class RemoteClipboardMode(override val wireCode: String) : WireCoded {
    DISABLED("disabled"),
    ASK("ask"),
    ;

    companion object {
        fun fromWireCode(wireCode: String): RemoteClipboardMode = decodeWireCode(wireCode, entries)
    }
}

enum class KeyboardLayout(override val wireCode: String, val rowCount: Int) : WireCoded {
    ONE_ROW("one_row", 1),
    TWO_ROWS("two_rows", 2),
    ;

    companion object {
        fun fromWireCode(wireCode: String): KeyboardLayout = decodeWireCode(wireCode, entries)
    }
}

enum class ModifierBehavior(override val wireCode: String) : WireCoded {
    ONE_SHOT("one_shot"),
    ONE_SHOT_WITH_DOUBLE_TAP_LOCK("one_shot_with_double_tap_lock"),
    ;

    companion object {
        fun fromWireCode(wireCode: String): ModifierBehavior = decodeWireCode(wireCode, entries)
    }
}

enum class TerminalInputMode(override val wireCode: String) : WireCoded {
    RAW("raw"),
    TEXT("text"),
    ;

    companion object {
        fun fromWireCode(wireCode: String): TerminalInputMode = decodeWireCode(wireCode, entries)
    }
}

enum class ReconnectPolicy(override val wireCode: String) : WireCoded {
    DISABLED("disabled"),
    AUTOMATIC("automatic"),
    ;

    companion object {
        fun fromWireCode(wireCode: String): ReconnectPolicy = decodeWireCode(wireCode, entries)
    }
}

/** Explicit policy for starting a fresh SSH shell after an eligible Mosh transport failure. */
enum class MoshFallbackPolicy(override val wireCode: String) : WireCoded {
    NEVER("never"),
    ASK("ask"),
    AUTOMATIC("automatic"),
    ;

    companion object {
        fun fromWireCode(wireCode: String): MoshFallbackPolicy = decodeWireCode(wireCode, entries)
    }
}

enum class SessionState(override val wireCode: String) : WireCoded {
    CONNECTING("connecting"),
    CONNECTED("connected"),
    RECONNECTING("reconnecting"),
    DISCONNECTED("disconnected"),
    FAILED("failed"),
    ;

    val isActive: Boolean
        get() = this == CONNECTING || this == CONNECTED || this == RECONNECTING

    companion object {
        fun fromWireCode(wireCode: String): SessionState = decodeWireCode(wireCode, entries)
    }
}

enum class KeyboardAction(override val wireCode: String) : WireCoded {
    ESCAPE("escape"),
    CONTROL("control"),
    ALT("alt"),
    TAB("tab"),
    SHIFT("shift"),
    ARROW_UP("arrow_up"),
    ARROW_DOWN("arrow_down"),
    ARROW_LEFT("arrow_left"),
    ARROW_RIGHT("arrow_right"),
    HOME("home"),
    END("end"),
    PAGE_UP("page_up"),
    PAGE_DOWN("page_down"),
    BACKSPACE("backspace"),
    INSERT("insert"),
    DELETE("delete"),
    ENTER("enter"),
    SLASH("slash"),
    BACKSLASH("backslash"),
    PIPE("pipe"),
    TILDE("tilde"),
    BACKTICK("backtick"),
    HYPHEN("hyphen"),
    UNDERSCORE("underscore"),
    AT_SIGN("at_sign"),
    F1("f1"),
    F2("f2"),
    F3("f3"),
    F4("f4"),
    F5("f5"),
    F6("f6"),
    F7("f7"),
    F8("f8"),
    F9("f9"),
    F10("f10"),
    F11("f11"),
    F12("f12"),
    CTRL_C("ctrl_c"),
    CTRL_D("ctrl_d"),
    CTRL_L("ctrl_l"),
    CTRL_R("ctrl_r"),
    CTRL_W("ctrl_w"),
    CTRL_U("ctrl_u"),
    CTRL_A("ctrl_a"),
    CTRL_B("ctrl_b"),
    CTRL_E("ctrl_e"),
    CTRL_K("ctrl_k"),
    CTRL_Z("ctrl_z"),
    TMUX_PREFIX("tmux_prefix"),
    PASTE("paste"),
    SNIPPETS("snippets"),
    HIDE_KEYBOARD("hide_keyboard"),
    KEYBOARD_SETTINGS("keyboard_settings"),
    COLON("colon"),
    SEMICOLON("semicolon"),
    HASH("hash"),
    DOLLAR("dollar"),
    EQUALS("equals"),
    SPACE("space"),
    EXCLAMATION("exclamation"),
    QUESTION("question"),
    ASTERISK("asterisk"),
    PLUS("plus"),
    PERIOD("period"),
    COMMA("comma"),
    LEFT_PAREN("left_paren"),
    RIGHT_PAREN("right_paren"),
    LEFT_BRACKET("left_bracket"),
    RIGHT_BRACKET("right_bracket"),
    LEFT_BRACE("left_brace"),
    RIGHT_BRACE("right_brace"),
    SINGLE_QUOTE("single_quote"),
    DOUBLE_QUOTE("double_quote"),
    LESS_THAN("less_than"),
    GREATER_THAN("greater_than"),
    AMPERSAND("ampersand"),
    CARET("caret"),
    PERCENT("percent"),
    ;

    companion object {
        fun fromWireCode(wireCode: String): KeyboardAction = decodeWireCode(wireCode, entries)
    }
}

private fun <T : WireCoded> decodeWireCode(wireCode: String, values: Iterable<T>): T =
    values.firstOrNull { it.wireCode == wireCode }
        ?: throw IllegalArgumentException("Unknown wire code: $wireCode")
