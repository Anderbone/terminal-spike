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
    private fun coherentRunner(
        metadata: String,
        afterMetadata: String = metadata,
        capture: (String) -> TmuxExecOutput,
    ) = TmuxCommandRunner { command ->
        val marker = Regex("__TS_CAPTURE_[0-9a-f]{32}__").find(command)?.value
            ?: return@TmuxCommandRunner capture(command)
        val commands = command.split(" \\; ").filter { it.startsWith("capture-pane ") }
        val content = commands.joinToString("") {
            val result = capture("'/usr/bin/tmux' $it")
            check(result.exitStatus == 0)
            result.stdout.toString(Charsets.UTF_8).let { text ->
                if (text.isEmpty() || text.endsWith('\n')) text else "$text\n"
            }
        }
        TmuxExecOutput((marker + metadata + "\n" + content + marker + afterMetadata + "\n").toByteArray(), 0)
    }

    @Test
    fun knownClientTtyWinsOverAnotherClientsMoreRecentActivity() {
        val commands = mutableListOf<String>()
        val runner = TmuxCommandRunner { command ->
            commands += command
            when {
                command == TMUX_LIST_COMMAND -> TmuxExecOutput(
                    "__TERMINAL_SPIKE_TMUX__\n/usr/bin/tmux\n$7|source|1|2|10\n$8|target|1|0|11\n".toByteArray(), 0,
                )
                " list-clients " in command -> TmuxExecOutput(
                    "/dev/pts/8|$7|100\n/dev/pts/9|$7|999\n".toByteArray(), 0,
                )
                else -> TmuxExecOutput(byteArrayOf(), 0)
            }
        }
        assertEquals(TmuxSessionSwitchResult.Switched,
            switchOrAttachTmuxSession(runner, "$8", "$7", clientTty = "/dev/pts/8"))
        assertTrue(commands.last().contains("switch-client -c '/dev/pts/8'"))
    }

    @Test
    fun historyChangingAfterTheOuterProbeUsesAtomicCaptureCoordinates() {
        for (changed in listOf("$7|%9|80|24|5001|0|0|0", "$7|%9|80|24|231|0|0|0",
            "$7|%9|81|24|5000|0|0|0")) {
            var historyRead = false
            val capture = captureTmuxPane(
                metadataRunner = { TmuxExecOutput("$7|%9|80|24|5000|0|0|0".toByteArray(), 0) },
                historyRunner = coherentRunner(changed) {
                    historyRead = true
                    TmuxExecOutput("changed history\n".toByteArray(), 0)
                },
                executable = "/usr/bin/tmux", sessionId = "$7",
            )
            assertTrue(historyRead)
            requireNotNull(capture)
            val fields = changed.split('|')
            assertEquals(fields[2].toInt(), capture.columns)
            assertEquals(fields[4].toInt(), capture.remoteHistoryRows)
            assertEquals((fields[4].toInt() - TMUX_HISTORY_PAGE_ROWS).coerceAtLeast(0), capture.capturedStartRow)
        }
    }

    @Test
    fun inconsistentAtomicCaptureAndOlderPageCoordinatesAreRejected() {
        val metadata = "$7|%9|80|24|5000|0|0|0"
        val changed = "$7|%9|80|24|5001|0|0|0"
        assertNull(captureTmuxPane(
            metadataRunner = { TmuxExecOutput(metadata.toByteArray(), 0) },
            historyRunner = coherentRunner(metadata, afterMetadata = changed) {
                TmuxExecOutput("rows\n".toByteArray(), 0)
            },
            executable = "/usr/bin/tmux", sessionId = "$7",
        ))
        assertNull(captureTmuxPane(
            metadataRunner = { TmuxExecOutput(metadata.toByteArray(), 0) },
            historyRunner = coherentRunner(changed) { TmuxExecOutput("rows\n".toByteArray(), 0) },
            executable = "/usr/bin/tmux", sessionId = "$7",
            pageRequest = TmuxHistoryPageRequest("%9", beforeRow = 904, remoteHistoryRows = 5000),
        ))
    }

    @Test
    fun paneHistoryCaptureJoinsOnlyTmuxMarkedWrapsSoTheParserCanRestoreThem() {
        val metadataCommands = mutableListOf<String>()
        val historyCommands = mutableListOf<String>()
        val capture = captureTmuxPane(
            metadataRunner = { command ->
                metadataCommands += command
                TmuxExecOutput("\$7|%9|80|24|3|0|0|0\n".encodeToByteArray(), 0)
            },
            historyRunner = coherentRunner("\$7|%9|80|24|3|0|0|0") { command ->
                historyCommands += command
                TmuxExecOutput("one\ntwo\nthree\n".encodeToByteArray(), 0)
            },
            executable = "/usr/bin/tmux",
            sessionId = "\$7",
            authoritative = true,
        )

        requireNotNull(capture)
        assertEquals(3, capture.historyRows)
        assertEquals("%9", capture.paneId)
        assertTrue(capture.authoritative)
        assertTrue(metadataCommands.single().contains("display-message -p -t '\$7'"))
        assertEquals(
            "'/usr/bin/tmux' capture-pane -p -e -J -t '%9' -S '-4096' -E -1",
            historyCommands.single(),
        )
    }

    @Test
    fun paneAlternateApplicationWithoutMouseTrackingKeepsHistoryLocal() {
        val historyCommands = mutableListOf<String>()
        val capture = captureTmuxPane(
            metadataRunner = {
                TmuxExecOutput("\$7|%9|80|1|1|1|0|0\n".encodeToByteArray(), 0)
            },
            historyRunner = coherentRunner("\$7|%9|80|1|1|1|0|0") { command ->
                historyCommands += command
                when {
                    " -a " in command -> TmuxExecOutput("saved primary\n".encodeToByteArray(), 0)
                    else -> TmuxExecOutput("history\n".encodeToByteArray(), 0)
                }
            },
            executable = "/usr/bin/tmux",
            sessionId = "\$7",
        )

        requireNotNull(capture)
        assertTrue(capture.alternateScreenActive)
        assertFalse(capture.mouseTrackingActive)
        assertEquals(2, capture.historyRows)
        assertEquals("history\nsaved primary\n", capture.content.toString(Charsets.UTF_8))
        assertEquals(2, historyCommands.size)
        assertTrue(historyCommands.last().contains("capture-pane -p -e -J -a -t '%9'"))
    }

    @Test
    fun paneApplicationMouseTrackingStillCapturesPersistentHistoryForLocalViewport() {
        var historyRan = false
        val capture = captureTmuxPane(
            metadataRunner = {
                TmuxExecOutput("\$7|%9|80|24|300|0|1|0\n".encodeToByteArray(), 0)
            },
            historyRunner = coherentRunner("\$7|%9|80|24|300|0|1|0") {
                historyRan = true
                TmuxExecOutput("history\n".encodeToByteArray(), 0)
            },
            executable = "/usr/bin/tmux",
            sessionId = "\$7",
        )

        requireNotNull(capture)
        assertFalse(capture.alternateScreenActive)
        assertTrue(capture.mouseTrackingActive)
        assertEquals(300, capture.historyRows)
        assertEquals("history\n", capture.content.toString(Charsets.UTF_8))
        assertTrue(historyRan)
    }

    @Test
    fun paneAlternateApplicationWithMouseTrackingAlsoCapturesSavedPrimaryRows() {
        val historyCommands = mutableListOf<String>()
        val capture = captureTmuxPane(
            metadataRunner = {
                TmuxExecOutput("\$7|%9|80|1|1|1|1|0\n".encodeToByteArray(), 0)
            },
            historyRunner = coherentRunner("\$7|%9|80|1|1|1|1|0") { command ->
                historyCommands += command
                if (" -a " in command) {
                    TmuxExecOutput("saved primary\n".encodeToByteArray(), 0)
                } else {
                    TmuxExecOutput("history\n".encodeToByteArray(), 0)
                }
            },
            executable = "/usr/bin/tmux",
            sessionId = "\$7",
        )

        requireNotNull(capture)
        assertTrue(capture.alternateScreenActive)
        assertTrue(capture.mouseTrackingActive)
        assertEquals(2, capture.historyRows)
        assertEquals("history\nsaved primary\n", capture.content.toString(Charsets.UTF_8))
        assertEquals(2, historyCommands.size)
        assertTrue(historyCommands.last().contains("capture-pane -p -e -J -a -t '%9'"))
    }

    @Test
    fun lightweightPaneProbeDoesNotRecaptureExistingSafeHistory() {
        var historyRan = false
        val capture = captureTmuxPane(
            metadataRunner = {
                TmuxExecOutput("\$7|%9|80|24|5000|0|0|0\n".encodeToByteArray(), 0)
            },
            historyRunner = coherentRunner("\$7|%9|80|24|5000|0|0|0") {
                historyRan = true
                TmuxExecOutput(byteArrayOf(), 0)
            },
            executable = "/usr/bin/tmux",
            sessionId = "\$7",
            includeHistory = false,
        )

        requireNotNull(capture)
        assertFalse(capture.historyIncluded)
        assertEquals(5_000, capture.historyRows)
        assertTrue(capture.content.isEmpty())
        assertFalse(historyRan)
    }

    @Test
    fun largeHistoryStartsWithBoundedNewestPageAndExposesOlderCoordinates() {
        val historyCommands = mutableListOf<String>()
        val capture = captureTmuxPane(
            metadataRunner = {
                TmuxExecOutput("\$7|%9|80|24|5000|0|0|0\n".encodeToByteArray(), 0)
            },
            historyRunner = coherentRunner("\$7|%9|80|24|5000|0|0|0") { command ->
                historyCommands += command
                TmuxExecOutput(ByteArray(1), 0)
            },
            executable = "/usr/bin/tmux",
            sessionId = "\$7",
        )

        requireNotNull(capture)
        assertEquals(TMUX_HISTORY_PAGE_ROWS, capture.historyRows)
        assertEquals(5_000, capture.remoteHistoryRows)
        assertEquals(904, capture.capturedStartRow)
        assertEquals(0, capture.oldestAvailableRow)
        assertTrue(capture.truncatedBefore)
        assertEquals(
            "'/usr/bin/tmux' capture-pane -p -e -J -t '%9' -S '-4096' -E -1",
            historyCommands.single(),
        )
    }

    @Test
    fun olderPageUsesDisjointStableRangeAndRejectsChangedRemoteHistory() {
        val historyCommands = mutableListOf<String>()
        val request = TmuxHistoryPageRequest("%9", beforeRow = 904, remoteHistoryRows = 5_000)
        val capture = captureTmuxPane(
            metadataRunner = {
                TmuxExecOutput("\$7|%9|80|24|5000|0|0|0\n".encodeToByteArray(), 0)
            },
            historyRunner = coherentRunner("\$7|%9|80|24|5000|0|0|0") { command ->
                historyCommands += command
                TmuxExecOutput(ByteArray(1), 0)
            },
            executable = "/usr/bin/tmux",
            sessionId = "\$7",
            pageRequest = request,
        )

        requireNotNull(capture)
        assertTrue(capture.olderPage)
        assertEquals(905, capture.historyRows)
        assertEquals(0, capture.capturedStartRow)
        assertEquals(
            "'/usr/bin/tmux' capture-pane -p -e -J -t '%9' -S '-5000' -E '-4096'",
            historyCommands.single(),
        )

        assertNull(
            captureTmuxPane(
                metadataRunner = {
                    TmuxExecOutput("\$7|%9|80|24|5001|0|0|0\n".encodeToByteArray(), 0)
                },
                historyRunner = coherentRunner("\$7|%9|80|24|5000|0|0|0") { error("stale page must not transfer history") },
                executable = "/usr/bin/tmux",
                sessionId = "\$7",
                pageRequest = request,
            ),
        )
    }

    @Test
    fun activeSessionSwitchUsesSideChannelAndTargetsItsMostRecentClient() {
        val commands = mutableListOf<String>()
        val runner = TmuxCommandRunner { command ->
            commands += command
            when {
                command.startsWith("/bin/sh -c ") -> TmuxExecOutput(
                    (
                        "__TERMINAL_SPIKE_TMUX__\n/usr/bin/tmux\n" +
                            "\$7|old|1|2|1\n\$8|work|2|1|2\n"
                    ).encodeToByteArray(),
                    0,
                )
                command.contains(" list-clients ") -> TmuxExecOutput(
                    "/dev/pts/4|\$7|100\n/dev/pts/9|\$7|200\n/dev/pts/2|\$8|300\n"
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
                            "\$7|work|1|0|1\n"
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
                            "\$7|work|1|0|1\n"
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
                            "\$7|old|1|0|1\n\$8|work|2|1|2\n"
                    ).encodeToByteArray(),
                    0,
                )
                command.contains(" list-clients ") -> TmuxExecOutput(
                    "/dev/pts/2|\$8|300\n".encodeToByteArray(),
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
                            "\$7|work|1|1|1\n"
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
                        if (!deleted) append("\$3|work|2|1|1725000000\n")
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
                command == TMUX_LIST_COMMAND -> {
                    val sessions = if (listCount.getAndIncrement() == 0) {
                        "\$1|one|1|0|1\n\$2|two|2|1|2\n"
                    } else {
                        "\$2|two|2|1|2\n"
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
        val selected = AtomicReference<StartupSessionChoice?>()
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
        val selected = AtomicReference<StartupSessionChoice?>()
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
            startupSessionCommand(requireNotNull(selected.get())),
        )
    }

    @Test
    fun newSessionResolutionDoesNotGuessAnExistingAttachedSessionBeforeCreation() {
        val existingSessionIds = setOf("\$1", "\$42")
        val runner = TmuxCommandRunner { command ->
            assertEquals(TMUX_LIST_COMMAND, command)
            TmuxExecOutput(
                (
                    "__TERMINAL_SPIKE_TMUX__\n/usr/bin/tmux\n" +
                        "\$1|dev|1|1|10\n" +
                        "\$42|download|1|2|20\n"
                    ).encodeToByteArray(),
                0,
            )
        }

        assertNull(resolveNewTmuxSessionId(runner, existingSessionIds))
    }

    @Test
    fun newSessionResolutionAcceptsOnlyOneUnambiguousNewSessionId() {
        fun runnerWith(vararg sessionLines: String) = TmuxCommandRunner { command ->
            assertEquals(TMUX_LIST_COMMAND, command)
            TmuxExecOutput(
                (
                    "__TERMINAL_SPIKE_TMUX__\n/usr/bin/tmux\n" +
                        sessionLines.joinToString(separator = "\n", postfix = "\n")
                    ).encodeToByteArray(),
                0,
            )
        }

        assertEquals(
            "\$45",
            resolveNewTmuxSessionId(
                runnerWith(
                    "\$1|dev|1|1|10",
                    "\$42|download|1|2|20",
                    "\$45|45|1|1|30",
                ),
                existingSessionIds = setOf("\$1", "\$42"),
            ),
        )
        assertNull(
            resolveNewTmuxSessionId(
                runnerWith(
                    "\$1|dev|1|1|10",
                    "\$45|45|1|1|30",
                    "\$46|46|1|1|31",
                ),
                existingSessionIds = setOf("\$1"),
            ),
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
            parseTmuxSessionLine("\$12|build server|4|2|1725000000"),
        )
        assertEquals("build|server", parseTmuxSessionLine("\$12|build|server|4|2|1725000000")?.name)
    }

    @Test
    fun ignoresShellBannerOutputBeforeTheAvailabilityMarker() {
        val sessions = queryTmuxSessions {
            TmuxExecOutput(
                (
                        "Welcome to the server\n" +
                            "__TERMINAL_SPIKE_TMUX__\n" +
                            "/opt/homebrew/bin/tmux\n" +
                            "\$4|work|3|1|1725000000\n"
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
        assertTrue(requireNotNull(command.get()).contains("#{session_id}|#{session_name}"))
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
                "#!/bin/sh\nprintf '\$4|fish work|2|0|1725000000\\n'\n",
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
            assertTrue(output.contains("\$4|fish work|2|0|1725000000"))
        } finally {
            Files.walk(fakeBin).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun rejectsMalformedOrUnsafeSessionRecords() {
        assertNull(parseTmuxSessionLine("work|2|0|1725000000"))
        assertNull(parseTmuxSessionLine("\$x|work|2|0|1725000000"))
        assertNull(parseTmuxSessionLine("\$1|work|-1|0|1725000000"))
        assertNull(parseTmuxSessionLine("\$1|\u0000|2|0|1725000000"))
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
