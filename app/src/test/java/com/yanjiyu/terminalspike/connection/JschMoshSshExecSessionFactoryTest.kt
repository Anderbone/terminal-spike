package com.yanjiyu.terminalspike.connection

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JschMoshSshExecSessionFactoryTest {
    @Test
    fun cancelledCredentialLoadRemainsCancellationAndClosesRegisteredSession() = runTest {
        val cancellation = CancellationException("credential load cancelled")
        val fixture = FactoryFixture()

        val failure = runCatching {
            fixture.open(SshAuthentication.StoredPassword { throw cancellation })
        }.exceptionOrNull()

        assertSame(cancellation, failure)
        assertTrue(checkNotNull(fixture.registered).isClosed())
    }

    @Test
    fun interruptedCredentialLoadRemainsInterruptionAndWipesPassphrase() = runTest {
        val interruption = InterruptedException("credential load interrupted")
        val passphrase = "temporary-passphrase".encodeToByteArray()
        val fixture = FactoryFixture()

        val failure = runCatching {
            fixture.open(
                SshAuthentication.PrivateKey(
                    identityName = "test-key",
                    loadKey = { throw interruption },
                    passphrase = passphrase,
                ),
            )
        }.exceptionOrNull()

        assertSame(interruption, failure)
        assertTrue(passphrase.all { it == 0.toByte() })
        assertTrue(checkNotNull(fixture.registered).isClosed())
    }

    @Test
    fun credentialFailureStillUsesDisplaySafeSshFailureAndClosesRegisteredSession() = runTest {
        val cause = IOException("credential provider private details")
        val fixture = FactoryFixture()

        val failure = runCatching {
            fixture.open(SshAuthentication.StoredPassword { throw cause })
        }.exceptionOrNull()

        assertTrue(failure is MoshBootstrapException)
        failure as MoshBootstrapException
        assertEquals(MoshBootstrapFailure.SSH, failure.failure)
        assertSame(cause, failure.cause)
        assertTrue(!failure.message.orEmpty().contains("private details"))
        assertTrue(checkNotNull(fixture.registered).isClosed())
    }

    @Test
    fun bootstrapRetainsCancellationFromRealAuthenticationFactory() = runTest {
        val cancellation = CancellationException("credential load cancelled")
        val fixture = FactoryFixture()
        val states = mutableListOf<MoshBootstrapState>()
        val executor = MoshBootstrapExecutor(fixture.factory)

        val failure = runCatching {
            executor.bootstrap(
                MoshBootstrapRequest(fixture.config(SshAuthentication.StoredPassword { throw cancellation })),
                states::add,
            )
        }.exceptionOrNull()

        assertSame(cancellation, failure)
        assertEquals(listOf(MoshBootstrapState.Authenticating), states)
        executor.close()
    }

    @Test
    fun bootstrapClassifiesRealAuthenticationInterruptionAsCancelledAndRestoresInterrupt() = runTest {
        val interruption = InterruptedException("credential load interrupted")
        val fixture = FactoryFixture()
        val executor = MoshBootstrapExecutor(fixture.factory)
        try {
            val failure = runCatching {
                executor.bootstrap(
                    MoshBootstrapRequest(fixture.config(SshAuthentication.StoredPassword { throw interruption })),
                    {},
                )
            }.exceptionOrNull()

            assertTrue(failure is MoshBootstrapException)
            failure as MoshBootstrapException
            assertEquals(MoshBootstrapFailure.CANCELLED, failure.failure)
            assertSame(interruption, failure.cause)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            executor.close()
        }
    }
}

/** Authentication fails before Session.connect, so these tests never open a socket. */
private class FactoryFixture {
    val factory = JschMoshSshExecSessionFactory(
        JschAuthenticatedSessionFactory(fileKnownHostManager { File("unused-known-hosts") }),
    )
    var registered: AuthenticatedJschSession? = null

    fun config(authentication: SshAuthentication) = SshConnectionConfig(
        host = "example.test",
        port = 22,
        username = "alice",
        authentication = authentication,
    )

    suspend fun open(authentication: SshAuthentication): MoshSshExecSession? = factory.open(
        config = config(authentication),
        onPrompt = { error("No host prompt expected before authentication loading.") },
        onKeyboardInteractiveChallenge = { error("No authentication challenge expected.") },
        onRepositoryReady = {},
        registerConnectingSession = {
            registered = it as AuthenticatedJschSession
            true
        },
    )
}
