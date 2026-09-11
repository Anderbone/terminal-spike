package com.yanjiyu.terminalspike.connection

import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class HerdrSessionsTest {
    @Test
    fun parsesOnlyRunningSessionsWithDefaultFirstAndOpaqueNames() {
        val result = parseHerdrSessionCatalog(output("""{"sessions":[
            {"name":"work","running":true,"socket_path":"ignored"},
            {"name":"stopped","running":false},
            {"name":"default","running":true,"default":true},
            {"name":"work","running":true},
            {"name":"missing-running"},
            {"name":"--remote","running":true},
            {"name":"../escape","running":true},
            {"name":"bad\nname","running":true}
        ]}"""))
        assertEquals(HerdrAvailability.AVAILABLE, result.availability)
        assertEquals(listOf(HerdrSession("default", true), HerdrSession("work")), result.sessions)
        assertEquals("herdr:default", result.sessions.first().selectionId)
    }

    @Test
    fun failedMissingMalformedAndOversizedResponsesDoNotBlockShellOrTmux() {
        assertEquals(HerdrAvailability.NOT_INSTALLED, parseHerdrSessionCatalog(TmuxExecOutput(byteArrayOf(), 127)).availability)
        for (value in listOf(
            output("not json"), output("{}"), output("[]"),
            output("""{"sessions":{}}"""), output("x".repeat(65_537)),
            TmuxExecOutput(output("""{"sessions":[]}""").stdout, 1),
            TmuxExecOutput("__TERMINAL_SPIKE_HERDR__\nrelative\n{\"sessions\":[]}".toByteArray(), 0),
        )) {
            assertEquals(HerdrAvailability.CHECK_FAILED, parseHerdrSessionCatalog(value).availability)
        }
        val empty = parseHerdrSessionCatalog(output("""{"sessions":[]}"""))
        assertEquals(HerdrAvailability.AVAILABLE, empty.availability)
        assertTrue(empty.sessions.isEmpty())
        val entries = (1..140).joinToString(",") { """{"name":"s$it","running":true}""" }
        assertEquals(MAX_HERDR_SESSIONS, parseHerdrSessionCatalog(output("{\"sessions\":[$entries]}")).sessions.size)
    }

    @Test
    fun chooserSelectsKnownHerdrWhenTmuxIsMissingAndIgnoresStaleOrUnknownAnswers() {
        val prompts = ArrayBlockingQueue<TmuxSessionPrompt>(4)
        val selected = AtomicReference<StartupSessionChoice?>()
        val selector = TmuxSessionSelector(
            commandRunner = { command ->
                if (command == HERDR_LIST_COMMAND) output("""{"sessions":[{"name":"default","default":true,"running":true}]}""")
                else TmuxExecOutput(byteArrayOf(), 0)
            },
            onPrompt = prompts::put,
        )
        val worker = thread(isDaemon = true) { selected.set(selector.awaitChoice()) }
        try {
            val prompt = requireNotNull(prompts.poll(2, TimeUnit.SECONDS))
            assertEquals(TmuxAvailability.NOT_INSTALLED, prompt.availability)
            assertEquals(listOf(HerdrSession("default", true)), prompt.herdrSessions)
            selector.answer(prompt.promptToken + 1, "herdr:default")
            selector.answer(prompt.promptToken, "herdr:unknown")
            selector.answer(prompt.promptToken, "herdr:default")
            worker.join(2_000)
            assertFalse(worker.isAlive)
            val choice = requireNotNull(selected.get())
            assertEquals(HerdrStartupChoice("/usr/bin/herdr", "default"), choice)
            assertFalse(choice is TmuxStartupChoice)
            assertEquals("'/usr/bin/herdr' 'session' 'attach' 'default'", startupSessionCommand(choice))
            assertEquals(startupSessionCommand(choice), resolveSshStartupCommand("saved-command", startupSessionCommand(choice)))
        } finally {
            selector.cancel()
            worker.join(2_000)
        }
    }

    @Test
    fun failedHerdrDiscoveryStillAllowsOrdinaryShellSelection() {
        lateinit var selector: TmuxSessionSelector
        selector = TmuxSessionSelector(
            commandRunner = { throw IllegalStateException("unavailable") },
            onPrompt = { prompt ->
                assertEquals(HerdrAvailability.CHECK_FAILED, prompt.herdrAvailability)
                assertTrue(prompt.herdrSessions.isEmpty())
                selector.answer(prompt.promptToken, null)
            },
        )
        assertEquals(null, selector.awaitChoice())
    }

    @Test
    fun probeUsesTheAvailableExecutableAndOnlyListsSessions() {
        val directory = Files.createTempDirectory("herdr-probe-")
        val executable = directory.resolve("herdr").toFile()
        try {
            executable.writeText("""#!/bin/sh
                [ "${'$'}#" -eq 3 ] && [ "${'$'}1" = session ] && [ "${'$'}2" = list ] && [ "${'$'}3" = --json ] || exit 1
                printf '%s\n' '{"sessions":[{"name":"work","running":true}]}'
            """.trimIndent())
            assertTrue(executable.setExecutable(true))
            val process = ProcessBuilder("/bin/sh", "-c", HERDR_LIST_COMMAND).apply {
                environment()["PATH"] = directory.toString()
            }.start()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            val catalog = parseHerdrSessionCatalog(TmuxExecOutput(process.inputStream.readBytes(), process.exitValue()))
            assertEquals(executable.absolutePath, catalog.executable)
            assertEquals(listOf(HerdrSession("work")), catalog.sessions)
        } finally {
            executable.delete()
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun attachCommandTreatsSpacesQuotesAndShellMetacharactersAsOneName() {
        val name = "team's space; echo harmless"
        val choice = HerdrStartupChoice("/opt/my tools/herdr", name)
        assertEquals("'/opt/my tools/herdr' 'session' 'attach' 'team'\\''s space; echo harmless'", choice.command())
        val process = ProcessBuilder("/bin/sh", "-c", "set -- ${quotePosixShellArgument(name)}; printf '%s' \"${'$'}1\"").start()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(name, process.inputStream.bufferedReader().readText())
        assertThrows(IllegalArgumentException::class.java) { HerdrStartupChoice("herdr", "work") }
        assertThrows(IllegalArgumentException::class.java) { HerdrStartupChoice("/usr/bin/herdr", "--remote") }
    }

    private fun output(json: String) = TmuxExecOutput(
        "banner\n__TERMINAL_SPIKE_HERDR__\n/usr/bin/herdr\n$json\n".toByteArray(), 0,
    )
}
