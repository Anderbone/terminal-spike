package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.RecentSession
import com.yanjiyu.terminalspike.terminal.TerminalController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises published snapshots and Recent writes through the real repository ownership path. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSessionMetadataTest {
    @Test
    fun sessionLabelsStopBeforeASurrogatePairAtTheUtf16Limit() = runTest {
        withSession(title = "s".repeat(95) + "🚀suffix") { fixture ->
            assertEquals("s".repeat(95), fixture.snapshot.title)
            assertEquals("s".repeat(95), fixture.snapshot.workspaceName)
            settleWrites()
            assertEquals("s".repeat(95), fixture.writes.last().hostDisplayName)
        }
    }

    @Test
    fun sessionLabelsRemoveSupplementaryFormatsAndRepairMalformedUtf16() = runTest {
        withSession(title = "Work${FORMAT}\uD800🚀\uDC00space") { fixture ->
            assertEquals("Work�🚀�space", fixture.snapshot.title)
            assertEquals("Work�🚀�space", fixture.snapshot.workspaceName)
            settleWrites()
            assertEquals("Work�🚀�space", fixture.writes.last().hostDisplayName)
        }
    }

    @Test
    fun invisibleSessionLabelsUseTheProtocolFallback() = runTest {
        for (protocol in ConnectionProtocol.entries) {
            withSession(title = "\u0000\u001B\u2066${FORMAT}\t\n", protocol = protocol) { fixture ->
                val expected = if (protocol == ConnectionProtocol.MOSH) "Mosh session" else "SSH session"
                assertEquals(expected, fixture.snapshot.title)
                assertEquals(expected, fixture.snapshot.workspaceName)
                settleWrites()
                assertEquals(expected, fixture.writes.last().hostDisplayName)
            }
        }
    }

    @Test
    fun terminalTitlesStopBeforeASurrogatePairWhenPublishedAndPersisted() = runTest {
        withSession { fixture ->
            fixture.emitTitle("t".repeat(127) + "🚀suffix")
            assertEquals("t".repeat(127), fixture.snapshot.terminalTitle)
            settleWrites()
            assertEquals("t".repeat(127), fixture.writes.last().terminalTitle)
        }
    }

    @Test
    fun terminalTitlesRemoveSupplementaryFormatsAndKeepVisibleInternationalText() = runTest {
        withSession { fixture ->
            fixture.emitTitle("构${FORMAT}建🚀 complete")
            assertEquals("构建🚀 complete", fixture.snapshot.terminalTitle)
            settleWrites()
            assertEquals("构建🚀 complete", fixture.writes.last().terminalTitle)
        }
    }

    @Test
    fun invisibleFormatsCannotBypassHostnameOrUsernameTitleRedaction() = runTest {
        withSession { fixture ->
            for (raw in listOf("pri${FORMAT}vate.example", "ope${FORMAT}rator")) {
                fixture.emitTitle(raw)
                assertNull(fixture.snapshot.terminalTitle)
                settleWrites()
                assertTrue(fixture.writes.isNotEmpty())
                assertTrue(fixture.writes.all { it.terminalTitle == null })
            }
            fixture.emitTitle("构建🚀 complete")
            assertEquals("构建🚀 complete", fixture.snapshot.terminalTitle)
        }
    }

    @Test
    fun labelControlRemovalAndTerminalTitleWhitespaceRetainTheirSeparateContracts() = runTest {
        withSession(title = "Prod\tbox\n\r\u0007") { fixture ->
            assertEquals("Prodbox", fixture.snapshot.title)
            fixture.emitTitle("\t构建\n test\r\u0007\u202E\u2069")
            assertEquals("构建 test", fixture.snapshot.terminalTitle)
            fixture.emitTitle("\u0000\u001B\u202E\u2069")
            assertNull(fixture.snapshot.terminalTitle)
        }
    }

    private fun TestScope.settleWrites() {
        advanceTimeBy(300)
        runCurrent()
    }

    private fun TestScope.withSession(
        title: String = "Workspace",
        protocol: ConnectionProtocol = ConnectionProtocol.SSH,
        test: (Fixture) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val connection = ProbeConnection()
        val writes = mutableListOf<RecentSession>()
        val repository = SshSessionRepository(
            applicationScope = scope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { connection },
            recentSessionWriter = RecentSessionWriter { writes += it },
            terminalFactory = SshSessionTerminalFactory { ProbeTerminal() },
        )
        try {
            val config = SshConnectionConfig("private.example", 22, "operator", SshAuthentication.Password(byteArrayOf(1)))
            val transport = when (protocol) {
                ConnectionProtocol.SSH -> RemoteSessionConnectionRequest.Ssh(config)
                ConnectionProtocol.MOSH -> RemoteSessionConnectionRequest.Mosh(MoshBootstrapRequest(config))
            }
            assertTrue(repository.startUserInitiatedSession(RemoteSessionStartRequest(
                title = title, workspaceName = title, connection = transport,
            )) is StartSshSessionResult.Started)
            runCurrent()
            test(Fixture(repository, connection, writes))
        } finally {
            repository.disconnectAll()
            scope.cancel()
            runCurrent()
        }
    }

    private class Fixture(
        private val repository: SshSessionRepository,
        private val connection: ProbeConnection,
        val writes: List<RecentSession>,
    ) {
        val snapshot: SshSessionSnapshot get() = repository.sessions.value.single()
        fun emitTitle(value: String) = connection.emit(value.toByteArray(Charsets.UTF_8))
    }

    private class ProbeTerminal : SshSessionTerminal {
        override val controller: TerminalController? = null
        override val columns = 80
        override val rows = 24
        override var terminalTitle: String? = null
        override fun attach(connection: Connection) = Unit
        override fun accept(bytes: ByteArray, sendResponse: (ByteArray) -> Unit) {
            terminalTitle = bytes.toString(Charsets.UTF_8)
        }
        override fun stopAndClear() = Unit
    }

    private class ProbeConnection : Connection {
        private val closed = CompletableDeferred<Unit>()
        private var receive: (ByteArray) -> Unit = {}
        override suspend fun connect(columns: Int, rows: Int, onBytes: (ByteArray) -> Unit, onState: (ConnectionState) -> Unit) {
            receive = onBytes
            onState(ConnectionState.Connected)
            closed.await()
        }
        fun emit(bytes: ByteArray) = receive(bytes)
        override fun send(bytes: ByteArray) = Unit
        override fun trySend(bytes: ByteArray) = false
        override fun resize(columns: Int, rows: Int) = Unit
        override fun answerHostIdentityPrompt(promptToken: Long, decision: HostIdentityDecision) = Unit
        override fun answerKeyboardInteractiveChallenge(challengeToken: Long, responses: List<CharArray>) {
            responses.forEach { it.fill('\u0000') }
        }
        override fun cancelKeyboardInteractiveChallenge(challengeToken: Long) = Unit
        override fun cancelPendingPrompts() = Unit
        override fun close() { closed.complete(Unit) }
    }

    private companion object {
        const val FORMAT = "\uDB40\uDC01"
    }
}
