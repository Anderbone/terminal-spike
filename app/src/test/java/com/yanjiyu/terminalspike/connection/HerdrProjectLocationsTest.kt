package com.yanjiyu.terminalspike.connection

import org.junit.Assert.*
import org.junit.Test

class HerdrProjectLocationsTest {
    @Test fun usesForegroundDirectoryAndFocusedPaneBeforeWorkspacePeers() {
        val output = TmuxExecOutput("""{"result":{"snapshot":{
            "focused_pane_id":"w9:pB",
            "workspaces":[{"workspace_id":"w6","label":"Terminal"},{"workspace_id":"w9","label":"Cable"}],
            "panes":[
                {"workspace_id":"w6","pane_id":"w6:pA","cwd":"/projects/terminal"},
                {"workspace_id":"w9","pane_id":"w9:pA","cwd":"/projects/cable/worktree"},
                {"workspace_id":"w9","pane_id":"w9:pB","cwd":"/old","foreground_cwd":"/projects/cable/worktree"},
                {"workspace_id":"w9","pane_id":"w9:pC","cwd":"relative"}
            ]}}}""".toByteArray(), 0)
        val locations = parseHerdrProjectLocations(output)
        assertEquals(listOf("/projects/cable/worktree", "/projects/terminal"), locations.map { it.path })
        assertTrue(locations.first().current)
        assertEquals("Cable", locations.first().label)
        assertFalse(locations.last().current)
    }

    @Test fun missingFocusDoesNotGuessTheFirstProjectAndBadResponsesFailClosed() {
        val json = """{"result":{"snapshot":{"panes":[{"workspace_id":"w1","pane_id":"w1:p1","cwd":"/repo"}]}}}"""
        assertFalse(parseHerdrProjectLocations(TmuxExecOutput(json.toByteArray(), 0)).single().current)
        assertTrue(parseHerdrProjectLocations(TmuxExecOutput("bad".toByteArray(), 0)).isEmpty())
        assertTrue(parseHerdrProjectLocations(TmuxExecOutput(json.toByteArray(), 1)).isEmpty())
        assertTrue(parseHerdrProjectLocations(TmuxExecOutput(ByteArray(1024 * 1024 + 1), 0)).isEmpty())
    }

    @Test fun queryUsesTheSelectedSessionAndOnlyReadsAnAtomicSnapshot() {
        var command = ""
        captureHerdrProjectLocations(TmuxCommandRunner { value ->
            command = value
            TmuxExecOutput("{}".toByteArray(), 0)
        }, HerdrStartupChoice("/opt/herdr bin", "work"))
        assertEquals("'/opt/herdr bin' '--session' 'work' 'api' 'snapshot'", command)
    }
}
