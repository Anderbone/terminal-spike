package com.yanjiyu.terminalspike.connection

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MoshProjectSessionTest {
    private val choice = HerdrStartupChoice("/usr/bin/herdr", "selected")
    private val snapshot = """{"result":{"snapshot":{"panes":[{"cwd":"/projects/open"}]}}}"""
    private val commands = mutableListOf<String>()
    private val sessions = mutableListOf<Session>()
    private var opens = 0
    private var onOpen: (() -> Unit)? = null
    private var failCreate = false
    private var failSnapshot = false
    private val factory = object : MoshSshExecSessionFactory {
        override suspend fun open(
            config: SshConnectionConfig,
            onPrompt: (HostIdentityPrompt) -> Unit,
            onKeyboardInteractiveChallenge: (KeyboardInteractiveChallenge) -> Unit,
            onRepositoryReady: (VerifyingHostKeyRepository) -> Unit,
            registerConnectingSession: (AutoCloseable) -> Boolean,
        ): MoshSshExecSession? {
            opens++
            assertEquals("example.com", config.host)
            assertEquals(2222, config.port)
            assertEquals("user", config.username)
            assertTrue(config.portForwards.isEmpty())
            assertFalse(config.tmuxSessionSelectorEnabled)
            assertFalse(config.autoAttachSingleHerdrSession)
            assertNull(config.startupCommand)
            val session = Session().also(sessions::add)
            if (!registerConnectingSession(session)) return null
            onOpen?.invoke()
            return session
        }
    }

    @Test fun expiredBootstrapStillConfirmsLiveCodexEffort() {
        result(Session(disconnected = true)).use {
            val context = it.captureInputContext()
            assertTrue("Live Mosh must recover its unavailable metadata channel", context.observationAvailable)
            assertEquals("codex", context.agentId)
            assertEquals("low", context.reasoningEffort)
            assertEquals(1, opens)
            assertTrue(sessions.single().closed)
            assertFalse(commands.any { command -> command.contains("mosh-server") || command.contains("'attach'") })
        }
    }

    @Test fun healthyEffortObservationReusesTheAuthenticatedConnection() {
        result(Session()).use {
            val context = it.captureInputContext()
            assertTrue(context.observationAvailable)
            assertEquals("low", context.reasoningEffort)
            assertEquals(0, opens)
            assertEquals(1, commands.count { command -> command == "/bin/sh -c :" })
        }
    }

    @Test fun expiredEffortObservationWithoutReloadableCredentialsStaysUnavailable() {
        result(Session(disconnected = true), SshAuthentication.Password("once".toByteArray())).use {
            assertFalse(it.captureInputContext().observationAvailable)
            assertEquals(0, opens)
        }
    }

    @Test fun healthyConnectionLoadsWithoutReauthentication() {
        result(Session()).use {
            assertEquals(listOf(RecentHerdrProject("/projects/new")), it.recentHerdrProjects())
            assertEquals(0, opens)
            assertEquals("/bin/sh -c :", commands.first())
        }
    }

    @Test fun expiredBootstrapSshRecoversPickerAndSelectionWithoutRestartingMosh() {
        result(Session(disconnected = true)).use {
            assertEquals(listOf(RecentHerdrProject("/projects/new")), it.recentHerdrProjects())
            assertTrue(it.openHerdrProject("/projects/new"))
            assertEquals(2, opens)
            assertTrue(sessions.all { session -> session.closed })
            assertEquals(2, commands.count { command -> command.contains("'api' 'snapshot'") })
            assertEquals(1, commands.count { command -> command.contains("workspace") && command.contains("create") })
            assertFalse(commands.any { command -> command.contains("mosh-server") || command.contains("'attach'") })
        }
    }

    @Test fun lostCreateResponseDoesNotReplayWorkspaceCreation() {
        failCreate = true
        result(Session(disconnected = true)).use {
            assertFalse(it.openHerdrProject("/projects/new"))
            assertEquals(1, opens)
            assertEquals(1, commands.count { command -> command.contains("workspace") && command.contains("create") })
            assertTrue(sessions.single().closed)
        }
    }

    @Test fun healthyConnectionDoesNotRetryAfterLosingCreateResponse() {
        failCreate = true
        result(Session()).use {
            assertFalse(it.openHerdrProject("/projects/new"))
            assertEquals(0, opens)
            assertEquals(1, commands.count { command -> command.contains("workspace") && command.contains("create") })
        }
    }

    @Test fun failedSnapshotOnFreshConnectionStillFailsClosed() {
        failSnapshot = true
        result(Session(disconnected = true)).use {
            assertNull(it.recentHerdrProjects())
            assertFalse(it.openHerdrProject("/projects/new"))
            assertFalse(commands.any { command -> command.contains("workspace") && command.contains("create") })
            assertTrue(sessions.all { session -> session.closed })
        }
    }

    @Test fun sessionOnlyCredentialsRequireExplicitReconnect() {
        result(Session(disconnected = true), SshAuthentication.Password("once".toByteArray())).use {
            assertNull(it.recentHerdrProjects())
            assertFalse(it.openHerdrProject("/projects/new"))
            assertEquals(0, opens)
        }
    }

    @Test fun cancellationDoesNotOpenAnotherConnection() {
        val original = object : MoshSshExecSession by Session() {
            override fun openExec(command: String): MoshExecChannel = throw CancellationException()
        }
        result(original).use {
            assertNull(it.recentHerdrProjects())
            assertEquals(0, opens)
        }
    }

    @Test fun closingDuringAuthenticationClosesFreshSessionAndPreventsProjectCommands() {
        val result = result(Session(disconnected = true))
        onOpen = result::close
        assertNull(result.recentHerdrProjects())
        assertTrue(sessions.single().closed)
        assertEquals(listOf("/bin/sh -c :"), commands)
    }

    private fun result(
        original: MoshSshExecSession,
        authentication: SshAuthentication = SshAuthentication.StoredPassword { "saved".toByteArray() },
    ): MoshBootstrapResult = MoshBootstrapResult(
        addressFamily = MoshAddressFamily.IPV4,
        addressBytes = byteArrayOf(192.toByte(), 0, 2, 44),
        udpPort = 60001,
        sessionKey = "abcdefghijklmnopqrstuv".toByteArray(),
        sshSideChannel = MoshSshSideChannel(original, SshConnectionConfig(
            host = "example.com", port = 2222, username = "user", authentication = authentication,
            startupCommand = "do-not-replay", tmuxSessionSelectorEnabled = true,
            autoAttachSingleHerdrSession = true,
        ), factory),
        herdrChoice = choice,
    )

    private inner class Session(private val disconnected: Boolean = false) : MoshSshExecSession {
        override val numericAddress: InetAddress = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 44))
        var closed = false
        override fun openExec(command: String): MoshExecChannel {
            check(!closed)
            commands += command
            val text = when {
                command == "/bin/sh -c :" -> ""
                command.contains("'layout'") -> """{"result":{"layout":{"focused_pane_id":"w1:p1","tab_id":"w1:t1"}}}"""
                command.contains("'get'") -> """{"result":{"pane":{"pane_id":"w1:p1","tab_id":"w1:t1","terminal_id":"term1","agent":"codex"}}}"""
                command.contains("'process-info'") -> """{"result":{"process_info":{"pane_id":"w1:p1","foreground_processes":[{"name":"codex","pid":42}]}}}"""
                command.contains("'read'") -> "› Ask Codex to do anything\n\n  GPT-6.1-Sol low · Context 73% left"

                command.contains("'api' 'snapshot'") -> if (failSnapshot) "{}" else snapshot
                command == HERDR_RECENT_PROJECTS_COMMAND -> "/projects/open\n/projects/new\n"
                command.contains("workspace") && command.contains("create") -> "{}"
                else -> error("Unexpected command: $command")
            }
            return object : MoshExecChannel {
                override val stdout = ByteArrayInputStream(text.toByteArray())
                override val stderr = ByteArrayInputStream(byteArrayOf())
                override val isClosed = true
                override val exitStatus = 0
                override fun connect(timeoutMillis: Int) {
                    if (disconnected || (failCreate && command.contains("workspace") && command.contains("create"))) {
                        throw IOException("SSH disconnected")
                    }
                }
                override fun close() = Unit
            }
        }
        override fun close() { closed = true }
    }
}
