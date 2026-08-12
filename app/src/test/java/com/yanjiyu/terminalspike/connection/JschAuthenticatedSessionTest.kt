package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.JSch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JschAuthenticatedSessionTest {
    @Test
    fun closeRunsTheTerminalOwnerHookExactlyOnce() {
        val jsch = JSch()
        val closeHooks = AtomicInteger()
        val authenticated = AuthenticatedJschSession(
            jsch = jsch,
            session = jsch.getSession("alice", "example.test", 22),
            onClose = { closeHooks.incrementAndGet() },
        )

        authenticated.close()
        authenticated.close()

        assertEquals(1, closeHooks.get())
        assertTrue(authenticated.isClosed())
    }

    @Test
    fun directPasswordIsCopiedIntoTargetThenConfigurationArrayIsWiped() = runTest {
        val password = "correct horse battery staple".encodeToByteArray()
        val target = RecordingAuthenticationTarget()

        applyAuthentication(SshAuthentication.Password(password), target)

        assertEquals("correct horse battery staple", target.passwordCopy?.decodeToString())
        assertTrue(password.isZeroed())
    }

    @Test
    fun loadedPasswordIsWipedWhenTargetThrows() = runTest {
        val loaded = "stored-password".encodeToByteArray()
        val target = RecordingAuthenticationTarget(failPassword = true)

        runCatching {
            applyAuthentication(SshAuthentication.StoredPassword { loaded }, target)
        }

        assertEquals("stored-password", target.passwordCopy?.decodeToString())
        assertTrue(loaded.isZeroed())
    }

    @Test
    fun privateKeyAndPassphraseAreWipedWhenIdentityInstallThrows() = runTest {
        val key = "private-key-material".encodeToByteArray()
        val passphrase = "key-passphrase".encodeToByteArray()
        val target = RecordingAuthenticationTarget(failIdentity = true)

        runCatching {
            applyAuthentication(
                SshAuthentication.PrivateKey(
                    identityName = "personal-key",
                    loadKey = { key },
                    passphrase = passphrase,
                ),
                target,
            )
        }

        assertEquals("private-key-material", target.privateKeyCopy?.decodeToString())
        assertEquals("key-passphrase", target.passphraseCopy?.decodeToString())
        assertTrue(key.isZeroed())
        assertTrue(passphrase.isZeroed())
    }

    @Test
    fun reloadablePrivateKeyPassphraseIsLoadedForOneAttemptAndWipedWhenInstallThrows() = runTest {
        val key = "private-key-material".encodeToByteArray()
        val loadedPassphrase = "saved-key-passphrase".encodeToByteArray()
        val loads = AtomicInteger()
        val target = RecordingAuthenticationTarget(failIdentity = true)

        runCatching {
            applyAuthentication(
                SshAuthentication.PrivateKey(
                    identityName = "personal-key",
                    loadKey = { key },
                    passphrase = null,
                    loadPassphrase = {
                        loads.incrementAndGet()
                        loadedPassphrase
                    },
                ),
                target,
            )
        }

        assertEquals(1, loads.get())
        assertEquals("saved-key-passphrase", target.passphraseCopy?.decodeToString())
        assertTrue(key.isZeroed())
        assertTrue(loadedPassphrase.isZeroed())
    }

    @Test
    fun sessionOnlyKeyboardInteractiveResponseIsCopiedThenConfigurationArrayIsWiped() = runTest {
        val response = "one-time response".encodeToByteArray()
        val target = RecordingAuthenticationTarget()

        applyAuthentication(
            SshAuthentication.KeyboardInteractive.SessionOnly(response),
            target,
        )

        assertEquals("one-time response", target.keyboardInteractiveCopy?.decodeToString())
        assertTrue(response.isZeroed())
    }

    @Test
    fun loadedReusableKeyboardInteractiveResponseIsWipedWhenTargetThrows() = runTest {
        val response = "saved response".encodeToByteArray()
        val target = RecordingAuthenticationTarget(failKeyboardInteractive = true)

        runCatching {
            applyAuthentication(
                SshAuthentication.KeyboardInteractive.ReusableResponse { response },
                target,
            )
        }

        assertEquals("saved response", target.keyboardInteractiveCopy?.decodeToString())
        assertTrue(response.isZeroed())
    }

    @Test
    fun rejectedPreConnectRegistrationPreservesStrictShellPolicyWithoutNetworkIo() = runTest {
        val password = "one-shot-password".encodeToByteArray()
        var observedStrictHostChecking: String? = null
        var observedAuthentications: String? = null
        var observedAlias: String? = null
        var observedKeepaliveMillis: Int? = null
        var observedKeepaliveFailureCount: Int? = null
        val factory = JschAuthenticatedSessionFactory(
            KnownHostManager { File("unused-known-hosts") },
        )

        val result = factory.connect(
            config = SshConnectionConfig(
                host = "example.test",
                port = 2222,
                username = "alice",
                authentication = SshAuthentication.Password(password),
                keepaliveIntervalSeconds = 45,
            ),
            onPrompt = {},
            registerBeforeConnect = { opened ->
                observedStrictHostChecking = opened.session.getConfig("StrictHostKeyChecking")
                observedAuthentications = opened.session.getConfig("PreferredAuthentications")
                observedAlias = opened.session.hostKeyAlias
                observedKeepaliveMillis = opened.session.serverAliveInterval
                observedKeepaliveFailureCount = opened.session.serverAliveCountMax
                false
            },
        )

        assertNull(result)
        assertEquals("yes", observedStrictHostChecking)
        assertEquals("password,keyboard-interactive", observedAuthentications)
        assertEquals("[example.test]:2222", observedAlias)
        assertEquals(45_000, observedKeepaliveMillis)
        assertEquals(3, observedKeepaliveFailureCount)
        assertTrue(password.isZeroed())
    }

    @Test
    fun zeroKeepaliveDisablesProtocolProbeWithoutNetworkIo() = runTest {
        var observedKeepaliveMillis: Int? = null
        val factory = JschAuthenticatedSessionFactory(
            KnownHostManager { File("unused-known-hosts") },
        )

        val result = factory.connect(
            config = SshConnectionConfig(
                host = "example.test",
                port = 22,
                username = "alice",
                authentication = SshAuthentication.Password("temporary".encodeToByteArray()),
                keepaliveIntervalSeconds = 0,
            ),
            onPrompt = {},
            registerBeforeConnect = { opened ->
                observedKeepaliveMillis = opened.session.serverAliveInterval
                false
            },
        )

        assertNull(result)
        assertEquals(0, observedKeepaliveMillis)
    }
}

private class RecordingAuthenticationTarget(
    private val failPassword: Boolean = false,
    private val failIdentity: Boolean = false,
    private val failKeyboardInteractive: Boolean = false,
) : SshAuthenticationTarget {
    var passwordCopy: ByteArray? = null
    var privateKeyCopy: ByteArray? = null
    var passphraseCopy: ByteArray? = null
    var keyboardInteractiveCopy: ByteArray? = null

    override fun setPassword(secret: ByteArray) {
        passwordCopy = secret.copyOf()
        if (failPassword) error("expected password failure")
    }

    override fun addIdentity(
        identityName: String,
        privateKey: ByteArray,
        passphrase: ByteArray?,
    ) {
        privateKeyCopy = privateKey.copyOf()
        passphraseCopy = passphrase?.copyOf()
        if (failIdentity) error("expected identity failure")
    }

    override fun setKeyboardInteractiveReusableResponse(secret: ByteArray) {
        keyboardInteractiveCopy = secret.copyOf()
        if (failKeyboardInteractive) error("expected keyboard-interactive failure")
    }
}

private fun ByteArray.isZeroed(): Boolean = all { it == 0.toByte() }
