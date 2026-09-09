package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.core.security.credential.CredentialStoreException
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JschSshConnectionIntegrityTest {
    @Test
    fun authenticationCancellationPropagatesWithoutBeingPublishedAsFailure() = runTest {
        val cancellation = CancellationException("expected cancellation")
        val states = mutableListOf<ConnectionState>()
        val connection = JschSshConnection(
            knownHostsFile = { File("unused-known-hosts") },
            config = SshConnectionConfig(
                host = "example.test",
                port = 22,
                username = "alice",
                authentication = SshAuthentication.StoredPassword { throw cancellation },
            ),
        )

        var thrown: Throwable? = null
        try {
            connection.connect(80, 24, {}, states::add)
        } catch (error: Throwable) {
            thrown = error
        }

        assertTrue(thrown === cancellation)
        assertFalse(states.any { it is ConnectionState.Failed })
        assertFalse(states.any { it is ConnectionState.Disconnected })
    }

    @Test
    fun terminalCallbackFailureCannotBypassItsCleanup() {
        var cleaned = false
        val publisher = ConnectionStatePublisher {
            throw IllegalStateException("expected callback failure")
        }

        val thrown = runCatching {
            publisher.publishTerminalAndCleanup(ConnectionState.Disconnected) {
                cleaned = true
            }
        }.exceptionOrNull()

        assertTrue(thrown is ConnectionCallbackException)
        assertTrue(cleaned)
        assertFalse(publisher.publish(ConnectionState.Failed("late failure")))
    }

    @Test
    fun callbackIOExceptionIsNotMisclassifiedAsTransportLoss() {
        val callbackError = runCatching {
            deliverConnectionBytes(
                onBytes = { throw IOException("application callback failed") },
                bytes = byteArrayOf(1),
            )
        }.exceptionOrNull()

        assertTrue(callbackError is ConnectionCallbackException)
        val failure = sshFailure(repository = null, error = callbackError as Exception)
        assertEquals(ConnectionFailureDisposition.TERMINAL, failure.disposition)
    }

    @Test
    fun unavailableStoredCredentialFailsBeforeHandshakeWithRecoveryMessage() = runTest {
        val states = mutableListOf<ConnectionState>()
        val connection = JschSshConnection(
            knownHostsFile = { File("unused-known-hosts") },
            config = SshConnectionConfig(
                host = "example.test",
                port = 22,
                username = "alice",
                authentication = SshAuthentication.PrivateKey(
                    identityName = "key",
                    loadKey = {
                        throw CredentialStoreException.Missing(
                            SecretId.parseCanonical("10000000-0000-4000-8000-000000000001"),
                        )
                    },
                    passphrase = null,
                ),
            ),
        )

        connection.connect(
            columns = 80,
            rows = 24,
            onBytes = {},
            onState = states::add,
        )

        assertEquals(
            "Saved credential is missing or damaged. Re-enter or re-import it.",
            (states.last() as ConnectionState.Failed).message,
        )
        assertEquals(
            ConnectionFailureDisposition.TERMINAL,
            (states.last() as ConnectionState.Failed).disposition,
        )
        assertFalse(states.any { it is ConnectionState.Connected })
    }

    @Test
    fun closeBeforeConnectStartsClearsConfigurationOwnedSecrets() {
        val password = "queued-password".toByteArray()
        JschSshConnection(
            knownHostsFile = { File("unused-known-hosts") },
            config = SshConnectionConfig(
                host = "example.test",
                port = 22,
                username = "alice",
                authentication = SshAuthentication.Password(password),
            ),
        ).close()
        assertTrue(password.all { it == 0.toByte() })

        val passphrase = "queued-passphrase".toByteArray()
        JschSshConnection(
            knownHostsFile = { File("unused-known-hosts") },
            config = SshConnectionConfig(
                host = "example.test",
                port = 22,
                username = "alice",
                authentication = SshAuthentication.PrivateKey(
                    identityName = "key",
                    loadKey = { error("The connection never started") },
                    passphrase = passphrase,
                ),
            ),
        ).close()
        assertTrue(passphrase.all { it == 0.toByte() })
    }

    @Test
    fun fullWriterQueueRejectsWithoutDroppingAnAcceptedItem() {
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val writesCompleted = CountDownLatch(2)
        val written = Collections.synchronizedList(mutableListOf<ByteArray>())
        val failures = Collections.synchronizedList(mutableListOf<Exception>())
        val writer = BoundedSshWriter(capacity = 1, pollIntervalMillis = 10L)
        writer.start(
            stream = object : OutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    firstWriteStarted.countDown()
                    assertTrue(releaseFirstWrite.await(2, TimeUnit.SECONDS))
                    written += bytes.copyOfRange(offset, offset + length)
                    writesCompleted.countDown()
                }

                override fun write(value: Int) = error("Bulk writes are expected")
            },
            onFailure = failures::add,
        )

        try {
            assertTrue(writer.offer(byteArrayOf(1)))
            assertTrue(firstWriteStarted.await(2, TimeUnit.SECONDS))
            assertTrue(writer.offer(byteArrayOf(2)))
            assertFalse(writer.offer(byteArrayOf(3)))
            releaseFirstWrite.countDown()
            assertTrue(writesCompleted.await(2, TimeUnit.SECONDS))

            assertEquals(listOf(1, 2), written.map { it.single().toInt() })
            assertTrue(failures.isEmpty())
        } finally {
            releaseFirstWrite.countDown()
            writer.stop()
        }
    }

    @Test
    fun resizeUsesWriterThreadCoalescesAndPreservesInputCapacity() {
        val caller = Thread.currentThread()
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val completed = CountDownLatch(3)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val workers = Collections.synchronizedList(mutableListOf<Thread>())
        val failures = Collections.synchronizedList(mutableListOf<Exception>())
        val writer = BoundedSshWriter(capacity = 1, pollIntervalMillis = 10L)
        writer.start(
            stream = object : OutputStream() {
                override fun write(value: Int) = error("Bulk writes are expected")

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (bytes[offset] == 1.toByte()) {
                        firstWriteStarted.countDown()
                        assertTrue(releaseFirstWrite.await(2, TimeUnit.SECONDS))
                    }
                    workers += Thread.currentThread()
                    events += "input:${bytes[offset]}"
                    completed.countDown()
                }
            },
            onResize = { columns, rows ->
                workers += Thread.currentThread()
                events += "resize:$columns:$rows"
                completed.countDown()
            },
            onFailure = failures::add,
        )
        try {
            assertTrue(writer.offer(byteArrayOf(1)))
            assertTrue(firstWriteStarted.await(2, TimeUnit.SECONDS))
            repeat(100) { assertTrue(writer.resize(80 + it, 24 + it)) }
            assertTrue(writer.offer(byteArrayOf(2)))
            assertFalse(writer.offer(byteArrayOf(3)))
            releaseFirstWrite.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("input:1", "resize:179:123", "input:2"), events)
            assertTrue(workers.all { it !== caller && it === workers.first() })
            assertTrue(failures.isEmpty())
        } finally {
            releaseFirstWrite.countDown()
            writer.stop()
        }
        assertFalse(writer.resize(80, 24))
    }

    @Test
    fun resizeWakesIdleWriterWithoutWaitingForInputOrPollTimeout() {
        val resized = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<Exception>())
        val writer = BoundedSshWriter(capacity = 1, pollIntervalMillis = 60_000L)
        writer.start(
            stream = object : OutputStream() {
                override fun write(value: Int) = error("No user input was offered")
            },
            onResize = { columns, rows ->
                assertEquals(1, columns)
                assertEquals(1, rows)
                resized.countDown()
            },
            onFailure = failures::add,
        )
        try {
            assertTrue(writer.resize(0, -1))
            assertTrue(resized.await(2, TimeUnit.SECONDS))
            assertTrue(failures.isEmpty())
        } finally {
            writer.stop()
        }
    }

    @Test
    fun writerFailureStopsAcceptanceAndPublishesOneTerminalFailure() {
        val terminalState = CountDownLatch(1)
        val states = Collections.synchronizedList(mutableListOf<ConnectionState>())
        val publisher = ConnectionStatePublisher { state ->
            states += state
            if (state is ConnectionState.Failed) terminalState.countDown()
        }
        val writer = BoundedSshWriter(capacity = 2, pollIntervalMillis = 10L)
        publisher.publish(ConnectionState.Connecting)
        publisher.publish(ConnectionState.Connected)
        writer.start(
            stream = object : OutputStream() {
                override fun write(value: Int) = throw IOException("expected test failure")

                override fun write(bytes: ByteArray, offset: Int, length: Int) =
                    throw IOException("expected test failure")
            },
            onFailure = {
                publisher.publish(
                    transientTransportFailure("SSH connection lost while sending data."),
                )
            },
        )

        try {
            assertTrue(writer.offer(byteArrayOf(7)))
            assertTrue(terminalState.await(2, TimeUnit.SECONDS))
            assertFalse(writer.offer(byteArrayOf(8)))
            assertFalse(publisher.publish(ConnectionState.Disconnected))

            assertEquals(1, states.count { it is ConnectionState.Failed })
            assertEquals(0, states.count { it is ConnectionState.Disconnected })
            assertTrue(states.last() is ConnectionState.Failed)
            assertEquals(
                ConnectionFailureDisposition.TRANSIENT_TRANSPORT,
                (states.last() as ConnectionState.Failed).disposition,
            )
        } finally {
            writer.stop()
        }
    }

    @Test
    fun explicitWriterStopDoesNotReportFailure() {
        val failures = Collections.synchronizedList(mutableListOf<Exception>())
        val writer = BoundedSshWriter(capacity = 1, pollIntervalMillis = 10L)
        writer.start(
            stream = object : OutputStream() {
                override fun write(value: Int) = Unit
            },
            onFailure = failures::add,
        )

        writer.stop()

        assertFalse(writer.offer(byteArrayOf(1)))
        assertTrue(failures.isEmpty())
    }

    @Test
    fun explicitDisconnectSuppressesAnyLateTransportFailure() {
        val states = mutableListOf<ConnectionState>()
        val publisher = ConnectionStatePublisher(states::add)
        publisher.publish(ConnectionState.Connecting)
        publisher.publish(ConnectionState.Connected)

        assertTrue(publisher.publish(ConnectionState.Disconnected))
        assertFalse(publisher.publish(ConnectionState.Failed("late writer failure")))

        assertEquals(1, states.count { it is ConnectionState.Disconnected })
        assertEquals(0, states.count { it is ConnectionState.Failed })
        assertTrue(states.last() is ConnectionState.Disconnected)
    }

    @Test
    fun transportTerminationMapsExplicitCloseRemoteExitUnexpectedEofAndFailuresTruthfully() {
        assertTrue(
            terminalConnectionState(
                explicitCloseRequested = true,
                transportFailure = transientTransportFailure("late writer failure"),
                fallbackFailure = transientTransportFailure("late read failure"),
            ) is ConnectionState.Disconnected,
        )
        assertTrue(
            terminalConnectionState(
                explicitCloseRequested = false,
                transportFailure = null,
                fallbackFailure = transientTransportFailure("late read failure"),
                remoteExitStatus = 0,
            ) is ConnectionState.Disconnected,
        )
        assertEquals(
            "callback failed",
            (
                terminalConnectionState(
                    explicitCloseRequested = false,
                    transportFailure = null,
                    fallbackFailure = ConnectionState.Failed("callback failed"),
                    remoteExitStatus = 0,
                ) as ConnectionState.Failed
            ).message,
        )
        val unexpectedEof = terminalConnectionState(
            explicitCloseRequested = false,
            transportFailure = null,
            fallbackFailure = null,
            remoteExitStatus = -1,
        )
        assertTrue(unexpectedEof is ConnectionState.Failed)
        assertEquals(
            ConnectionFailureDisposition.TRANSIENT_TRANSPORT,
            (unexpectedEof as ConnectionState.Failed).disposition,
        )
        assertEquals(
            "writer failed",
            (
                terminalConnectionState(
                    false,
                    transientTransportFailure("writer failed"),
                    transientTransportFailure("read failed"),
                ) as ConnectionState.Failed
            ).message,
        )
        assertEquals(
            "connect failed",
            (
                terminalConnectionState(
                    false,
                    null,
                    ConnectionState.Failed("connect failed"),
                ) as ConnectionState.Failed
            ).message,
        )
    }
}
