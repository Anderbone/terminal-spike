package com.yanjiyu.terminalspike.ui.connections

import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy
import com.yanjiyu.terminalspike.core.model.ReconnectPolicy
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsManagementTest {
    @Test
    fun fullMoshHostDraftValidatesAndPreservesAdvancedFields() {
        val result = validateHostEditor(
            HostEditorDraft(
                displayName = "Production",
                protocol = ConnectionProtocol.MOSH,
                hostname = "server.example",
                port = "2222",
                username = "alice",
                authenticationMethod = HostAuthenticationMethod.PRIVATE_KEY,
                keyIdentityId = KEY_ID,
                terminalProfileId = PROFILE_ID,
                keyboardProfileId = KEYBOARD_ID,
                isFavourite = true,
                group = "Work",
                tag = "prod",
                startupCommand = "tmux attach || tmux new",
                keepaliveMode = HostKeepaliveMode.CUSTOM,
                keepaliveSeconds = "45",
                reconnectMode = HostReconnectMode.AUTOMATIC,
                moshPortMode = MoshPortMode.RANGE,
                moshRangeFirst = "60000",
                moshRangeLast = "60010",
                moshServerCommand = "/usr/local/bin/mosh-server",
                moshLocale = "en_GB.UTF-8",
                moshFallbackPolicy = MoshFallbackPolicy.AUTOMATIC,
            ),
            availableKeyIds = setOf(KEY_ID),
        )

        assertTrue(result.errors.isEmpty)
        val value = requireNotNull(result.value)
        assertEquals(45, value.keepaliveIntervalSeconds)
        assertEquals(ReconnectPolicy.AUTOMATIC, value.reconnectPolicy)
        assertEquals(60_000, value.moshPortRange?.first)
        assertEquals(60_010, value.moshPortRange?.last)
        assertEquals("/usr/local/bin/mosh-server", value.moshServerCommand)
        assertEquals("en_GB.UTF-8", value.moshLocale)
        assertEquals(MoshFallbackPolicy.AUTOMATIC, value.moshFallbackPolicy)
        assertTrue(value.isFavourite)
    }

    @Test
    fun switchingMoshDraftToSshDropsEveryMoshOnlyFieldBeforeBuildingProfile() {
        val result = validateHostEditor(
            HostEditorDraft(
                displayName = "Production",
                protocol = ConnectionProtocol.SSH,
                hostname = "server.example",
                port = "22",
                username = "alice",
                moshPortMode = MoshPortMode.RANGE,
                moshPort = "60000",
                moshRangeFirst = "60000",
                moshRangeLast = "60010",
                moshServerCommand = "/usr/local/bin/mosh-server",
                moshLocale = "invalid locale",
                moshFallbackPolicy = MoshFallbackPolicy.AUTOMATIC,
            ),
            availableKeyIds = emptySet(),
        )

        assertTrue(result.errors.isEmpty)
        val value = requireNotNull(result.value)
        assertNull(value.moshPort)
        assertNull(value.moshPortRange)
        assertNull(value.moshServerCommand)
        assertNull(value.moshLocale)
        assertEquals(MoshFallbackPolicy.NEVER, value.moshFallbackPolicy)
        val profile = value.toProfile(nowEpochMillis = 42)
        assertEquals(ConnectionProtocol.SSH, profile.protocol)
        assertNull(profile.moshPort)
        assertNull(profile.moshPortRange)
        assertNull(profile.moshServerCommand)
        assertNull(profile.moshLocale)
        assertEquals(MoshFallbackPolicy.NEVER, profile.moshFallbackPolicy)
    }

    @Test
    fun invalidFieldsStayFieldLocalAndDoNotProduceAProfile() {
        val result = validateHostEditor(
            HostEditorDraft(
                displayName = "",
                hostname = "bad host",
                port = "70000",
                username = "bad user",
                authenticationMethod = HostAuthenticationMethod.PRIVATE_KEY,
                keyIdentityId = KEY_ID,
                keepaliveMode = HostKeepaliveMode.CUSTOM,
                keepaliveSeconds = "1",
            ),
            availableKeyIds = emptySet(),
        )

        assertNull(result.value)
        assertNotNull(result.errors.displayName)
        assertNotNull(result.errors.hostname)
        assertNotNull(result.errors.port)
        assertNotNull(result.errors.username)
        assertNotNull(result.errors.authentication)
        assertNotNull(result.errors.keepalive)
    }

    @Test
    fun multilineImmediateSnippetAlwaysRequiresConfirmationAndGetsAUuid() {
        val result = validateSnippetEditor(
            SnippetEditorDraft(
                name = "Deploy",
                command = "build\ndeploy",
                tapAction = SnippetTapAction.SEND_IMMEDIATELY,
                confirmMultilineExecution = false,
            ),
            nowEpochMillis = 42,
        )

        val snippet = requireNotNull(result.snippet)
        assertTrue(snippet.confirmMultilineExecution)
        assertEquals(36, snippet.id.length)
        assertEquals(42, snippet.createdAtEpochMillis)
        assertFalse(result.errors.name != null || result.errors.command != null)
    }

    private companion object {
        const val KEY_ID = "10000000-0000-4000-8000-000000000001"
        const val PROFILE_ID = "10000000-0000-4000-8000-000000000002"
        const val KEYBOARD_ID = "10000000-0000-4000-8000-000000000003"
    }
}
