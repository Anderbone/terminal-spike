package com.yanjiyu.terminalspike.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RecentSessionTest {
    @Test
    fun activeSessionContainsOnlyDisplaySafeHostMetadata() {
        val session = RecentSession(
            id = SESSION_ID,
            hostProfileId = HOST_ID,
            hostDisplayName = "Production shell",
            protocol = ConnectionProtocol.SSH,
            state = SessionState.CONNECTED,
            startedAtEpochMillis = 100,
            lastActivityAtEpochMillis = 150,
            terminalTitle = "api-01 — tmux",
        )

        assertEquals("Production shell", session.hostDisplayName)
        assertEquals("api-01 — tmux", session.terminalTitle)
    }

    @Test
    fun endedSessionRequiresEndTimeAfterLastActivity() {
        val ended = RecentSession(
            id = SESSION_ID,
            hostProfileId = HOST_ID,
            hostDisplayName = "Production shell",
            protocol = ConnectionProtocol.MOSH,
            state = SessionState.DISCONNECTED,
            startedAtEpochMillis = 100,
            lastActivityAtEpochMillis = 150,
            endedAtEpochMillis = 160,
        )

        assertEquals(160L, ended.endedAtEpochMillis)
        assertThrows(IllegalArgumentException::class.java) {
            ended.copy(endedAtEpochMillis = 149)
        }
    }

    @Test
    fun activeAndEndedStateCombinationsCannotDisagree() {
        assertThrows(IllegalArgumentException::class.java) {
            validActiveSession().copy(endedAtEpochMillis = 200)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validActiveSession().copy(state = SessionState.FAILED)
        }
    }

    @Test
    fun unsanitizedOscTitleIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validActiveSession().copy(terminalTitle = "safe\nnot safe")
        }
    }

    @Test
    fun historyCanSurviveHostDeletionWithoutClaimingReconnectMetadata() {
        assertNull(validActiveSession().copy(hostProfileId = null).hostProfileId)
    }

    @Test
    fun endpointIdentityAcceptsOnlyFixedLowercaseHmacTokens() {
        val token = "0a".repeat(32)

        assertEquals(token, validActiveSession().copy(endpointIdentityToken = token).endpointIdentityToken)
        listOf("0a", "0A".repeat(32), "gg".repeat(32)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                validActiveSession().copy(endpointIdentityToken = invalid)
            }
        }
    }

    @Test
    fun recentHostActivityContainsOnlyValidatedLookupMetadata() {
        val activity = RecentHostActivity(
            hostProfileId = HOST_ID,
            lastActivityAtEpochMillis = 150,
        )

        assertEquals(HOST_ID, activity.hostProfileId)
        assertEquals(150L, activity.lastActivityAtEpochMillis)
        assertThrows(IllegalArgumentException::class.java) {
            activity.copy(hostProfileId = "example.test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            activity.copy(lastActivityAtEpochMillis = -1)
        }
    }

    private fun validActiveSession() = RecentSession(
        id = SESSION_ID,
        hostProfileId = HOST_ID,
        hostDisplayName = "Shell",
        protocol = ConnectionProtocol.SSH,
        state = SessionState.CONNECTED,
        startedAtEpochMillis = 100,
        lastActivityAtEpochMillis = 150,
    )

    private companion object {
        const val SESSION_ID = "00000000-0000-4000-8000-000000000001"
        const val HOST_ID = "00000000-0000-4000-8000-000000000002"
    }
}
