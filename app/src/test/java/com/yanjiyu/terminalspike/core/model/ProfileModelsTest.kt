package com.yanjiyu.terminalspike.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileModelsTest {
    @Test
    fun snippetDefaultsToInsertAndRequiresConfirmationForImmediateMultilineSend() {
        val snippet = validSnippet(command = "printf 'one\\n'\nprintf 'two\\n'")

        assertEquals(SnippetTapAction.INSERT, snippet.tapAction)
        assertThrows(IllegalArgumentException::class.java) {
            snippet.copy(
                tapAction = SnippetTapAction.SEND_IMMEDIATELY,
                confirmMultilineExecution = false,
            )
        }
        assertEquals(
            SnippetTapAction.SEND_IMMEDIATELY,
            snippet.copy(tapAction = SnippetTapAction.SEND_IMMEDIATELY).tapAction,
        )
    }

    @Test
    fun snippetRejectsEmptyOversizedAndControlBearingCommands() {
        assertThrows(IllegalArgumentException::class.java) { validSnippet(command = "") }
        assertThrows(IllegalArgumentException::class.java) {
            validSnippet(command = "x".repeat(ModelLimits.MAX_COMMAND_LENGTH + 1))
        }
        assertThrows(IllegalArgumentException::class.java) { validSnippet(command = "echo\u0000unsafe") }
    }

    @Test
    fun terminalProfileCoversGeometryCursorBellScrollLinksAndTerm() {
        val profile = validTerminalProfile()

        assertEquals("nord", profile.themeId)
        assertEquals(CursorStyle.BEAM, profile.cursorStyle)
        assertEquals(TouchScrollMode.AUTO, profile.scroll.touchMode)
        assertEquals(RemoteClipboardMode.ASK, profile.links.remoteClipboardMode)
        assertEquals("xterm-256color", profile.termValue)
    }

    @Test
    fun terminalGeometryAndScrollbackAreFiniteAndBounded() {
        val profile = validTerminalProfile()

        assertThrows(IllegalArgumentException::class.java) { profile.copy(fontSizeSp = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { profile.copy(lineHeightMultiplier = 3.1f) }
        assertThrows(IllegalArgumentException::class.java) { profile.copy(letterSpacingEm = Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) {
            profile.copy(scrollbackLines = ModelLimits.MAX_SCROLLBACK_LINES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) { profile.copy(termValue = "xterm 256color") }
    }

    @Test
    fun keyboardProfilePreservesOrderAndCoversHostSelectableSettings() {
        val actions = listOf(
            KeyboardAction.ESCAPE,
            KeyboardAction.CONTROL,
            KeyboardAction.ARROW_LEFT,
            KeyboardAction.ARROW_RIGHT,
            KeyboardAction.TMUX_PREFIX,
            KeyboardAction.SNIPPETS,
        )
        val profile = KeyboardProfile(
            id = KEYBOARD_PROFILE_ID,
            name = "tmux",
            orderedActions = actions,
            layout = KeyboardLayout.TWO_ROWS,
            modifierBehavior = ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK,
            hapticFeedbackEnabled = true,
            keyRepeatEnabled = true,
            inputMode = TerminalInputMode.RAW,
            tmuxPrefix = "C-a",
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        )

        assertEquals(actions, profile.orderedActions)
        assertEquals(2, profile.layout.rowCount)
        assertEquals("C-a", profile.tmuxPrefix)
    }

    @Test
    fun keyboardProfileRejectsEmptyDuplicateOrUnboundedActions() {
        val profile = validKeyboardProfile()

        assertThrows(IllegalArgumentException::class.java) { profile.copy(orderedActions = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            profile.copy(orderedActions = listOf(KeyboardAction.ESCAPE, KeyboardAction.ESCAPE))
        }
        assertThrows(IllegalArgumentException::class.java) { profile.copy(tmuxPrefix = " ") }
        assertThrows(IllegalArgumentException::class.java) { profile.copy(tmuxPrefix = "not-a-chord") }
    }

    @Test
    fun hostKeyboardReferenceProvidesPerHostProfileOverride() {
        val host = HostProfile(
            id = HOST_ID,
            displayName = "Vim host",
            hostname = "vim.example",
            port = 22,
            username = "vim",
            protocol = ConnectionProtocol.SSH,
            credentialId = CREDENTIAL_ID,
            keyboardProfileId = KEYBOARD_PROFILE_ID,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )

        assertEquals(KEYBOARD_PROFILE_ID, host.keyboardProfileId)
    }

    private fun validSnippet(command: String) = Snippet(
        id = SNIPPET_ID,
        name = "Deploy status",
        group = "Operations",
        command = command,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun validTerminalProfile() = TerminalProfile(
        id = TERMINAL_PROFILE_ID,
        name = "Nord 14",
        themeId = "nord",
        fontId = "system_monospace",
        fontSizeSp = 14f,
        lineHeightMultiplier = 1.1f,
        letterSpacingEm = 0f,
        cursorStyle = CursorStyle.BEAM,
        scrollbackLines = 20_000,
        bell = BellSettings(visualBellEnabled = true),
        scroll = ScrollBehavior(touchMode = TouchScrollMode.AUTO),
        links = LinkBehavior(remoteClipboardMode = RemoteClipboardMode.ASK),
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun validKeyboardProfile() = KeyboardProfile(
        id = KEYBOARD_PROFILE_ID,
        name = "General",
        orderedActions = listOf(KeyboardAction.ESCAPE),
        layout = KeyboardLayout.ONE_ROW,
        modifierBehavior = ModifierBehavior.ONE_SHOT,
        hapticFeedbackEnabled = false,
        keyRepeatEnabled = true,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private companion object {
        const val HOST_ID = "00000000-0000-4000-8000-000000000001"
        const val CREDENTIAL_ID = "00000000-0000-4000-8000-000000000002"
        const val TERMINAL_PROFILE_ID = "00000000-0000-4000-8000-000000000003"
        const val KEYBOARD_PROFILE_ID = "00000000-0000-4000-8000-000000000004"
        const val SNIPPET_ID = "00000000-0000-4000-8000-000000000005"
    }
}
