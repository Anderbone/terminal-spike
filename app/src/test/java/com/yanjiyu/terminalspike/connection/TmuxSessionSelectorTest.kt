package com.yanjiyu.terminalspike.connection

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxSessionSelectorTest {
    @Test
    fun activeSessionSwitchUsesSideChannelAndTargetsItsMostRecentClient() {
        val commands = mutableListOf<String>()
        val runner = TmuxCommandRunner { command ->
            commands += command
            when {
                command.startsWith("/bin/sh -c ") -> TmuxExecOutput(
                    (
                        "__TERMINAL_SPIKE_TMUX__\n/usr/bin/tmux\n" +
                            "\$7\told\t1\t2\t1\n\$8\twork\t2\t1\t2\n"
                    ).encodeToByteArray(),
                    0,
                )
                command.contains(" list-clients ") -> TmuxExecOutput(
                    "/dev/pts/4\t\$7\t100\n/dev/pts/9\t\$7\t200\n/dev/pts/2\t\$8\t300\n"
                        .encodeToByteArray(),
                    0,
                )
                command == "'/usr/bin/tmux' switch-client -c '/dev/pts/9' -t '\$8'" ->
                    TmuxExecOutput(byteArrayOf(), 0)
                else -> error("Unexpected command: $command")
            }
        }

        assertTrue(switchTmuxSession(runner, "\$8", "\$7"))
        assertTrue(commands.last().contains("switch-client -c '/dev/pts/9' -t '\$8'"))
    }

    @Test
    fun activeSessionSwitchRejectsInvalidTargetWithoutRunningCommands() {
        var ran = false
        assertFalse(
            switchTmuxSession(
                commandRunner = { ran = true; TmuxExecOutput(byteArrayOf(), 0) },
                targetSessionId = "\$8; reboot",
                sourceSessionId = "\$7",
            ),
        )
        assertFalse(ran)
    }

    @Test
    fun plainShellSwitchReturnsValidatedAttachCommandForTheInteractiveTerminal() {
        val runner = TmuxCommandRunner { command ->
            when {
                command.startsWith("/bin/sh -c ") -> TmuxExecOutput(
                    (
                        "__TERMINAL_SPIKE_TMUX__\n/usr/local/bin/tmux\n" +
                            "\$7\twork\t1\t0\t1\n"
                    ).encodeToByteArray(),
                    0,
                )
                else -> error("Unexpected command: $command")
            }
        }

        assertEquals(
            TmuxSessionSwitchResult.Attach(
                "'/usr/local/bin/tmux' attach-session -t '\$7'",
            ),
            switchOrAttachTmuxSession(runner, "\$7", sourceSessionId = null),
        )
    }

    @Test
    fun plainShellSwitchRejectsASessionMissingFromTheFreshCatalogue() {
        val runner = TmuxCommandRunner { command ->
            when {
                command.startsWith("/bin/sh -c ") -> TmuxExecOutput(
                    (
                        "__TERMINAL_SPIKE_TMUX__\n/usr/bin/tmux\n" +
                            "\$7\twork\t1\t0\t1\n"
                    ).encodeToByteArray(),
                    0,
                )
                else -> error("Unexpected command: $command")
            }
        }

        assertEquals(
            TmuxSessionSwitchResult.Failed,
            switchOrAttachTmuxSession(runner, "\$8", sourceSessionId = null),
        )
    }

    @Test
    fun activeSessionSwitchNeverFallsBackToAnotherTmuxClient() {
        val commands = mutableListOf<String>()
        val runner = TmuxCommandRunner { command ->
            commands += command
            when {
                command.startsWith("/bin/sh -c ") -> TmuxExecOutput(
                    (
                        "__TERMINAL_SPIKE_TMUX__\n/usr/bin/tmux\n" +
                            "\$7\told\t1\t0\t1\n\$8\twork\t2\t1\t2\n"
                    ).encodeToByteArray(),
                    0,
                )
                command.contains(" list-clients ") -> TmuxExecOutput(
                    "/dev/pts/2\t\$8\t300\n".encodeToByteArray(),
                    0,
                )
                else -> error("Unexpected command: $command")
            }
        }

        assertFalse(switchTmuxSession(runner, "\$8", "\$7"))
        assertFalse(commands.any { it.contains(" switch-client ") })
    }

    @Test
    fun activeCatalogueIncludesBoundedCapturePanePreview() {
        val runner = TmuxCommandRunner { command ->
            when {
                command.startsWith("/bin/sh -c ") -> TmuxExecOutput(
                    (
                        "__TERMINAL_SPIKE_TMUX__\n/usr/bin/tmux\n" +
                            "\$7\twork\t1\t1\t1\n"
                    ).encodeToByteArray(),
                    0,
                )
                command == "'/usr/bin/tmux' capture-pane -p -J -t '\$7' -S -10" ->
                    TmuxExecOutput("prompt> ./gradlew test\nBUILD SUCCESSFUL\n".encodeToByteArray(), 0)
                else -> error("Unexpected command: $command")
            }
        }

        assertEquals(
            listOf("prompt> ./gradlew test", "BUILD SUCCESSFUL"),
            queryTmuxSessionCatalog(runner, includePreviews = true).sessions.single().previewLines,
        )
    }

    @Test
    fun activeCatalogueDeletionUsesTheValidatedExecutableAndRefreshes() {
        var deleted = false
        val runner = TmuxCommandRunner { command ->
            when {
                command.startsWith("/bin/sh -c ") -> TmuxExecOutput(
                    buildString {
                        append("__TERMINAL_SPIKE_TMUX__\n/usr/bin/tmux\n")
                        if (!deleted) append("\$3\twork\t2\t1\t1725000000\n")
                    }.encodeToByteArray(),
                    0,
                )
                command == "'/usr/bin/tmux' kill-session -t '\$3'" -> {
                    deleted = true
                    TmuxExecOutput(byteArrayOf(), 0)
                }
                else -> error("Unexpected command: $command")
            }
        }

        assertEquals(listOf("\$3"), queryTmuxSessionCatalog(runner).sessions.map(TmuxSession::id))
        val refreshed = terminateTmuxSession(runner, "\$3")

        assertFalse(refreshed.deleteFailed)
        assertTrue(refreshed.sessions.isEmpty())
    }

    @Test
    fun deleteRefreshesTheChooserBeforeSelection() {
        val listCount = java.util.concurrent.atomic.AtomicInteger(0)
        val runner = TmuxCommandRunner { command ->
            when {
                command.startsWith("/bin/sh -c ") -> {
                    val sessions = if (listCount.getAndIncrement() == 0) {
                        "\$1\tone\t1\t0\t1\n\$2\ttwo\t2\t1\t2\n"
                    } else {
                        "\$2\ttwo\t2\t1\t2\n"
                    }
                    TmuxExecOutput(
                        ("__TERMINAL_SPIKE_TMUX__\n/usr/bin/tmux\n" + sessions).encodeToByteArray(),
                        0,
                    )
                }
                command == "'/usr/bin/tmux' kill-session -t '\$1'" ->
                    TmuxExecOutput(byteArrayOf(), 0)
                else -> error("Unexpected command: $command")
            }
        }
        val prompts = ArrayBlockingQueue<TmuxSessionPrompt>(2)
        val selected = AtomicReference<TmuxStartupChoice?>()
        val selector = TmuxSessionSelector(runner, prompts::put)
        val worker = thread(isDaemon = true) { selected.set(selector.awaitChoice()) }

        val initial = requireNotNull(prompts.poll(2, TimeUnit.SECONDS))
        assertEquals(listOf("\$1", "\$2"), initial.sessions.map(TmuxSession::id))
        selector.delete(initial.promptToken, "\$1")
        val refreshed = requireNotNull(prompts.poll(2, TimeUnit.SECONDS))
        assertEquals(listOf("\$2"), refreshed.sessions.map(TmuxSession::id))
        selector.answer(refreshed.promptToken, "\$2")

        worker.join(2_000)
        assertTrue(!worker.isAlive)
        assertEquals(TmuxStartupChoice.Attach("/usr/bin/tmux", "\$2"), selected.get())
    }

    @Test
    fun installedTmuxCanStartANewProtectedSession() {
        val prompts = ArrayBlockingQueue<TmuxSessionPrompt>(1)
        val selected = AtomicReference<TmuxStartupChoice?>()
        val selector = TmuxSessionSelector(
            commandRunner = {
                TmuxExecOutput(
                    "__TERMINAL_SPIKE_TMUX__\n/usr/local/bin/tmux\n".encodeToByteArray(),
                    0,
                )
            },
            onPrompt = prompts::put,
        )
        val worker = thread(isDaemon = true) { selected.set(selector.awaitChoice()) }

        val prompt = requireNotNull(prompts.poll(2, TimeUnit.SECONDS))
        assertEquals(TmuxAvailability.AVAILABLE, prompt.availability)
        selector.answer(prompt.promptToken, TMUX_NEW_SESSION_SELECTION)

        worker.join(2_000)
        assertTrue(!worker.isAlive)
        assertEquals(TmuxStartupChoice.NewSession("/usr/local/bin/tmux"), selected.get())
        assertEquals(
            "'/usr/local/bin/tmux' new-session",
            tmuxStartupCommand(requireNotNull(selected.get())),
        )
    }

    @Test
    fun missingTmuxStillShowsAnExplanatoryChooser() {
        val prompts = ArrayBlockingQueue<TmuxSessionPrompt>(1)
        val selector = TmuxSessionSelector(
            commandRunner = { TmuxExecOutput(byteArrayOf(), 0) },
            onPrompt = prompts::put,
        )
        val worker = thread(isDaemon = true) { selector.awaitChoice() }

        val prompt = requireNotNull(prompts.poll(2, TimeUnit.SECONDS))
        assertEquals(TmuxAvailability.NOT_INSTALLED, prompt.availability)
        assertTrue(prompt.sessions.isEmpty())
        selector.answer(prompt.promptToken, null)

        worker.join(2_000)
        assertTrue(!worker.isAlive)
    }

    @Test
    fun parsesBoundedTmuxMetadata() {
        assertEquals(
            TmuxSession(
                id = "\$12",
                name = "build server",
                windowCount = 4,
                attachedClientCount = 2,
                createdAtEpochSeconds = 1_725_000_000L,
            ),
            parseTmuxSessionLine("\$12\tbuild server\t4\t2\t1725000000"),
        )
    }

    @Test
    fun ignoresShellBannerOutputBeforeTheAvailabilityMarker() {
        val sessions = queryTmuxSessions {
            TmuxExecOutput(
                (
                        "Welcome to the server\n" +
                            "__TERMINAL_SPIKE_TMUX__\n" +
                            "/opt/homebrew/bin/tmux\n" +
                            "\$4\twork\t3\t1\t1725000000\n"
                    ).encodeToByteArray(),
                0,
            )
        }

        assertEquals(listOf("\$4"), sessions?.map(TmuxSession::id))
    }

    @Test
    fun probeFallsBackToTheAccountShellWhenExecPathDiffersFromInteractivePath() {
        val command = AtomicReference<String>()

        queryTmuxSessions {
            command.set(it)
            TmuxExecOutput(
                "__TERMINAL_SPIKE_TMUX__\n/home/operator/bin/tmux\n".encodeToByteArray(),
                0,
            )
        }

        assertTrue(
            requireNotNull(command.get()).contains(
                "\"\$ACCOUNT_SHELL\" -ic \"env\"",
            ),
        )
        assertTrue(
            requireNotNull(command.get()).contains(
                "\"\$ACCOUNT_SHELL\" -lic \"command -v tmux\"",
            ),
        )
        assertTrue(requireNotNull(command.get()).startsWith("/bin/sh -c '"))
        assertTrue(requireNotNull(command.get()).contains("#{session_id}\t#{session_name}"))
        assertFalse(requireNotNull(command.get()).contains("#{session_id}\\t#{session_name}"))
    }

    @Test
    fun fishLoginShellCanRunThePortableProbe() {
        val fish = Path.of("/usr/bin/fish")
        assumeTrue(Files.isExecutable(fish))
        val fakeBin = Files.createTempDirectory("terminal-spike-tmux-probe")
        try {
            val fakeTmux = fakeBin.resolve("tmux")
            Files.writeString(
                fakeTmux,
                "#!/bin/sh\nprintf '\$4\\tfish work\\t2\\t0\\t1725000000\\n'\n",
            )
            Files.setPosixFilePermissions(fakeTmux, PosixFilePermissions.fromString("rwx------"))
            val process = ProcessBuilder(fish.toString(), "-c", TMUX_LIST_COMMAND)
                .redirectErrorStream(true)
                .apply { environment()["PATH"] = fakeBin.toString() }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertTrue(output.contains("__TERMINAL_SPIKE_TMUX__\n${fakeTmux}\n"))
            assertTrue(output.contains("\$4\tfish work\t2\t0\t1725000000"))
        } finally {
            Files.walk(fakeBin).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun rejectsMalformedOrUnsafeSessionRecords() {
        assertNull(parseTmuxSessionLine("work\t2\t0\t1725000000"))
        assertNull(parseTmuxSessionLine("\$x\twork\t2\t0\t1725000000"))
        assertNull(parseTmuxSessionLine("\$1\twork\t-1\t0\t1725000000"))
        assertNull(parseTmuxSessionLine("\$1\t\u0000\t2\t0\t1725000000"))
    }

    @Test
    fun attachTargetsOnlyValidatedOpaqueTmuxIds() {
        assertEquals("tmux attach-session -t '\$7'", tmuxAttachCommand("\$7"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun attachRejectsShellText() {
        tmuxAttachCommand("\$7; reboot")
    }

    @Test
    fun selectedAttachTakesPrecedenceOverConfiguredShellInput() {
        assertEquals(
            "tmux attach-session -t '\$3'",
            resolveSshStartupCommand("exec zsh", "tmux attach-session -t '\$3'"),
        )
        assertEquals("exec zsh", resolveSshStartupCommand("exec zsh", null))
        assertNull(resolveSshStartupCommand(null, null))
        assertEquals(
            "'/home/operator/bin/tmux' attach-session -t '\$3'",
            tmuxStartupCommand(TmuxStartupChoice.Attach("/home/operator/bin/tmux", "\$3")),
        )
    }

    @Test
    fun moshAttachIsPassedAsQuotedServerArguments() {
        val request = MoshBootstrapRequest(
            ssh = SshConnectionConfig(
                host = "host.example",
                port = 22,
                username = "operator",
                authentication = SshAuthentication.Password(byteArrayOf(1)),
            ),
        )

        assertEquals(
            "'mosh-server' 'new' '-c' '256' '-s' '-l' 'LANG=en_US.UTF-8' '--' " +
                "'tmux' 'attach-session' '-t' '\$9'",
            buildMoshServerCommand(request, "\$9"),
        )
        assertEquals(
            "'mosh-server' 'new' '-c' '256' '-s' '-l' 'LANG=en_US.UTF-8' '--' " +
                "'tmux' 'new-session'",
            buildMoshServerCommand(request, startNewTmuxSession = true),
        )
        assertEquals(
            "'mosh-server' 'new' '-c' '256' '-s' '-l' 'LANG=en_US.UTF-8' '--' " +
                "'/home/operator/bin/tmux' 'attach-session' '-t' '\$9'",
            buildMoshServerCommand(
                request = request,
                tmuxSessionId = "\$9",
                tmuxExecutable = "/home/operator/bin/tmux",
            ),
        )
    }
}
