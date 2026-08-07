package com.yanjiyu.terminalspike.connection

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownHostStoreTest {
    @Test
    fun trustedKeysPersistAndReload() {
        val directory = Files.createTempDirectory("known-host-store-test").toFile()
        try {
            val file = directory.resolve("known_hosts")
            val key = byteArrayOf(1, 2, 3, 4)

            KnownHostStore(file).trust("example.test", "ssh-ed25519", key)
            key.fill(0)
            val reloaded = KnownHostStore(file).entries("example.test", "ssh-ed25519")

            assertEquals(1, reloaded.size)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), reloaded.single().key)
            assertTrue(file.readText().startsWith("example.test ssh-ed25519 "))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun removeOnlyDeletesTheRequestedAlgorithm() {
        val directory = Files.createTempDirectory("known-host-store-test").toFile()
        try {
            val file = directory.resolve("known_hosts")
            val store = KnownHostStore(file)
            store.trust("example.test", "ssh-ed25519", byteArrayOf(1))
            store.trust("example.test", "ecdsa-sha2-nistp256", byteArrayOf(2))

            store.remove("example.test", "ssh-ed25519")

            assertEquals(1, KnownHostStore(file).entries("example.test").size)
            assertEquals("ecdsa-sha2-nistp256", KnownHostStore(file).entries("example.test").single().algorithm)
        } finally {
            directory.deleteRecursively()
        }
    }
}
