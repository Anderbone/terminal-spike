package com.yanjiyu.terminalspike.connection

import org.junit.Assert.*
import org.junit.Test

class HerdrRecentProjectsTest {
    private val choice = HerdrStartupChoice("/opt/herdr bin", "selected session")
    private fun output(text: String, status: Int = 0) = TmuxExecOutput(text.toByteArray(), status)
    private val snapshot = """{"result":{"snapshot":{"panes":[
        {"cwd":"/projects/open", "foreground_cwd":"/projects/current"},
        {"cwd":"/projects/second/"}
    ]}}}"""

    @Test fun filtersEveryOpenPaneDirectoryAndSortsUnknownDatesDeterministically() {
        val open = herdrOpenProjectPaths(output(snapshot))
        val recent = output("/projects/open\n/projects/new two\n/projects/current/\n/projects/new-one\n/projects/second\n/projects/new two/\nrelative\n/bad\u0000name\n")
        assertEquals(listOf(RecentHerdrProject("/projects/new two"), RecentHerdrProject("/projects/new-one")), filterHerdrRecentProjects(recent, open))
    }

    @Test fun failedSnapshotDoesNotShowAlreadyOpenProjectsAsCandidates() {
        var calls = 0
        val runner = TmuxCommandRunner { calls++; output("{}", 1) }
        assertNull(recentHerdrProjects(runner, choice))
        assertEquals(1, calls)
        assertNull(recentHerdrProjects(TmuxCommandRunner { output("{}") }, choice))
    }

    @Test fun usesSelectedSessionAndDistinguishesMissingZoxideFromEmptyHistory() {
        val commands = mutableListOf<String>()
        val runner = TmuxCommandRunner {
            commands += it
            if (commands.size == 1) output(snapshot) else output("")
        }
        assertEquals(emptyList<RecentHerdrProject>(), recentHerdrProjects(runner, choice))
        assertEquals("'/opt/herdr bin' '--session' 'selected session' 'api' 'snapshot'", commands.first())
        assertTrue(commands.last().contains("query --list"))
        var calls = 0
        assertNull(recentHerdrProjects(TmuxCommandRunner {
            if (++calls == 1) output(snapshot) else output("", 127)
        }, choice))
    }

    @Test fun createRechecksOpenProjectsAndQuotesShellMetacharacters() {
        val commands = mutableListOf<String>()
        val runner = TmuxCommandRunner {
            commands += it
            if (commands.size == 1) output(snapshot) else output("{}")
        }
        val path = "/projects/a'\$(touch nope); b"
        assertTrue(openHerdrProject(runner, choice, path))
        val create = listOf(choice.executable, "--session", choice.sessionName, "workspace", "create", "--cwd", path, "--focus")
            .joinToString(" ", transform = ::quotePosixShellArgument)
        // The exact quoted create happens once, before recording a successful visit.
        val script = commands.last()
        assertTrue(script.startsWith("/bin/sh -c "))
        assertTrue(script.contains(quotePosixShellArgument(create).removeSurrounding("'")))
        assertTrue(script.contains(" add -- "))
        assertFalse(script.contains("&& exec"))
        commands.clear()
        assertFalse(openHerdrProject(runner, choice, "/projects/open"))
        assertEquals(1, commands.size)
        commands.clear()
        assertFalse(openHerdrProject(runner, choice, "relative"))
        assertTrue(commands.isEmpty())
    }

    @Test fun rejectsFailedCreateAndBoundsTheProjectList() {
        var calls = 0
        assertFalse(openHerdrProject(TmuxCommandRunner {
            if (++calls == 1) output(snapshot) else output("failed", 1)
        }, choice, "/missing"))
        assertEquals(256, filterHerdrRecentProjects(output((1..300).joinToString("\n") { "/projects/$it" }), emptySet()).size)
    }
}
