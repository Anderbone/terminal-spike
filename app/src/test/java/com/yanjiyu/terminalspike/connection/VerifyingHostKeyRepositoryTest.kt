package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.HostKeyRepository
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyingHostKeyRepositoryTest {
    @Test
    fun firstContactRejectDoesNotStoreTheKey() = withStore { store ->
        var prompt: HostIdentityPrompt? = null
        val received = CountDownLatch(1)
        val repository = repository(store) {
            prompt = it
            received.countDown()
        }
        val result = verifyAsync(repository, ed25519Key(1)) {
            assertTrue(received.await(2, TimeUnit.SECONDS))
            val firstContact = prompt as HostIdentityPrompt.FirstContact
            repository.answerPrompt(firstContact.promptToken, HostIdentityDecision.Reject)
        }

        assertEquals(HostKeyRepository.NOT_INCLUDED, result)
        assertEquals(HostKeyFailure.REJECTED, repository.failure)
        assertTrue(store.entries("example.test").isEmpty())
    }

    @Test
    fun firstContactTrustOnceAcceptsOnlyTheCurrentSession() = withStore { store ->
        var prompt: HostIdentityPrompt.FirstContact? = null
        val received = CountDownLatch(1)
        val repository = repository(store) {
            prompt = it as HostIdentityPrompt.FirstContact
            received.countDown()
        }
        val result = verifyAsync(repository, ed25519Key(2)) {
            assertTrue(received.await(2, TimeUnit.SECONDS))
            repository.answerPrompt(
                requireNotNull(prompt).promptToken,
                HostIdentityDecision.TrustOnce,
            )
        }

        assertEquals(HostKeyRepository.OK, result)
        assertEquals(HostKeyRepository.OK, repository.check("example.test", ed25519Key(2)))
        assertTrue(store.entries("example.test").isEmpty())

        val freshPromptReceived = CountDownLatch(1)
        var freshPrompt: HostIdentityPrompt.FirstContact? = null
        val freshRepository = repository(store) {
            freshPrompt = it as HostIdentityPrompt.FirstContact
            freshPromptReceived.countDown()
        }
        val freshResult = verifyAsync(freshRepository, ed25519Key(2)) {
            assertTrue(freshPromptReceived.await(2, TimeUnit.SECONDS))
            freshRepository.answerPrompt(
                requireNotNull(freshPrompt).promptToken,
                HostIdentityDecision.Reject,
            )
        }
        assertEquals(HostKeyRepository.NOT_INCLUDED, freshResult)
    }

    @Test
    fun stalePromptTokenCannotResolveTheCurrentDecision() = withStore { store ->
        var prompt: HostIdentityPrompt.FirstContact? = null
        val received = CountDownLatch(1)
        val repository = repository(store) {
            prompt = it as HostIdentityPrompt.FirstContact
            received.countDown()
        }
        val verifier = thread { repository.check("example.test", ed25519Key(21)) }

        assertTrue(received.await(2, TimeUnit.SECONDS))
        val current = requireNotNull(prompt)
        assertEquals(
            false,
            repository.answerPrompt(current.promptToken + 1L, HostIdentityDecision.TrustAndSave),
        )
        assertTrue(verifier.isAlive)
        assertTrue(repository.answerPrompt(current.promptToken, HostIdentityDecision.Reject))
        verifier.join(2_000)
        assertTrue(!verifier.isAlive)
        assertTrue(store.entries("example.test").isEmpty())
    }

    @Test
    fun firstContactRejectsChangedKeyOnlyReplacementDecision() = withStore { store ->
        var prompt: HostIdentityPrompt.FirstContact? = null
        val received = CountDownLatch(1)
        val repository = repository(store) {
            prompt = it as HostIdentityPrompt.FirstContact
            received.countDown()
        }
        val verifier = thread { repository.check("example.test", ed25519Key(22)) }

        assertTrue(received.await(2, TimeUnit.SECONDS))
        val current = requireNotNull(prompt)
        assertEquals(
            false,
            repository.answerPrompt(
                current.promptToken,
                HostIdentityDecision.ReplaceSavedKey,
            ),
        )
        assertTrue(verifier.isAlive)
        assertTrue(repository.answerPrompt(current.promptToken, HostIdentityDecision.Reject))
        verifier.join(2_000)

        assertTrue(!verifier.isAlive)
        assertTrue(store.entries("example.test").isEmpty())
    }

    @Test
    fun firstContactTrustAndSavePersistsTheOfferedKey() = withStore { store ->
        var prompt: HostIdentityPrompt.FirstContact? = null
        val received = CountDownLatch(1)
        val offered = ed25519Key(3)
        val repository = repository(store) {
            prompt = it as HostIdentityPrompt.FirstContact
            received.countDown()
        }
        val result = verifyAsync(repository, offered) {
            assertTrue(received.await(2, TimeUnit.SECONDS))
            val firstContact = requireNotNull(prompt)
            assertEquals("example.test", firstContact.endpoint)
            assertEquals("ssh-ed25519", firstContact.algorithm)
            assertTrue(firstContact.newFingerprint.startsWith("SHA256:"))
            repository.answerPrompt(firstContact.promptToken, HostIdentityDecision.TrustAndSave)
        }

        assertEquals(HostKeyRepository.OK, result)
        assertArrayEquals(offered, store.entries("example.test").single().key)
    }

    @Test
    fun changedKeyRejectShowsPreviousAndNewFingerprintsAndKeepsSavedKey() = withStore { store ->
        val original = ed25519Key(4)
        val offered = ed25519Key(5)
        store.trust("example.test", "ssh-ed25519", original)
        var prompt: HostIdentityPrompt.Changed? = null
        val received = CountDownLatch(1)
        val repository = repository(store) {
            prompt = it as HostIdentityPrompt.Changed
            received.countDown()
        }
        val result = verifyAsync(repository, offered) {
            assertTrue(received.await(2, TimeUnit.SECONDS))
            val changed = requireNotNull(prompt)
            assertNotEquals(changed.previousFingerprint, changed.newFingerprint)
            repository.answerPrompt(changed.promptToken, HostIdentityDecision.Reject)
        }

        assertEquals(HostKeyRepository.CHANGED, result)
        assertEquals(HostKeyFailure.CHANGED, repository.failure)
        assertArrayEquals(original, store.entries("example.test").single().key)
    }

    @Test
    fun explicitChangedKeyReplacementCommitsTheOfferedKey() = withStore { store ->
        store.trust("example.test", "ssh-ed25519", ed25519Key(6))
        val offered = rsaKey(7)
        var prompt: HostIdentityPrompt.Changed? = null
        val received = CountDownLatch(1)
        val repository = repository(store) {
            prompt = it as HostIdentityPrompt.Changed
            received.countDown()
        }
        val result = verifyAsync(repository, offered) {
            assertTrue(received.await(2, TimeUnit.SECONDS))
            repository.answerPrompt(
                requireNotNull(prompt).promptToken,
                HostIdentityDecision.ReplaceSavedKey,
            )
        }

        assertEquals(HostKeyRepository.OK, result)
        val replacement = store.entries("example.test").single()
        assertEquals("ssh-rsa", replacement.algorithm)
        assertArrayEquals(offered, replacement.key)
    }

    @Test
    fun changedKeyReplacementRejectsAStaleExpectedEndpointSnapshot() = withStore { store ->
        store.trust("example.test", "ssh-ed25519", ed25519Key(8))
        val intervening = rsaKey(9)
        var prompt: HostIdentityPrompt.Changed? = null
        val received = CountDownLatch(1)
        val repository = repository(store) {
            prompt = it as HostIdentityPrompt.Changed
            received.countDown()
        }
        val result = verifyAsync(repository, ed25519Key(10)) {
            assertTrue(received.await(2, TimeUnit.SECONDS))
            store.replaceEndpoint("example.test", "ssh-rsa", intervening)
            repository.answerPrompt(
                requireNotNull(prompt).promptToken,
                HostIdentityDecision.ReplaceSavedKey,
            )
        }

        assertEquals(HostKeyRepository.CHANGED, result)
        assertEquals(HostKeyFailure.CHANGED, repository.failure)
        assertArrayEquals(intervening, store.entries("example.test").single().key)
    }

    @Test
    fun replacementComparesTheCompleteEndpointKeySet() = withStore { store ->
        val originalEd25519 = ed25519Key(14)
        val originalRsa = rsaKey(15)
        val interveningRsa = rsaKey(16)
        store.trust("example.test", "ssh-ed25519", originalEd25519)
        store.trust("example.test", "ssh-rsa", originalRsa)
        var prompt: HostIdentityPrompt.Changed? = null
        val received = CountDownLatch(1)
        val repository = repository(store) {
            prompt = it as HostIdentityPrompt.Changed
            received.countDown()
        }

        val result = verifyAsync(repository, ed25519Key(17)) {
            assertTrue(received.await(2, TimeUnit.SECONDS))
            // Change only the second saved algorithm after the endpoint-wide prompt snapshot.
            store.trust("example.test", "ssh-rsa", interveningRsa)
            repository.answerPrompt(
                requireNotNull(prompt).promptToken,
                HostIdentityDecision.ReplaceSavedKey,
            )
        }

        assertEquals(HostKeyRepository.CHANGED, result)
        assertEquals(2, store.entries("example.test").size)
        assertArrayEquals(
            interveningRsa,
            store.entries("example.test", "ssh-rsa").single().key,
        )
    }

    @Test
    fun nonDefaultPortUsesTheCanonicalEndpointAlias() = withStore { store ->
        var prompt: HostIdentityPrompt.FirstContact? = null
        val received = CountDownLatch(1)
        val repository = VerifyingHostKeyRepository(
            store = FileKnownHostTrustStore(store),
            endpoint = KnownHostEndpoint.create("EXAMPLE.test", 2222),
            onPrompt = {
                prompt = it as HostIdentityPrompt.FirstContact
                received.countDown()
            },
        )
        val result = verifyAsync(repository, ed25519Key(11)) {
            assertTrue(received.await(2, TimeUnit.SECONDS))
            val firstContact = requireNotNull(prompt)
            assertEquals("[example.test]:2222", firstContact.endpoint)
            repository.answerPrompt(firstContact.promptToken, HostIdentityDecision.TrustAndSave)
        }

        assertEquals(HostKeyRepository.OK, result)
        assertEquals(1, store.entries("[example.test]:2222").size)
        assertTrue(store.entries("example.test").isEmpty())
    }

    @Test
    fun concurrentFirstContactPromptsCannotSaveDifferentKeys() = withStore { store ->
        var firstPrompt: HostIdentityPrompt.FirstContact? = null
        var secondPrompt: HostIdentityPrompt.FirstContact? = null
        val promptsReceived = CountDownLatch(2)
        val first = repository(store) {
            firstPrompt = it as HostIdentityPrompt.FirstContact
            promptsReceived.countDown()
        }
        val second = repository(store) {
            secondPrompt = it as HostIdentityPrompt.FirstContact
            promptsReceived.countDown()
        }
        val results = Collections.synchronizedList(mutableListOf<Int>())
        val firstThread = thread { results += first.check("example.test", ed25519Key(12)) }
        val secondThread = thread { results += second.check("example.test", ed25519Key(13)) }

        try {
            assertTrue(promptsReceived.await(2, TimeUnit.SECONDS))
            assertNotEquals(
                requireNotNull(firstPrompt).promptToken,
                requireNotNull(secondPrompt).promptToken,
            )
            assertTrue(
                first.answerPrompt(
                    requireNotNull(firstPrompt).promptToken,
                    HostIdentityDecision.TrustAndSave,
                ),
            )
            assertTrue(
                second.answerPrompt(
                    requireNotNull(secondPrompt).promptToken,
                    HostIdentityDecision.TrustAndSave,
                ),
            )
            firstThread.join(2_000)
            secondThread.join(2_000)
        } finally {
            if (firstThread.isAlive) {
                first.cancelPrompt()
                firstThread.join(2_000)
            }
            if (secondThread.isAlive) {
                second.cancelPrompt()
                secondThread.join(2_000)
            }
        }

        assertFalse("First host-key verifier did not finish.", firstThread.isAlive)
        assertFalse("Second host-key verifier did not finish.", secondThread.isAlive)
        assertEquals(setOf(HostKeyRepository.OK, HostKeyRepository.CHANGED), results.toSet())
        assertEquals(1, store.entries("example.test").size)
    }

    @Test
    fun retiredRepositoryRejectsChecksWithoutPublishingOrAcceptingAnAnswer() = withStore { store ->
        val promptPublished = AtomicBoolean(false)
        val repository = repository(store) { promptPublished.set(true) }

        repository.retire()
        val result = repository.check("example.test", ed25519Key(31))

        assertEquals(HostKeyRepository.NOT_INCLUDED, result)
        assertFalse(promptPublished.get())
        assertFalse(repository.answerPrompt(1L, HostIdentityDecision.TrustOnce))
    }

    @Test
    fun retirementDuringStoreCheckPreventsPostClosePromptPublication() = withStore { store ->
        val delegate = FileKnownHostTrustStore(store)
        val checkEntered = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        val promptPublished = AtomicBoolean(false)
        val blockingStore = object : KnownHostTrustStore by delegate {
            override fun check(
                endpoint: KnownHostEndpoint,
                algorithm: String,
                key: ByteArray,
            ): KnownHostTrustCheck {
                checkEntered.countDown()
                check(releaseCheck.await(2, TimeUnit.SECONDS))
                return delegate.check(endpoint, algorithm, key)
            }
        }
        val repository = VerifyingHostKeyRepository(
            store = blockingStore,
            endpoint = KnownHostEndpoint.create("example.test", 22),
            onPrompt = { promptPublished.set(true) },
        )
        var result = HostKeyRepository.OK
        val verifier = thread { result = repository.check("example.test", ed25519Key(32)) }

        try {
            assertTrue(checkEntered.await(2, TimeUnit.SECONDS))
            repository.retire()
        } finally {
            releaseCheck.countDown()
        }
        verifier.join(2_000)

        assertFalse(verifier.isAlive)
        assertEquals(HostKeyRepository.NOT_INCLUDED, result)
        assertFalse(promptPublished.get())
    }

    @Test
    fun retirementResolvesPendingVerifierAndMakesItsPromptTokenStale() = withStore { store ->
        val promptReceived = CountDownLatch(1)
        var prompt: HostIdentityPrompt.FirstContact? = null
        val repository = repository(store) {
            prompt = it as HostIdentityPrompt.FirstContact
            promptReceived.countDown()
        }
        var result = HostKeyRepository.OK
        val verifier = thread { result = repository.check("example.test", ed25519Key(33)) }

        assertTrue(promptReceived.await(2, TimeUnit.SECONDS))
        repository.retire()
        verifier.join(2_000)

        assertFalse(verifier.isAlive)
        assertEquals(HostKeyRepository.NOT_INCLUDED, result)
        assertFalse(
            repository.answerPrompt(
                requireNotNull(prompt).promptToken,
                HostIdentityDecision.TrustAndSave,
            ),
        )
        assertTrue(store.entries("example.test").isEmpty())
    }

    private fun repository(
        store: KnownHostStore,
        onPrompt: (HostIdentityPrompt) -> Unit,
    ) = VerifyingHostKeyRepository(store, "example.test", onPrompt)

    private fun verifyAsync(
        repository: VerifyingHostKeyRepository,
        key: ByteArray,
        answer: () -> Unit,
    ): Int {
        var result = HostKeyRepository.NOT_INCLUDED
        val verifier = thread { result = repository.check("example.test", key) }
        try {
            answer()
        } catch (failure: Throwable) {
            repository.cancelPrompt()
            verifier.join(2_000)
            throw failure
        }
        verifier.join(2_000)
        if (verifier.isAlive) {
            repository.cancelPrompt()
            verifier.join(2_000)
        }
        assertTrue("Host-key verifier did not finish.", !verifier.isAlive)
        return result
    }

    private fun withStore(block: (KnownHostStore) -> Unit) {
        val directory = Files.createTempDirectory("host-key-repository-test").toFile()
        try {
            block(KnownHostStore(directory.resolve("known_hosts")))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun ed25519Key(fill: Byte): ByteArray {
        val algorithm = "ssh-ed25519".toByteArray(StandardCharsets.US_ASCII)
        val publicKey = ByteArray(32) { fill }
        return ByteBuffer.allocate(4 + algorithm.size + 4 + publicKey.size)
            .putInt(algorithm.size)
            .put(algorithm)
            .putInt(publicKey.size)
            .put(publicKey)
            .array()
    }

    private fun rsaKey(fill: Byte): ByteArray {
        val algorithm = "ssh-rsa".toByteArray(StandardCharsets.US_ASCII)
        val exponent = byteArrayOf(1, 0, 1)
        val modulus = ByteArray(64) { fill }
        return ByteBuffer.allocate(4 + algorithm.size + 4 + exponent.size + 4 + modulus.size)
            .putInt(algorithm.size)
            .put(algorithm)
            .putInt(exponent.size)
            .put(exponent)
            .putInt(modulus.size)
            .put(modulus)
            .array()
    }
}
