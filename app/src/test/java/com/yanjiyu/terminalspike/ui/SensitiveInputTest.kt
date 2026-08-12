package com.yanjiyu.terminalspike.ui

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveInputTest {
    @Test
    fun deferredCompletionWipesBytesWhenCoroutineNeverClaimsThem() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6)
        val owner = DeferredSecretPair(first, second)

        owner.wipeUnclaimed()
        owner.wipeUnclaimed()

        assertArrayEquals(ByteArray(3), first)
        assertArrayEquals(ByteArray(3), second)
    }

    @Test
    fun completionHookCannotWipeBytesAfterCoroutineClaimsOwnership() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6)
        val owner = DeferredSecretPair(first, second)

        val claimed = owner.claim()
        owner.wipeUnclaimed()

        assertArrayEquals(byteArrayOf(1, 2, 3), claimed.first)
        assertArrayEquals(byteArrayOf(4, 5, 6), claimed.second)
        claimed.first?.fill(0)
        claimed.second?.fill(0)
    }

    @Test
    fun successWipesScratchCurrentAndRetiredAccumulatorBuffers() {
        val payload = ByteArray(20 * 1024) { index -> (index % 251 + 1).toByte() }
        val wiped = mutableListOf<ByteArray>()

        val result = ByteArrayInputStream(payload).readSensitiveBounded(32 * 1024) { buffer ->
            wiped += buffer
        }

        assertArrayEquals(payload, result)
        assertTrue(wiped.size >= 3)
        assertTrue(wiped.all { buffer -> buffer.all { it == 0.toByte() } })
    }

    @Test
    fun limitFailureWipesEveryTemporaryBuffer() {
        val wiped = mutableListOf<ByteArray>()

        val failure = runCatching {
            ByteArrayInputStream(ByteArray(33) { 7 }).readSensitiveBounded(32) { buffer ->
                wiped += buffer
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(wiped.isNotEmpty())
        assertTrue(wiped.all { buffer -> buffer.all { it == 0.toByte() } })
    }

    @Test
    fun readFailureWipesScratchAndAccumulatedBytes() {
        val wiped = mutableListOf<ByteArray>()
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Bulk reads are required")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (reads++ > 0) throw IOException("expected read failure")
                repeat(minOf(8, length)) { index -> buffer[offset + index] = (index + 1).toByte() }
                return minOf(8, length)
            }
        }

        val failure = runCatching {
            input.readSensitiveBounded(64) { buffer -> wiped += buffer }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(wiped.size >= 2)
        assertTrue(wiped.all { buffer -> buffer.all { it == 0.toByte() } })
    }
}
