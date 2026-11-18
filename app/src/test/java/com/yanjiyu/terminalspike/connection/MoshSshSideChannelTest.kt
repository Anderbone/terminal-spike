package com.yanjiyu.terminalspike.connection

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class MoshSshSideChannelTest {
    @Test
    fun freshUploadSuccessLeavesTheInputStreamOwnedByItsCaller() {
        val input = TrackedInput()
        val original = disconnectedUploadSession()
        val fresh = Session()
        val uploader = object : MoshSshExecSession by fresh {
            override fun uploadPastedImage(fileName: String, source: InputStream): String {
                source.use { assertArrayEquals(byteArrayOf(1, 2, 3), it.readBytes()) }
                return "/uploaded.png"
            }
        }
        try {
            owner(original, uploader, uploader).use { owner ->
                assertEquals("/uploaded.png", owner.uploadPastedImage("image.png", input))
                assertEquals(0, input.closes)
                assertEquals(1, fresh.closes)
            }
        } finally {
            input.close()
        }
        assertEquals(1, input.closes)
    }

    @Test
    fun freshUploadFailureDoesNotCloseCallerInputOrSuppressItsCloseFailure() {
        val failure = IOException("Upload failed")
        val input = TrackedInput(closeFailure = IOException("Caller close failed"))
        val fresh = Session()
        val uploader = object : MoshSshExecSession by fresh {
            override fun uploadPastedImage(fileName: String, source: InputStream): String =
                source.use {
                    assertEquals(1, it.read())
                    throw failure
                }
        }
        try {
            owner(disconnectedUploadSession(), uploader, uploader).use { owner ->
                assertSame(failure, assertThrows(IOException::class.java) {
                    owner.uploadPastedImage("image.png", input)
                })
                assertEquals(0, input.closes)
                assertEquals(setOf(failure), failure.failureTree())
                assertEquals(1, fresh.closes)
            }
        } finally {
            runCatching { input.close() }
        }
        assertEquals(1, input.closes)
    }

    @Test
    fun projectFailureSurvivesSessionAndPendingLeaseCloseFailures() {
        val operationFailure = IOException("Project operation failed")
        val sessionFailure = IOException("Session close failed")
        val leaseFailure = IOException("Pending lease close failed")
        val original = Session()
        val fresh = Session(closeFailure = sessionFailure)
        val pending = Lease(leaseFailure)
        val owner = owner(original, fresh, pending)
        try {
            val actual = assertThrows(IOException::class.java) {
                owner.withLiveProjectSession<Unit> { throw operationFailure }
            }
            assertSame(operationFailure, actual)
            assertEquals(setOf(operationFailure, sessionFailure, leaseFailure), actual.failureTree())
            assertEquals(1, fresh.closes)
            assertEquals(1, pending.closes)
        } finally {
            owner.close()
        }
        assertEquals(1, pending.closes)
        assertEquals(1, original.closes)
    }

    @Test
    fun failedSessionCloseStillClosesAndUnregistersDistinctAuthenticationLease() {
        val failure = IOException("Session close failed")
        val fresh = Session(closeFailure = failure)
        val pending = Lease()
        val owner = owner(Session(), fresh, pending)
        try {
            assertSame(failure, assertThrows(IOException::class.java) {
                owner.withLiveProjectSession { "result" }
            })
            assertEquals(1, fresh.closes)
            assertEquals(1, pending.closes)
        } finally {
            owner.close()
        }
        assertEquals("A completed operation must leave no pending lease to close again", 1, pending.closes)
    }

    @Test
    fun authenticationCancellationSurvivesPendingLeaseCloseFailure() {
        val cancelled = CancellationException("Authentication cancelled")
        val closeFailure = IOException("Pending lease close failed")
        val pending = Lease(closeFailure)
        val owner = owner(Session(), Session(), pending, cancelled)
        try {
            val actual = assertThrows(CancellationException::class.java) {
                owner.withLiveProjectSession { error("Authentication did not finish") }
            }
            assertSame(cancelled, actual)
            assertEquals(setOf(cancelled, closeFailure), actual.failureTree())
        } finally {
            owner.close()
        }
        assertEquals(1, pending.closes)
    }

    @Test
    fun failedPendingCloseIsNotRetriedWhenMoshEnds() {
        val failure = IOException("Pending lease close failed")
        val fresh = Session()
        val pending = Lease(failure)
        val owner = owner(Session(), fresh, pending)
        try {
            assertSame(failure, assertThrows(IOException::class.java) {
                owner.withLiveProjectSession { "result" }
            })
        } finally {
            owner.close()
        }
        assertEquals(1, fresh.closes)
        assertEquals(1, pending.closes)
    }

    @Test
    fun sameRegisteredAndReturnedSessionClosesOnceAfterEachOperation() {
        val original = Session()
        val fresh = Session()
        val owner = owner(original, fresh, fresh)
        try {
            assertEquals("result", owner.withLiveProjectSession { "result" })
            assertEquals(1, fresh.closes)
            assertEquals(0, original.closes)
        } finally {
            owner.close()
        }
        assertEquals(1, fresh.closes)
        assertEquals(1, original.closes)
    }

    @Test
    fun interruptedUploadDoesNotStartFreshAuthentication() {
        val interrupted = InterruptedException("Upload interrupted before reading")
        val original = object : MoshSshExecSession by Session() {
            override fun uploadPastedImage(fileName: String, source: InputStream): String =
                throw interrupted
        }
        var opens = 0
        val factory = factory(Session(), Lease(), beforeOpen = { opens++ })
        MoshSshSideChannel(original, configuration(), factory).use { owner ->
            assertSame(interrupted, assertThrows(InterruptedException::class.java) {
                owner.uploadPastedImage("image.png", ByteArrayInputStream(byteArrayOf(1)))
            })
        }
        assertEquals(0, opens)
    }

    private fun owner(
        original: MoshSshExecSession,
        fresh: MoshSshExecSession,
        pending: AutoCloseable,
        failure: Exception? = null,
    ) = MoshSshSideChannel(original, configuration(), factory(fresh, pending, failure))

    private fun factory(
        fresh: MoshSshExecSession,
        pending: AutoCloseable,
        failure: Exception? = null,
        beforeOpen: () -> Unit = {},
    ) = object : MoshSshExecSessionFactory {
        override suspend fun open(
            config: SshConnectionConfig,
            onPrompt: (HostIdentityPrompt) -> Unit,
            onKeyboardInteractiveChallenge: (KeyboardInteractiveChallenge) -> Unit,
            onRepositoryReady: (VerifyingHostKeyRepository) -> Unit,
            registerConnectingSession: (AutoCloseable) -> Boolean,
        ): MoshSshExecSession {
            beforeOpen()
            check(registerConnectingSession(pending))
            failure?.let { throw it }
            return fresh
        }
    }

    private fun configuration() = SshConnectionConfig(
        host = "example.test",
        port = 22,
        username = "user",
        authentication = SshAuthentication.StoredPassword { byteArrayOf(1) },
    )

    private fun disconnectedUploadSession() = object : MoshSshExecSession by Session() {
        override fun uploadPastedImage(fileName: String, source: InputStream): String =
            throw IOException("SSH disconnected before reading")
    }

    private class TrackedInput(private val closeFailure: IOException? = null) :
        ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
        var closes = 0
        override fun close() {
            closes++
            closeFailure?.let { throw it }
        }
    }

    private class Lease(private val failure: IOException? = null) : AutoCloseable {
        var closes = 0
        override fun close() {
            closes++
            failure?.let { throw it }
        }
    }

    private class Session(private val closeFailure: IOException? = null) : MoshSshExecSession {
        override val numericAddress: InetAddress = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
        var closes = 0
        override fun openExec(command: String): MoshExecChannel = throw IOException("SSH disconnected")
        override fun uploadPastedImage(fileName: String, source: InputStream): String = "/uploaded.png"
        override fun close() {
            closes++
            closeFailure?.let { throw it }
        }
    }

    private fun Throwable.failureTree(): Set<Throwable> = buildSet {
        fun visit(error: Throwable) {
            if (!add(error)) return
            error.suppressed.forEach(::visit)
        }
        visit(this@failureTree)
    }
}
