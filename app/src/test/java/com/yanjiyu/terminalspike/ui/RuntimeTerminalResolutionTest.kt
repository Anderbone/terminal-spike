package com.yanjiyu.terminalspike.ui

import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeTerminalResolutionTest {
    @Test
    fun explicitHostProfileSuppliesTermAndStartup() {
        val default = terminalProfile(DEFAULT_PROFILE_ID, "xterm-256color")
        val selected = terminalProfile(SELECTED_PROFILE_ID, "xterm-direct")
        val command = "tmux new-session -A -s ops"

        val resolution = resolveRuntimeTerminalSelection(
            host = host(terminalProfileId = selected.id, startupCommand = command),
            defaultProfile = default,
            profilesById = mapOf(default.id to default, selected.id to selected),
        )

        assertEquals(selected.id, resolution.explicitProfileId)
        assertEquals("xterm-direct", resolution.profile?.termValue)
        assertEquals(command, resolution.startupCommand)
        assertFalse(resolution.toString().contains(command))
    }

    @Test
    fun missingHostOverrideUsesTheCurrentDefaultProfile() {
        val default = terminalProfile(DEFAULT_PROFILE_ID, "vt100")

        val resolution = resolveRuntimeTerminalSelection(
            host = host(terminalProfileId = null, startupCommand = null),
            defaultProfile = default,
            profilesById = mapOf(default.id to default),
        )

        assertNull(resolution.explicitProfileId)
        assertEquals("vt100", resolution.profile?.termValue)
        assertNull(resolution.startupCommand)
    }

    @Test
    fun staleExplicitReferenceNeverFallsBackAndMustBlockBeforeHandshake() {
        val default = terminalProfile(DEFAULT_PROFILE_ID, "xterm-256color")

        val resolution = resolveRuntimeTerminalSelection(
            host = host(terminalProfileId = STALE_PROFILE_ID, startupCommand = "echo never"),
            defaultProfile = default,
            profilesById = mapOf(default.id to default),
        )

        assertEquals(STALE_PROFILE_ID, resolution.explicitProfileId)
        assertNull(resolution.profile)
    }

    private fun host(
        terminalProfileId: String?,
        startupCommand: String?,
    ) = HostProfile(
        id = HOST_ID,
        displayName = "Operations",
        hostname = "example.test",
        port = 22,
        username = "alice",
        protocol = ConnectionProtocol.SSH,
        credentialId = null,
        terminalProfileId = terminalProfileId,
        startupCommand = startupCommand,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun terminalProfile(id: String, term: String) = TerminalProfile(
        id = id,
        name = "Terminal $term",
        themeId = "current",
        fontId = "system-monospace",
        fontSizeSp = 14f,
        lineHeightMultiplier = 1f,
        letterSpacingEm = 0f,
        cursorStyle = CursorStyle.BLOCK,
        scrollbackLines = 2_000,
        termValue = term,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private companion object {
        const val HOST_ID = "10000000-0000-4000-8000-000000000001"
        const val DEFAULT_PROFILE_ID = "20000000-0000-4000-8000-000000000002"
        const val SELECTED_PROFILE_ID = "30000000-0000-4000-8000-000000000003"
        const val STALE_PROFILE_ID = "40000000-0000-4000-8000-000000000004"
    }
}
