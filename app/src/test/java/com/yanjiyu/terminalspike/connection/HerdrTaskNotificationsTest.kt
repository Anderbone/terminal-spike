package com.yanjiyu.terminalspike.connection

import org.junit.Assert.*
import org.junit.Test

class HerdrTaskNotificationsTest {
    private val working = HerdrAgentState("w6:pC", "term1", "session1", "working", 10)

    @Test fun observesActiveAndBackgroundCompletionOnceWithoutReplayingExistingIdleAgents() {
        val tracker = HerdrTaskNotificationTracker()
        val idle = working.copy(state = "idle", sequence = 11)
        assertFalse(tracker.observe(listOf(idle)))
        assertFalse(tracker.observe(listOf(working.copy(sequence = 12))))
        assertTrue(tracker.observe(listOf(idle.copy(sequence = 13))))
        assertFalse(tracker.observe(listOf(idle.copy(sequence = 13))))
        assertFalse(tracker.observe(listOf(idle.copy(state = "done", sequence = 13))))
        assertFalse(tracker.observe(listOf(working.copy(sequence = 14))))
        assertTrue(tracker.observe(listOf(idle.copy(state = "done", sequence = 15))))
    }

    @Test fun failuresRestartsMissingPanesAndUncertainStatesCannotCreateCompletion() {
        for (interruption in listOf(null, emptyList(), listOf(working.copy(state = "unknown")),
            listOf(working.copy(state = "blocked")))) {
            val tracker = HerdrTaskNotificationTracker()
            assertFalse(tracker.observe(listOf(working)))
            assertFalse(tracker.observe(interruption))
            assertFalse(tracker.observe(listOf(working.copy(state = "idle", sequence = 11))))
        }
        for (idle in listOf(
            working.copy(state = "idle", sequence = 10),
            working.copy(state = "idle", sequence = 1),
            working.copy(state = "idle", sequence = 11, terminalId = "replacement"),
            working.copy(state = "idle", sequence = 11, agentSession = "replacement"),
        )) {
            val tracker = HerdrTaskNotificationTracker()
            tracker.observe(listOf(working))
            assertFalse(tracker.observe(listOf(idle)))
        }
    }

    @Test fun simultaneousCompletionsCoalesceAndUnchangedWorkingPanesRemainTracked() {
        val tracker = HerdrTaskNotificationTracker()
        val second = working.copy(paneId = "w9:p1", terminalId = "term2")
        tracker.observe(listOf(working, second))
        assertTrue(tracker.observe(listOf(working.copy(state = "idle", sequence = 11), second)))
        assertTrue(tracker.observe(listOf(working.copy(state = "idle", sequence = 11),
            second.copy(state = "done", sequence = 12))))
        assertFalse(tracker.observe(emptyList()))
    }

    private val payload = """{"result":{"type":"agent_list","agents":[{"pane_id":"w6:pC","terminal_id":"term1","agent_session":{"value":"session1"},"agent_status":"working","state_change_seq":10,"terminal_title":"private task text"}]}}"""

    @Test fun boundedExplicitSessionQueryRetainsOnlyLifecycleMetadata() {
        var command = ""
        val states = captureHerdrAgentStates({ command = it; TmuxExecOutput(payload.toByteArray(), 0) },
            HerdrStartupChoice("/usr/bin/herdr", "work ' quoted"))
        assertEquals(listOf(working), states)
        assertEquals("'/usr/bin/herdr' '--session' 'work '\\'' quoted' 'agent' 'list'", command)
        assertFalse(states.toString().contains("private task text"))
    }

    @Test fun malformedOversizedFailedAndDuplicateMetadataFailClosed() {
        val agent = payload.substringAfter("\"agents\":[").substringBeforeLast("]}}")
        val invalid = listOf(
            "{}", payload.replace("agent_list", "other"), payload.replace("w6:pC", "w6:p;bad"),
            payload.replace("working", "bogus"), payload.replace(":10", ":-1"),
            """{"result":{"type":"agent_list","agents":[$agent,$agent]}}""",
            """{"result":{"type":"agent_list","agents":[${List(257) { agent }.joinToString(",")}]}}""",
        ).map { TmuxExecOutput(it.toByteArray(), 0) } + listOf(
            TmuxExecOutput(payload.toByteArray(), 1), TmuxExecOutput(ByteArray(256 * 1024 + 1), 0),
        )
        invalid.forEach { output ->
            assertNull(captureHerdrAgentStates({ output }, HerdrStartupChoice("/usr/bin/herdr", "default")))
        }
    }
}
