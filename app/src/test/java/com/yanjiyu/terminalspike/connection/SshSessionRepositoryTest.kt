package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.core.model.RecentSession
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.model.TerminalRemoteClipboardRequest
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SshSessionRepositoryTest {
    @Test
    fun liveSessionBelongsToApplicationOwnerNotAUiClient() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val connection = FakeConnection()
        val foregroundStarts = AtomicInteger()
        val repository = repository(
            scope = ownerScope,
            connectionFactory = { connection },
            foregroundStarter = {
                foregroundStarts.incrementAndGet()
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
        )

        try {
            val result = repository.startUserInitiatedSession("Remote shell", config())
                as StartSshSessionResult.Started
            runCurrent()

            assertEquals(1, foregroundStarts.get())
            assertEquals(ConnectionState.Connected, repository.sessions.value.single().connectionState)
            assertTrue(repository.requiresForegroundService())

            val shortLivedUiOwner = SupervisorJob()
            shortLivedUiOwner.cancel()
            runCurrent()

            assertEquals(result.sessionId, repository.sessions.value.single().id)
            assertEquals(0, connection.closeCalls.get())
            assertTrue(repository.requiresForegroundService())

            repository.close(result.sessionId)
            runCurrent()

            assertTrue(repository.sessions.value.isEmpty())
            assertEquals(1, connection.closeCalls.get())
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun unavailableForegroundServiceRejectsBeforeTransportAndClearsSecrets() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val factoryCalls = AtomicInteger()
        val terminal = FakeTerminal()
        val password = "do-not-retain".toByteArray()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter { SessionForegroundStartResult.Unavailable },
            connectionFactory = SshConnectionFactory {
                factoryCalls.incrementAndGet()
                FakeConnection()
            },
            terminalFactory = SshSessionTerminalFactory { terminal },
        )
        try {
            val result = repository.startUserInitiatedSession(
                title = "Remote shell",
                config = config(SshAuthentication.Password(password)),
            )

            assertEquals(StartSshSessionResult.ForegroundServiceUnavailable, result)
            assertEquals(0, factoryCalls.get())
            assertTrue(repository.sessions.value.isEmpty())
            assertTrue(password.all { it == 0.toByte() })
            assertEquals(1, terminal.stopCalls.get())
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun notificationDenialLimitsVisibilityButDoesNotRejectUserStartedSession() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repository = repository(
            scope = ownerScope,
            connectionFactory = { FakeConnection() },
            foregroundStarter = {
                SessionForegroundStartResult.Started(
                    SessionNotificationVisibility.LIMITED_BY_PERMISSION,
                )
            },
        )

        try {
            val result = repository.startUserInitiatedSession("Remote shell", config())

            assertTrue(result is StartSshSessionResult.Started)
            assertEquals(
                SessionNotificationVisibility.LIMITED_BY_PERMISSION,
                (result as StartSshSessionResult.Started).notificationVisibility,
            )
            assertEquals(1, repository.sessions.value.size)
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun duplicateStartsImmediatelyWhenAuthenticationIsReloadable() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val createdConnections = mutableListOf<FakeConnection>()
        val repository = repository(
            scope = ownerScope,
            connectionFactory = {
                FakeConnection().also(createdConnections::add)
            },
        )
        try {
            val original = repository.startUserInitiatedSession(
                "Remote shell",
                config(SshAuthentication.StoredPassword { byteArrayOf(1, 2, 3) }),
            ) as StartSshSessionResult.Started
            runCurrent()

            val duplicate = repository.duplicateUserInitiatedSession(original.sessionId)
                as DuplicateSshSessionResult.Started
            runCurrent()

            assertEquals(2, createdConnections.size)
            assertEquals(2, repository.sessions.value.size)
            assertTrue(duplicate.sessionId != original.sessionId)
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun duplicateRequiresAuthenticationWhenOriginalSecretWasOneShot() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repository = repository(scope = ownerScope, connectionFactory = { FakeConnection() })
        try {
            val original = repository.startUserInitiatedSession("Remote shell", config())
                as StartSshSessionResult.Started
            runCurrent()

            assertEquals(
                DuplicateSshSessionResult.AuthenticationRequired,
                repository.duplicateUserInitiatedSession(original.sessionId),
            )
            assertEquals(1, repository.sessions.value.size)
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun reloadablePrivateKeyPassphraseSupportsDuplicateAndReconnect() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = FakeConnection()
        val duplicate = FakeConnection()
        val reconnected = FakeConnection()
        val connections = ArrayDeque(listOf(first, duplicate, reconnected))
        val observedAuthentications = mutableListOf<SshAuthentication.PrivateKey>()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { request ->
                observedAuthentications += request.sshConfig.authentication as SshAuthentication.PrivateKey
                connections.removeFirst()
            },
            terminalFactory = SshSessionTerminalFactory { FakeTerminal() },
        )
        val authentication = SshAuthentication.PrivateKey(
            identityName = "saved-key",
            loadKey = { byteArrayOf(1, 2, 3) },
            passphrase = null,
            loadPassphrase = { byteArrayOf(4, 5, 6) },
        )

        try {
            val original = repository.startUserInitiatedSession(
                reconnectingRequest(authentication = authentication),
            ) as StartSshSessionResult.Started
            runCurrent()

            assertTrue(
                repository.duplicateUserInitiatedSession(original.sessionId) is
                    DuplicateSshSessionResult.Started,
            )
            runCurrent()
            first.emit(transientTransportFailure("lost"))
            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(3, observedAuthentications.size)
            assertTrue(observedAuthentications.all { it.passphrase == null })
            assertTrue(observedAuthentications.all { it.loadPassphrase != null })
            assertTrue(repository.sessions.value.all { it.connectionState is ConnectionState.Connected })
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun sessionOnlyPrivateKeyPassphraseStillRequiresReentryForDuplicateAndReconnect() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val connection = FakeConnection()
        val repository = repository(scope = ownerScope, connectionFactory = { connection })
        val passphrase = byteArrayOf(7, 8, 9)

        try {
            val original = repository.startUserInitiatedSession(
                reconnectingRequest(
                    authentication = SshAuthentication.PrivateKey(
                        identityName = "one-shot-key",
                        loadKey = { byteArrayOf(1, 2, 3) },
                        passphrase = passphrase,
                    ),
                ),
            ) as StartSshSessionResult.Started
            runCurrent()

            assertEquals(
                DuplicateSshSessionResult.AuthenticationRequired,
                repository.duplicateUserInitiatedSession(original.sessionId),
            )
            connection.emit(transientTransportFailure("lost"))

            val failed = repository.sessions.value.single().connectionState as ConnectionState.Failed
            assertTrue(failed.message.contains("Reconnect manually", ignoreCase = true))
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun disconnectAllEndsForegroundRequirementButKeepsReviewableTabs() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = FakeConnection()
        val second = FakeConnection()
        val connections = ArrayDeque(listOf(first, second))
        val repository = repository(
            scope = ownerScope,
            connectionFactory = { connections.removeFirst() },
        )

        try {
            repository.startUserInitiatedSession("First", config())
            repository.startUserInitiatedSession("Second", config())
            runCurrent()
            assertTrue(repository.requiresForegroundService())

            repository.disconnectAll()
            runCurrent()

            assertFalse(repository.requiresForegroundService())
            assertEquals(2, repository.sessions.value.size)
            assertTrue(repository.sessions.value.all { it.connectionState is ConnectionState.Disconnected })
            assertEquals(1, first.closeCalls.get())
            assertEquals(1, second.closeCalls.get())
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun unexpectedForegroundServiceLossPublishesFailureAndClosesTransport() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val connection = FakeConnection()
        val repository = repository(scope = ownerScope, connectionFactory = { connection })

        try {
            repository.startUserInitiatedSession("Remote shell", config())
            runCurrent()

            repository.failAllForServiceLoss("Background ownership failed.")
            runCurrent()

            assertEquals(
                ConnectionState.Failed("Background ownership failed."),
                repository.sessions.value.single().connectionState,
            )
            assertFalse(repository.requiresForegroundService())
            assertEquals(1, connection.closeCalls.get())
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun disconnectAllWhileForegroundStartIsBlockedCannotLaunchATransport() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val starterEntered = CountDownLatch(1)
        val releaseStarter = CountDownLatch(1)
        val factoryCalls = AtomicInteger()
        val password = "startup-secret".toByteArray()
        val repository = repository(
            scope = ownerScope,
            connectionFactory = {
                factoryCalls.incrementAndGet()
                FakeConnection()
            },
            foregroundStarter = {
                starterEntered.countDown()
                check(releaseStarter.await(5, TimeUnit.SECONDS))
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
        )

        try {
            val starting = async(Dispatchers.Default) {
                repository.startUserInitiatedSession(
                    "Remote shell",
                    config(SshAuthentication.Password(password)),
                )
            }
            assertTrue(starterEntered.await(5, TimeUnit.SECONDS))

            repository.disconnectAll()
            releaseStarter.countDown()

            assertEquals(StartSshSessionResult.Cancelled, starting.await())
            assertEquals(0, factoryCalls.get())
            assertEquals(ConnectionState.Disconnected, repository.sessions.value.single().connectionState)
            assertTrue(password.all { it == 0.toByte() })
        } finally {
            releaseStarter.countDown()
            ownerScope.cancel()
        }
    }

    @Test
    fun disconnectAllWhileConnectionFactoryIsBlockedClosesWithoutConnecting() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val connection = FakeConnection()
        val repository = repository(
            scope = ownerScope,
            connectionFactory = {
                factoryEntered.countDown()
                check(releaseFactory.await(5, TimeUnit.SECONDS))
                connection
            },
        )

        try {
            val starting = async(Dispatchers.Default) {
                repository.startUserInitiatedSession("Remote shell", config())
            }
            assertTrue(factoryEntered.await(5, TimeUnit.SECONDS))

            repository.disconnectAll()
            releaseFactory.countDown()

            assertEquals(StartSshSessionResult.Cancelled, starting.await())
            assertEquals(ConnectionState.Disconnected, repository.sessions.value.single().connectionState)
            assertEquals(1, connection.closeCalls.get())
            assertEquals(0, connection.connectCalls.get())
        } finally {
            releaseFactory.countDown()
            ownerScope.cancel()
        }
    }

    @Test
    fun lateTransportStateCannotResurrectADisconnectedRuntime() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val connection = FakeConnection()
        val repository = repository(scope = ownerScope, connectionFactory = { connection })

        try {
            val result = repository.startUserInitiatedSession("Remote shell", config())
                as StartSshSessionResult.Started
            runCurrent()
            repository.disconnect(result.sessionId)

            connection.emit(ConnectionState.Connected)

            assertEquals(ConnectionState.Disconnected, repository.sessions.value.single().connectionState)
            assertFalse(repository.requiresForegroundService())
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun genericMoshRuntimePublishesSafeMetadataTitleActivityAndRecentHistory() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val connection = FakeConnection()
        val terminal = FakeTerminal()
        val writes = mutableListOf<RecentSession>()
        var now = 1_000L
        var receivedRequest: RemoteSessionConnectionRequest? = null
        val password = "ephemeral-mosh-bootstrap".toByteArray()
        val bootstrap = MoshBootstrapRequest(
            ssh = config(SshAuthentication.Password(password)),
            serverCommand = "/usr/local/bin/mosh-server",
            udpPortRange = MoshPortRange(60_000, 60_010),
        )
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { request ->
                receivedRequest = request
                connection
            },
            recentSessionWriter = RecentSessionWriter { session -> writes += session },
            terminalFactory = SshSessionTerminalFactory { terminal },
            nowEpochMillis = { now },
        )

        try {
            val result = repository.startUserInitiatedSession(
                RemoteSessionStartRequest(
                    title = "operator@private.example",
                    workspaceName = "Production",
                    connection = RemoteSessionConnectionRequest.Mosh(bootstrap),
                    sourceProfileId = 12L,
                    hostProfileId = "a6d5aa79-6c8f-4bb7-82f7-29a98612003d",
                    recentSessionId = "3b16ea85-663a-49de-9619-c4c32aad7575",
                ),
            ) as StartSshSessionResult.Started
            runCurrent()

            assertTrue(receivedRequest is RemoteSessionConnectionRequest.Mosh)
            val initial = repository.sessions.value.single()
            assertEquals(result.sessionId, initial.id)
            assertEquals("Production", initial.workspaceName)
            assertEquals(ConnectionProtocol.MOSH, initial.protocol)
            assertEquals(12L, initial.sourceProfileId)
            assertEquals(60_000, repository.connectionSeedFor(initial.id)?.moshPortRange?.first)
            assertEquals(ConnectionState.Connected, initial.connectionState)

            now = 20_000L
            connection.emitBytes("build dashboard".toByteArray())
            assertEquals("build dashboard", repository.sessions.value.single().terminalTitle)
            assertEquals(20_000L, repository.sessions.value.single().lastActivityAtEpochMillis)

            now = 35_000L
            terminal.recordAcceptedInput()
            assertEquals(35_000L, repository.sessions.value.single().lastActivityAtEpochMillis)
            advanceTimeBy(RECENT_WRITE_SETTLE_MILLIS)
            runCurrent()

            val latest = writes.last()
            assertEquals("3b16ea85-663a-49de-9619-c4c32aad7575", latest.id)
            assertEquals(ConnectionProtocol.MOSH, latest.protocol)
            assertEquals("Production", latest.hostDisplayName)
            assertEquals("build dashboard", latest.terminalTitle)
            assertEquals(35_000L, latest.lastActivityAtEpochMillis)
            assertTrue(password.all { it == 0.toByte() })

            repository.disconnect(result.sessionId)
            runCurrent()
            assertTrue(password.all { it == 0.toByte() })
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun savedHostRuntimeProfileOverridesSurviveInApplicationOwnedSnapshot() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val terminal = FakeTerminal()
        var receivedConfiguration: RemoteSessionTerminalConfiguration? = null
        val requestedRenderer = TerminalRendererProfile(fontSizeSp = 18f)
        val terminalProfileId = "b2f344f6-096f-48cb-bba2-f18ca9e2b684"
        val keyboardProfileId = "95970871-50a6-4caa-bbc9-7fd4d22ca109"
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { FakeConnection() },
            terminalFactory = object : SshSessionTerminalFactory {
                override fun create(): SshSessionTerminal = terminal

                override fun create(
                    configuration: RemoteSessionTerminalConfiguration,
                ): SshSessionTerminal {
                    receivedConfiguration = configuration
                    return terminal
                }
            },
        )

        try {
            repository.startUserInitiatedSession(
                RemoteSessionStartRequest(
                    title = "Production",
                    workspaceName = "Production",
                    connection = RemoteSessionConnectionRequest.Ssh(config()),
                    terminalProfileId = terminalProfileId,
                    keyboardProfileId = keyboardProfileId,
                    terminalConfiguration = RemoteSessionTerminalConfiguration(
                        scrollbackLines = 4_096,
                        rendererProfile = requestedRenderer,
                        remoteClipboardMode = RemoteClipboardMode.DISABLED,
                    ),
                ),
            )
            runCurrent()

            val snapshot = repository.sessions.value.single()
            assertEquals(terminalProfileId, snapshot.terminalProfileId)
            assertEquals(keyboardProfileId, snapshot.keyboardProfileId)
            assertEquals(4_096, receivedConfiguration?.scrollbackLines)
            assertEquals(requestedRenderer, receivedConfiguration?.rendererProfile)
            assertEquals(RemoteClipboardMode.DISABLED, receivedConfiguration?.remoteClipboardMode)
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun askPolicyEmitsOneShotPayloadOnlyToAnActiveObserverAndLiveTransport() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val connection = FakeConnection()
        val terminal = FakeTerminal()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { connection },
            terminalFactory = SshSessionTerminalFactory { terminal },
        )
        val events = mutableListOf<RemoteClipboardWriteRequestEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.remoteClipboardRequests.collect { event -> events += event }
        }

        try {
            val started = repository.startUserInitiatedSession(
                RemoteSessionStartRequest(
                    title = "Remote shell",
                    workspaceName = "Remote shell",
                    connection = RemoteSessionConnectionRequest.Ssh(config()),
                    terminalConfiguration = RemoteSessionTerminalConfiguration(
                        remoteClipboardMode = RemoteClipboardMode.ASK,
                    ),
                ),
            ) as StartSshSessionResult.Started
            runCurrent()

            terminal.nextRemoteClipboardText = "private clipboard value"
            connection.emitBytes("frame".toByteArray())
            runCurrent()

            val event = events.single()
            assertEquals(started.sessionId, event.sessionId)
            assertEquals("private clipboard value", repository.consumeRemoteClipboardRequest(event))
            assertEquals(null, repository.consumeRemoteClipboardRequest(event))
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun disabledPolicyNeverEmitsClipboardPrompt() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val connection = FakeConnection()
        val terminal = FakeTerminal()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { connection },
            terminalFactory = SshSessionTerminalFactory { terminal },
        )
        val events = mutableListOf<RemoteClipboardWriteRequestEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.remoteClipboardRequests.collect { event -> events += event }
        }

        try {
            repository.startUserInitiatedSession(
                RemoteSessionStartRequest(
                    title = "Remote shell",
                    workspaceName = "Remote shell",
                    connection = RemoteSessionConnectionRequest.Ssh(config()),
                    terminalConfiguration = RemoteSessionTerminalConfiguration(
                        remoteClipboardMode = RemoteClipboardMode.DISABLED,
                    ),
                ),
            )
            runCurrent()
            terminal.nextRemoteClipboardText = "must be dropped"
            connection.emitBytes("frame".toByteArray())
            runCurrent()

            assertTrue(events.isEmpty())
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun askPromptEmittedWithoutAnObserverIsNotReplayedLater() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val connection = FakeConnection()
        val terminal = FakeTerminal()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { connection },
            terminalFactory = SshSessionTerminalFactory { terminal },
        )

        try {
            repository.startUserInitiatedSession("Remote shell", config())
            runCurrent()
            terminal.nextRemoteClipboardText = "not replayed"
            connection.emitBytes("frame".toByteArray())
            runCurrent()

            val events = mutableListOf<RemoteClipboardWriteRequestEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.remoteClipboardRequests.collect { event -> events += event }
            }
            runCurrent()

            assertTrue(events.isEmpty())
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun closingOriginatingSessionMakesDeliveredClipboardPromptStale() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val connection = FakeConnection()
        val terminal = FakeTerminal()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { connection },
            terminalFactory = SshSessionTerminalFactory { terminal },
        )
        val events = mutableListOf<RemoteClipboardWriteRequestEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.remoteClipboardRequests.collect { event -> events += event }
        }

        try {
            val started = repository.startUserInitiatedSession("Remote shell", config())
                as StartSshSessionResult.Started
            runCurrent()
            terminal.nextRemoteClipboardText = "stale value"
            connection.emitBytes("frame".toByteArray())
            runCurrent()
            val event = events.single()

            repository.close(started.sessionId)

            assertFalse(repository.isRemoteClipboardRequestLive(event))
            assertEquals(null, repository.consumeRemoteClipboardRequest(event))
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun reconnectReplacementRetainsIdAndRejectsLateCallbacksFromRetiredTransport() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val firstConnection = FakeConnection()
        val secondConnection = FakeConnection()
        val firstTerminal = FakeTerminal()
        val secondTerminal = FakeTerminal()
        val connections = ArrayDeque(listOf(firstConnection, secondConnection))
        val terminals = ArrayDeque(listOf(firstTerminal, secondTerminal))
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { connections.removeFirst() },
            terminalFactory = SshSessionTerminalFactory { terminals.removeFirst() },
        )

        try {
            val first = repository.startUserInitiatedSession(
                request(
                    recentSessionId = "14f4db24-0a92-46b8-86ae-7f6473e73750",
                ),
            ) as StartSshSessionResult.Started
            runCurrent()
            firstConnection.emit(ConnectionState.Failed("Connection lost."))
            assertTrue(repository.sessions.value.single().connectionState is ConnectionState.Failed)

            val replacement = repository.startUserInitiatedSession(
                request(
                    replacementSessionId = first.sessionId,
                    recentSessionId = "761b2d7a-9cbd-4343-bc20-f3e77988030e",
                ),
            ) as StartSshSessionResult.Started
            runCurrent()

            assertEquals(first.sessionId, replacement.sessionId)
            assertEquals("761b2d7a-9cbd-4343-bc20-f3e77988030e", repository.sessions.value.single().recentSessionId)
            assertEquals(ConnectionState.Connected, repository.sessions.value.single().connectionState)
            assertEquals(1, firstConnection.closeCalls.get())
            assertEquals(1, firstTerminal.stopCalls.get())

            firstConnection.emit(ConnectionState.Connected)

            assertEquals(ConnectionState.Connected, repository.sessions.value.single().connectionState)
            assertEquals(
                "761b2d7a-9cbd-4343-bc20-f3e77988030e",
                repository.sessions.value.single().recentSessionId,
            )
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun terminalTitleContainingEndpointMetadataIsNotPublishedOrPersisted() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val connection = FakeConnection()
        val terminal = FakeTerminal()
        val writes = mutableListOf<RecentSession>()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { connection },
            recentSessionWriter = RecentSessionWriter { session -> writes += session },
            terminalFactory = SshSessionTerminalFactory { terminal },
        )

        try {
            repository.startUserInitiatedSession(
                request(recentSessionId = "8e059d8e-ef11-41b5-9ba2-a8d924b2aab8"),
            )
            runCurrent()
            connection.emitBytes("operator@example.invalid".toByteArray())
            advanceTimeBy(RECENT_WRITE_SETTLE_MILLIS)
            runCurrent()

            assertEquals(null, repository.sessions.value.single().terminalTitle)
            assertTrue(writes.isNotEmpty())
            assertTrue(writes.all { it.terminalTitle == null })
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun reloadableStoredCredentialReconnectsWithBoundedFreshTransport() = runTest {
        val sensitiveStartupCommand = "tmux new-session -A -s reconnect-test"
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = FakeConnection()
        val second = FakeConnection()
        val connections = ArrayDeque(listOf(first, second))
        val factoryCalls = AtomicInteger()
        val observedConfigs = mutableListOf<SshConnectionConfig>()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { request ->
                factoryCalls.incrementAndGet()
                observedConfigs += request.sshConfig
                connections.removeFirst()
            },
            terminalFactory = SshSessionTerminalFactory { FakeTerminal() },
        )

        try {
            repository.startUserInitiatedSession(
                reconnectingRequest(
                    authentication = SshAuthentication.StoredPassword { byteArrayOf(4, 5, 6) },
                    terminalType = "xterm-direct",
                    startupCommand = sensitiveStartupCommand,
                ),
            )
            runCurrent()
            assertFalse(repository.sessions.value.toString().contains(sensitiveStartupCommand))
            first.emit(transientTransportFailure("lost"))

            assertTrue(repository.sessions.value.single().connectionState is ConnectionState.Reconnecting)
            assertEquals(1, factoryCalls.get())

            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(2, factoryCalls.get())
            assertEquals(ConnectionState.Connected, repository.sessions.value.single().connectionState)
            assertEquals(1, first.closeCalls.get())
            assertEquals(1, second.connectCalls.get())
            assertEquals(2, observedConfigs.size)
            assertTrue(observedConfigs.all { it.terminalType == "xterm-direct" })
            assertTrue(
                observedConfigs.all {
                    it.startupCommand == sensitiveStartupCommand
                },
            )
            assertFalse(repository.sessions.value.toString().contains(sensitiveStartupCommand))
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun unexpectedSshEofWithoutRemoteExitStatusStartsReconnect() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = FakeConnection()
        val second = FakeConnection()
        val connections = ArrayDeque(listOf(first, second))
        val repository = repository(
            scope = ownerScope,
            connectionFactory = { connections.removeFirst() },
        )

        try {
            repository.startUserInitiatedSession(
                reconnectingRequest(
                    authentication = SshAuthentication.StoredPassword { byteArrayOf(4, 5, 6) },
                ),
            )
            runCurrent()

            first.emit(
                terminalConnectionState(
                    explicitCloseRequested = false,
                    transportFailure = null,
                    fallbackFailure = null,
                    remoteExitStatus = -1,
                ),
            )
            assertTrue(repository.sessions.value.single().connectionState is ConnectionState.Reconnecting)

            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(ConnectionState.Connected, repository.sessions.value.single().connectionState)
            assertEquals(1, second.connectCalls.get())
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun moshReconnectRetainsGenericBootstrapOptionsAndOpensFreshTransport() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = FakeConnection()
        val second = FakeConnection()
        val connections = ArrayDeque(listOf(first, second))
        val requests = mutableListOf<RemoteSessionConnectionRequest>()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { request ->
                requests += request
                connections.removeFirst()
            },
            terminalFactory = SshSessionTerminalFactory { FakeTerminal() },
        )

        try {
            val storedPassword = SshAuthentication.StoredPassword { byteArrayOf(4, 5, 6) }
            repository.startUserInitiatedSession(
                RemoteSessionStartRequest(
                    title = "Mosh",
                    workspaceName = "Mosh",
                    connection = RemoteSessionConnectionRequest.Mosh(
                        MoshBootstrapRequest(
                            ssh = config(storedPassword),
                            serverCommand = "/usr/bin/mosh-server",
                            udpPort = 60_007,
                        ),
                    ),
                    reliabilityPolicy = RemoteSessionReliabilityPolicy(reconnectEnabled = true),
                ),
            )
            runCurrent()
            first.emit(transientTransportFailure("lost"))
            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(2, requests.size)
            assertTrue(requests.all { it is RemoteSessionConnectionRequest.Mosh })
            val retried = (requests.last() as RemoteSessionConnectionRequest.Mosh).bootstrapRequest
            assertEquals("/usr/bin/mosh-server", retried.serverCommand)
            assertEquals(60_007, retried.udpPort)
            assertEquals(ConnectionState.Connected, repository.sessions.value.single().connectionState)
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun changedNetworkGenerationsReachOnlyTheCurrentActiveMoshTransport() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val network = MutableStateFlow(
            NetworkAvailabilitySnapshot(true, 0L, 0, false),
        )
        val availability = object : NetworkAvailability {
            override val state = network
        }
        val ssh = FakeConnection()
        val mosh = HintRecordingConnection()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { request ->
                if (request is RemoteSessionConnectionRequest.Mosh) mosh else ssh
            },
            terminalFactory = SshSessionTerminalFactory { FakeTerminal() },
            networkAvailability = availability,
        )

        try {
            repository.startUserInitiatedSession("SSH", config())
            val moshSession = repository.startUserInitiatedSession(
                RemoteSessionStartRequest(
                    title = "Mosh",
                    workspaceName = "Mosh",
                    connection = RemoteSessionConnectionRequest.Mosh(
                        MoshBootstrapRequest(ssh = config()),
                    ),
                ),
            ) as StartSshSessionResult.Started
            runCurrent()
            assertEquals(listOf(0L), mosh.hints.map { it.connectivityGeneration })

            network.value = NetworkAvailabilitySnapshot(true, 1L, 4, true)
            runCurrent()
            network.value = network.value.copy(isMetered = false)
            runCurrent()
            assertEquals(listOf(0L, 1L), mosh.hints.map { it.connectivityGeneration })

            repository.close(moshSession.sessionId)
            network.value = NetworkAvailabilitySnapshot(false, 2L, 0, false)
            runCurrent()
            assertEquals(listOf(0L, 1L), mosh.hints.map { it.connectivityGeneration })
            assertEquals(1, ssh.connectCalls.get())
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun automaticMoshFallbackStartsOneFreshSshTransportOnlyForClassifiedFailure() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val mosh = FakeConnection()
        val ssh = FakeConnection()
        val connections = ArrayDeque(listOf(mosh, ssh))
        val requests = mutableListOf<RemoteSessionConnectionRequest>()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { request ->
                requests += request
                connections.removeFirst()
            },
            terminalFactory = SshSessionTerminalFactory { FakeTerminal() },
        )

        try {
            repository.startUserInitiatedSession(
                RemoteSessionStartRequest(
                    title = "Mosh",
                    workspaceName = "Mosh",
                    connection = RemoteSessionConnectionRequest.Mosh(
                        MoshBootstrapRequest(
                            ssh = config(
                                SshAuthentication.StoredPassword { byteArrayOf(4, 5, 6) },
                            ),
                        ),
                    ),
                    moshFallbackPolicy = com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy.AUTOMATIC,
                ),
            )
            runCurrent()
            mosh.emit(
                ConnectionState.Failed(
                    message = "Mosh UDP failed.",
                    moshFallbackFailure = MoshFallbackFailure.UDP,
                ),
            )
            runCurrent()

            assertEquals(2, requests.size)
            assertTrue(requests.first() is RemoteSessionConnectionRequest.Mosh)
            assertTrue(requests.last() is RemoteSessionConnectionRequest.Ssh)
            assertEquals(ConnectionProtocol.SSH, repository.sessions.value.single().protocol)
            assertEquals(ConnectionState.Connected, repository.sessions.value.single().connectionState)
            assertEquals(1, mosh.closeCalls.get())
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun reconnectWaitsOfflineAndIntentionalDisconnectCancelsPendingRetry() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val network = MutableStateFlow(
            NetworkAvailabilitySnapshot(
                isOnline = true,
                connectivityGeneration = 0L,
                addressFamily = 0,
                isMetered = false,
            ),
        )
        val availability = object : NetworkAvailability {
            override val state = network
        }
        val first = FakeConnection()
        val factoryCalls = AtomicInteger()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory {
                factoryCalls.incrementAndGet()
                first
            },
            terminalFactory = SshSessionTerminalFactory { FakeTerminal() },
            networkAvailability = availability,
        )

        try {
            val started = repository.startUserInitiatedSession(
                reconnectingRequest(
                    authentication = SshAuthentication.StoredPassword { byteArrayOf(4, 5, 6) },
                ),
            ) as StartSshSessionResult.Started
            runCurrent()
            network.value = network.value.copy(isOnline = false, connectivityGeneration = 1L)
            first.emit(transientTransportFailure("lost"))
            runCurrent()

            val waiting = repository.sessions.value.single().connectionState as ConnectionState.Reconnecting
            assertTrue(waiting.waitingForNetwork)
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(1, factoryCalls.get())

            repository.disconnect(started.sessionId)
            network.value = network.value.copy(isOnline = true, connectivityGeneration = 2L)
            advanceTimeBy(60_000L)
            runCurrent()

            assertEquals(1, factoryCalls.get())
            assertEquals(ConnectionState.Disconnected, repository.sessions.value.single().connectionState)
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun reconnectExhaustsConfiguredAttemptsAndDoesNotLoopForever() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val initial = FakeConnection()
        val failedOne = FakeConnection(
            statesOnConnect = listOf(ConnectionState.Connecting, transientTransportFailure("one")),
        )
        val failedTwo = FakeConnection(
            statesOnConnect = listOf(ConnectionState.Connecting, transientTransportFailure("two")),
        )
        val connections = ArrayDeque(listOf(initial, failedOne, failedTwo))
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { connections.removeFirst() },
            terminalFactory = SshSessionTerminalFactory { FakeTerminal() },
        )

        try {
            repository.startUserInitiatedSession(
                reconnectingRequest(
                    authentication = SshAuthentication.StoredPassword { byteArrayOf(4, 5, 6) },
                    policy = RemoteSessionReliabilityPolicy(
                        reconnectEnabled = true,
                        reconnectMaxAttempts = 2,
                        initialRetryDelayMillis = 1_000L,
                        maximumRetryDelayMillis = 2_000L,
                    ),
                ),
            )
            runCurrent()
            initial.emit(transientTransportFailure("lost"))
            advanceTimeBy(1_000L)
            runCurrent()
            advanceTimeBy(2_000L)
            runCurrent()

            val final = repository.sessions.value.single().connectionState
            assertTrue(final is ConnectionState.Failed)
            assertTrue((final as ConnectionState.Failed).message.contains("new shell"))
            assertEquals(1, failedOne.connectCalls.get())
            assertEquals(1, failedTwo.connectCalls.get())
            assertFalse(repository.requiresForegroundService())
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun terminalFailureAfterConnectedDoesNotAutomaticallyReconnect() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val initial = FakeConnection()
        val factoryCalls = AtomicInteger()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory {
                factoryCalls.incrementAndGet()
                initial
            },
            terminalFactory = SshSessionTerminalFactory { FakeTerminal() },
        )

        try {
            repository.startUserInitiatedSession(
                reconnectingRequest(
                    authentication = SshAuthentication.StoredPassword { byteArrayOf(4, 5, 6) },
                ),
            )
            runCurrent()

            initial.emit(ConnectionState.Failed("Host key changed. Connection blocked."))
            advanceTimeBy(60_000L)
            runCurrent()

            val final = repository.sessions.value.single().connectionState
            assertTrue(final is ConnectionState.Failed)
            val failure = final as ConnectionState.Failed
            assertEquals(
                ConnectionFailureDisposition.TERMINAL,
                failure.disposition,
            )
            assertEquals("Host key changed. Connection blocked.", failure.message)
            assertEquals(1, factoryCalls.get())
            assertFalse(repository.requiresForegroundService())
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun connectedFlapsDoNotReplenishTheBoundedRetryBudget() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val initial = FakeConnection()
        val flapOne = FakeConnection(
            statesOnConnect = listOf(
                ConnectionState.Connecting,
                ConnectionState.Connected,
                transientTransportFailure("flap one"),
            ),
        )
        val flapTwo = FakeConnection(
            statesOnConnect = listOf(
                ConnectionState.Connecting,
                ConnectionState.Connected,
                transientTransportFailure("flap two"),
            ),
        )
        val connections = ArrayDeque(listOf(initial, flapOne, flapTwo))
        val factoryCalls = AtomicInteger()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory {
                factoryCalls.incrementAndGet()
                connections.removeFirst()
            },
            terminalFactory = SshSessionTerminalFactory { FakeTerminal() },
            reconnectStableWindowMillis = 10_000L,
        )

        try {
            repository.startUserInitiatedSession(
                reconnectingRequest(
                    authentication = SshAuthentication.StoredPassword { byteArrayOf(4, 5, 6) },
                    policy = RemoteSessionReliabilityPolicy(
                        reconnectEnabled = true,
                        reconnectMaxAttempts = 2,
                        initialRetryDelayMillis = 1_000L,
                        maximumRetryDelayMillis = 2_000L,
                    ),
                ),
            )
            runCurrent()
            initial.emit(transientTransportFailure("initial loss"))

            advanceTimeBy(1_000L)
            runCurrent()
            advanceTimeBy(2_000L)
            runCurrent()
            advanceTimeBy(60_000L)
            runCurrent()

            val final = repository.sessions.value.single().connectionState
            assertTrue(final is ConnectionState.Failed)
            assertTrue((final as ConnectionState.Failed).message.contains("new shell"))
            assertEquals(3, factoryCalls.get())
            assertEquals(1, flapOne.connectCalls.get())
            assertEquals(1, flapTwo.connectCalls.get())
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun stableReconnectResetsTheNextOutageToAttemptOne() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val initial = FakeConnection()
        val stableReconnect = FakeConnection()
        val nextOutageReconnect = FakeConnection()
        val connections = ArrayDeque(listOf(initial, stableReconnect, nextOutageReconnect))
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory { connections.removeFirst() },
            terminalFactory = SshSessionTerminalFactory { FakeTerminal() },
            reconnectStableWindowMillis = 10_000L,
        )

        try {
            repository.startUserInitiatedSession(
                reconnectingRequest(
                    authentication = SshAuthentication.StoredPassword { byteArrayOf(4, 5, 6) },
                    policy = RemoteSessionReliabilityPolicy(
                        reconnectEnabled = true,
                        reconnectMaxAttempts = 3,
                        initialRetryDelayMillis = 1_000L,
                        maximumRetryDelayMillis = 2_000L,
                    ),
                ),
            )
            runCurrent()
            initial.emit(transientTransportFailure("first outage"))
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(ConnectionState.Connected, repository.sessions.value.single().connectionState)

            advanceTimeBy(10_000L)
            runCurrent()
            stableReconnect.emit(transientTransportFailure("second outage"))

            val waiting = repository.sessions.value.single().connectionState as
                ConnectionState.Reconnecting
            assertEquals(1, waiting.attempt)
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(1, nextOutageReconnect.connectCalls.get())
        } finally {
            repository.disconnectAll()
            ownerScope.cancel()
        }
    }

    @Test
    fun oneShotPasswordIsNeverRetainedForAutomaticReconnect() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val connection = FakeConnection()
        val factoryCalls = AtomicInteger()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory {
                factoryCalls.incrementAndGet()
                connection
            },
            terminalFactory = SshSessionTerminalFactory { FakeTerminal() },
        )

        try {
            repository.startUserInitiatedSession(
                reconnectingRequest(
                    authentication = SshAuthentication.Password("one-shot".encodeToByteArray()),
                ),
            )
            runCurrent()
            connection.emit(transientTransportFailure("lost"))
            advanceTimeBy(60_000L)
            runCurrent()

            val final = repository.sessions.value.single().connectionState
            assertTrue(final is ConnectionState.Failed)
            assertTrue((final as ConnectionState.Failed).message.contains("manually"))
            assertEquals(1, factoryCalls.get())
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun sessionOnlyKeyboardInteractiveAlwaysRequiresVisibleManualReconnect() = runTest {
        val ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val connection = FakeConnection()
        val factoryCalls = AtomicInteger()
        val repository = SshSessionRepository(
            applicationScope = ownerScope,
            foregroundStarter = SessionForegroundStarter {
                SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
            },
            connectionFactory = RemoteSessionConnectionFactory {
                factoryCalls.incrementAndGet()
                connection
            },
            terminalFactory = SshSessionTerminalFactory { FakeTerminal() },
        )

        try {
            repository.startUserInitiatedSession(
                reconnectingRequest(
                    authentication = SshAuthentication.KeyboardInteractive.SessionOnly(),
                ),
            )
            runCurrent()
            connection.emit(transientTransportFailure("lost"))
            advanceTimeBy(60_000L)
            runCurrent()

            val final = repository.sessions.value.single().connectionState
            assertTrue(final is ConnectionState.Failed)
            assertTrue((final as ConnectionState.Failed).message.contains("manually"))
            assertEquals(1, factoryCalls.get())
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun onlyLiveOrDecisionPendingStatesRequireForegroundOwnership() {
        assertTrue(ConnectionState.Connecting.requiresForegroundService())
        assertTrue(ConnectionState.Connected.requiresForegroundService())
        assertTrue(
            ConnectionState.AwaitingApproval(
                HostIdentityPrompt.FirstContact(
                    endpoint = "host",
                    algorithm = "ssh-ed25519",
                    newFingerprint = "SHA256:test",
                    promptToken = 1L,
                ),
            ).requiresForegroundService(),
        )
        assertFalse(ConnectionState.Disconnected.requiresForegroundService())
        assertFalse(ConnectionState.Failed("redacted").requiresForegroundService())
    }

    private fun repository(
        scope: CoroutineScope,
        connectionFactory: (SshConnectionConfig) -> Connection,
        foregroundStarter: () -> SessionForegroundStartResult = {
            SessionForegroundStartResult.Started(SessionNotificationVisibility.VISIBLE)
        },
    ) = SshSessionRepository(
        applicationScope = scope,
        foregroundStarter = SessionForegroundStarter { foregroundStarter() },
        connectionFactory = SshConnectionFactory(connectionFactory),
        terminalFactory = SshSessionTerminalFactory { FakeTerminal() },
    )

    private fun config(
        authentication: SshAuthentication = SshAuthentication.Password(byteArrayOf(1, 2, 3)),
        terminalType: String = "xterm-256color",
        startupCommand: String? = null,
    ) = SshConnectionConfig(
        host = "example.invalid",
        port = 22,
        username = "user",
        authentication = authentication,
        terminalType = terminalType,
        startupCommand = startupCommand,
    )

    private fun request(
        replacementSessionId: Long? = null,
        recentSessionId: String,
    ) = RemoteSessionStartRequest(
        title = "operator@example.invalid",
        workspaceName = "Remote shell",
        connection = RemoteSessionConnectionRequest.Ssh(config()),
        replacementSessionId = replacementSessionId,
        recentSessionId = recentSessionId,
    )

    private fun reconnectingRequest(
        authentication: SshAuthentication,
        policy: RemoteSessionReliabilityPolicy = RemoteSessionReliabilityPolicy(
            reconnectEnabled = true,
            reconnectMaxAttempts = 5,
        ),
        terminalType: String = "xterm-256color",
        startupCommand: String? = null,
    ) = RemoteSessionStartRequest(
        title = "Remote shell",
        workspaceName = "Remote shell",
        connection = RemoteSessionConnectionRequest.Ssh(
            config(
                authentication = authentication,
                terminalType = terminalType,
                startupCommand = startupCommand,
            ),
        ),
        reliabilityPolicy = policy,
    )

    private class FakeTerminal : SshSessionTerminal {
        override val controller: TerminalController? = null
        override val columns: Int = 80
        override val rows: Int = 24
        override var terminalTitle: String? = null
            private set
        val stopCalls = AtomicInteger()
        private var onInputAccepted: () -> Unit = {}
        var nextRemoteClipboardText: String? = null

        override fun attach(connection: Connection) = Unit

        override fun attach(connection: Connection, onInputAccepted: () -> Unit) {
            this.onInputAccepted = onInputAccepted
        }

        override fun accept(bytes: ByteArray, sendResponse: (ByteArray) -> Unit) {
            terminalTitle = bytes.toString(Charsets.UTF_8)
        }

        override fun accept(
            bytes: ByteArray,
            sendResponse: (ByteArray) -> Unit,
            onRemoteClipboardRequest: (TerminalRemoteClipboardRequest) -> Unit,
        ) {
            nextRemoteClipboardText?.let { text ->
                nextRemoteClipboardText = null
                onRemoteClipboardRequest(TerminalRemoteClipboardRequest(text))
            }
            accept(bytes, sendResponse)
        }

        override fun detach() {
            onInputAccepted = {}
        }

        override fun stopAndClear() {
            stopCalls.incrementAndGet()
            detach()
        }

        fun recordAcceptedInput() = onInputAccepted()
    }

    private open class FakeConnection(
        private val statesOnConnect: List<ConnectionState> = listOf(
            ConnectionState.Connecting,
            ConnectionState.Connected,
        ),
    ) : Connection {
        private val closed = CompletableDeferred<Unit>()
        private var stateCallback: ((ConnectionState) -> Unit)? = null
        private var bytesCallback: ((ByteArray) -> Unit)? = null
        val closeCalls = AtomicInteger()
        val connectCalls = AtomicInteger()

        override suspend fun connect(
            columns: Int,
            rows: Int,
            onBytes: (ByteArray) -> Unit,
            onState: (ConnectionState) -> Unit,
        ) {
            connectCalls.incrementAndGet()
            stateCallback = onState
            bytesCallback = onBytes
            statesOnConnect.forEach(onState)
            closed.await()
        }

        override fun send(bytes: ByteArray) = Unit

        override fun trySend(bytes: ByteArray): Boolean = !closed.isCompleted && bytes.isNotEmpty()

        override fun resize(columns: Int, rows: Int) = Unit

        override fun answerHostIdentityPrompt(
            promptToken: Long,
            decision: HostIdentityDecision,
        ) = Unit

        override fun answerKeyboardInteractiveChallenge(
            challengeToken: Long,
            responses: List<CharArray>,
        ) {
            responses.forEach { it.fill('\u0000') }
        }

        override fun cancelKeyboardInteractiveChallenge(challengeToken: Long) = Unit

        override fun cancelPendingPrompts() = Unit

        override fun close() {
            if (closeCalls.incrementAndGet() == 1) {
                stateCallback?.invoke(ConnectionState.Disconnected)
                closed.complete(Unit)
            }
        }

        fun emit(state: ConnectionState) {
            stateCallback?.invoke(state)
        }

        fun emitBytes(bytes: ByteArray) {
            bytesCallback?.invoke(bytes)
        }
    }

    private class HintRecordingConnection : FakeConnection(), MoshNetworkHintReceiver {
        val hints = mutableListOf<NetworkAvailabilitySnapshot>()

        override fun updateNetworkHint(snapshot: NetworkAvailabilitySnapshot) {
            hints += snapshot
        }
    }

    private companion object {
        const val RECENT_WRITE_SETTLE_MILLIS = 1_000L
    }
}
