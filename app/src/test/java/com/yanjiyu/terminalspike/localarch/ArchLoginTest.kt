package com.yanjiyu.terminalspike.localarch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArchLoginTest {
    @Test fun deviceCodesSurviveEveryTransportSplitAndAnsiColour() {
        for (output in listOf(
            "! First copy your one-time code: \u001b[1mABCD-1234\u001b[0m\r\n",
            "Open https://auth.openai.com/codex/device\r\nEnter this code:\r\n    ABCD-1234\r\n",
        )) {
            for (split in 0..output.length) {
                val parser = ArchLoginCodeParser()
                val first = parser.accept(output.take(split))
                assertEquals("ABCD-1234", parser.accept(output.drop(split)) ?: first)
            }
            val parser = ArchLoginCodeParser()
            assertEquals("ABCD-1234", output.map { parser.accept(it.toString()) }.last())
        }
    }

    @Test fun codexLongerCodeIsNeverPublishedAsATruncatedGithubCode() {
        val parser = ArchLoginCodeParser()
        assertNull(parser.accept("Enter this one-time code\n  CODE-1234"))
        assertNull(parser.accept("5"))
        assertEquals("CODE-12345", parser.accept("\n"))
        val oneByte = ArchLoginCodeParser()
        val results = "Enter this one-time code\n  CODE-12345\n".map { oneByte.accept(it.toString()) }
        assertEquals(listOf("CODE-12345"), results.filterNotNull())
    }

    @Test fun failuresAndTokensAreNotDeviceCodes() {
        val parser = ArchLoginCodeParser()
        assertNull(parser.accept("Error: network unavailable; ghp_secret; access_token=secret"))
        assertNull(parser.accept("x".repeat(20_000)))
        assertEquals("ABCD-1234", parser.accept("\nABCD-1234\n"))
    }

    @Test fun successRequiresZeroExitAndCodeAloneIsNotSuccess() = runBlocking {
        for (status in listOf(0, 1)) {
            val codes = mutableListOf<String>()
            val pty = FakePty(status)
            assertEquals(status == 0, pty.use { runArchLogin(it, codes::add) })
            assertEquals(listOf("ABCD-1234"), codes)
            assertTrue(pty.closed)
        }
    }

    @Test fun cancellationClosesProcessWithoutReportingSuccess() = runBlocking {
        val pty = FakePty(0)
        try {
            pty.use { runArchLogin(it) { throw CancellationException() } }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(pty.closed)
        }
    }

    private class FakePty(private val status: Int) : LocalPty {
        var closed = false
        var emitted = false
        override fun read(buffer: ByteArray): Int {
            if (emitted) return -1
            emitted = true
            val bytes = "Code: ABCD-1234\n".toByteArray()
            bytes.copyInto(buffer)
            return bytes.size
        }
        override fun exitCode() = status
        override fun close() { closed = true }
        override fun write(bytes: ByteArray) = Unit
        override fun resize(columns: Int, rows: Int) = Unit
    }
}
