package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.HostKeyRepository
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyingHostKeyRepositoryTest {
    @Test
    fun unknownKeyRequiresConfirmationBeforeItIsStored() {
        val directory = Files.createTempDirectory("host-key-repository-test").toFile()
        try {
            val store = KnownHostStore(directory.resolve("known_hosts"))
            val promptReceived = CountDownLatch(1)
            var prompt: HostKeyPrompt? = null
            val repository = VerifyingHostKeyRepository(store, "example.test") {
                prompt = it
                promptReceived.countDown()
            }
            var result = HostKeyRepository.NOT_INCLUDED
            val verifier = thread {
                result = repository.check("example.test", ed25519Key(7))
            }

            assertTrue(promptReceived.await(2, TimeUnit.SECONDS))
            assertEquals("example.test", prompt?.host)
            assertTrue(prompt?.sha256Fingerprint?.startsWith("SHA256:") == true)
            repository.answerPrompt(true)
            verifier.join(2_000)

            assertEquals(HostKeyRepository.OK, result)
            assertEquals(1, store.entries("example.test", "ssh-ed25519").size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun changedKeyOfKnownAlgorithmIsBlockedWithoutPrompting() {
        val directory = Files.createTempDirectory("host-key-repository-test").toFile()
        try {
            val store = KnownHostStore(directory.resolve("known_hosts"))
            store.trust("example.test", "ssh-ed25519", ed25519Key(1))
            var prompted = false
            val repository = VerifyingHostKeyRepository(store, "example.test") { prompted = true }

            val result = repository.check("example.test", ed25519Key(2))

            assertEquals(HostKeyRepository.CHANGED, result)
            assertEquals(HostKeyFailure.CHANGED, repository.failure)
            assertEquals(false, prompted)
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
}
