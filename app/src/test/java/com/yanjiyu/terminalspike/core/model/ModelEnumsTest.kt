package com.yanjiyu.terminalspike.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEnumsTest {
    @Test
    fun everyEnumHasUniqueStableWireCodesAndRoundTrips() {
        assertRoundTrips(ConnectionProtocol.entries, ConnectionProtocol::fromWireCode)
        assertRoundTrips(SshCredentialKind.entries, SshCredentialKind::fromWireCode)
        assertRoundTrips(SshKeyOrigin.entries, SshKeyOrigin::fromWireCode)
        assertRoundTrips(SnippetTapAction.entries, SnippetTapAction::fromWireCode)
        assertRoundTrips(CursorStyle.entries, CursorStyle::fromWireCode)
        assertRoundTrips(TouchScrollMode.entries, TouchScrollMode::fromWireCode)
        assertRoundTrips(RemoteClipboardMode.entries, RemoteClipboardMode::fromWireCode)
        assertRoundTrips(KeyboardLayout.entries, KeyboardLayout::fromWireCode)
        assertRoundTrips(ModifierBehavior.entries, ModifierBehavior::fromWireCode)
        assertRoundTrips(TerminalInputMode.entries, TerminalInputMode::fromWireCode)
        assertRoundTrips(ReconnectPolicy.entries, ReconnectPolicy::fromWireCode)
        assertRoundTrips(SessionState.entries, SessionState::fromWireCode)
        assertRoundTrips(KeyboardAction.entries, KeyboardAction::fromWireCode)
    }

    @Test
    fun representativeWireCodesDoNotDependOnKotlinNames() {
        assertEquals("private_key", SshCredentialKind.PRIVATE_KEY.wireCode)
        assertEquals("send_immediately", SnippetTapAction.SEND_IMMEDIATELY.wireCode)
        assertEquals("one_shot_with_double_tap_lock", ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK.wireCode)
        assertEquals("keyboard_settings", KeyboardAction.KEYBOARD_SETTINGS.wireCode)
    }

    @Test
    fun keyboardActionsCoverTheCompleteAccessoryContract() {
        val expectedCodes = setOf(
            "escape", "control", "alt", "tab", "shift",
            "arrow_up", "arrow_down", "arrow_left", "arrow_right",
            "home", "end", "page_up", "page_down", "backspace", "insert", "delete", "enter",
            "slash", "backslash", "pipe", "tilde", "backtick", "hyphen", "underscore", "at_sign",
            "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10", "f11", "f12",
            "ctrl_c", "ctrl_d", "ctrl_l", "ctrl_r", "ctrl_w", "ctrl_u", "ctrl_a", "ctrl_b",
            "ctrl_e", "ctrl_k", "ctrl_z",
            "tmux_prefix", "paste", "select_images", "snippets", "tmux_sessions", "hide_keyboard",
            "keyboard_settings",
            "colon", "semicolon", "hash", "dollar", "equals", "space", "exclamation", "question",
            "asterisk", "plus", "period", "comma", "left_paren", "right_paren", "left_bracket",
            "right_bracket", "left_brace", "right_brace", "single_quote", "double_quote",
            "less_than", "greater_than", "ampersand", "caret", "percent",
        )

        assertEquals(expectedCodes, KeyboardAction.entries.map { it.wireCode }.toSet())
    }

    @Test
    fun unknownWireCodesFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionProtocol.fromWireCode("SSH")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardAction.fromWireCode("future_action")
        }
    }

    @Test
    fun activeSessionStateIsExplicit() {
        assertTrue(SessionState.CONNECTING.isActive)
        assertTrue(SessionState.CONNECTED.isActive)
        assertTrue(SessionState.RECONNECTING.isActive)
        assertFalse(SessionState.DISCONNECTED.isActive)
        assertFalse(SessionState.FAILED.isActive)
    }

    private fun <T : WireCoded> assertRoundTrips(values: Iterable<T>, decoder: (String) -> T) {
        val entries = values.toList()
        assertEquals(entries.size, entries.map(WireCoded::wireCode).distinct().size)
        entries.forEach { value -> assertEquals(value, decoder(value.wireCode)) }
    }
}
