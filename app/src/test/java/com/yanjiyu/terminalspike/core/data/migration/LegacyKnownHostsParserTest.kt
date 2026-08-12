package com.yanjiyu.terminalspike.core.data.migration

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyKnownHostsParserTest {
    @Test
    fun parsesDefaultPortBracketedPortAndUnbracketedIpv6() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6)
        val source = """
            EXAMPLE.test ssh-ed25519 ${first.base64()}
            [other.test]:2200 rsa-sha2-512 ${second.base64()}
            2001:db8::1 ssh-ed25519 ${first.base64()}
        """.trimIndent()

        val result = LegacyKnownHostsParser.parse(source)

        assertTrue(result.warnings.isEmpty())
        assertEquals(3, result.records.size)
        assertEquals(22, result.records.single { it.canonicalHost == "example.test" }.port)
        assertEquals(2200, result.records.single { it.canonicalHost == "other.test" }.port)
        assertEquals(22, result.records.single { it.canonicalHost == "2001:db8::1" }.port)
    }

    @Test
    fun lastValidDuplicateWinsButDifferentAlgorithmsRemainDistinct() {
        val oldKey = byteArrayOf(1, 1, 1)
        val newKey = byteArrayOf(2, 2, 2)
        val rsaKey = byteArrayOf(3, 3, 3)
        val source = """
            host.test ssh-ed25519 ${oldKey.base64()}
            host.test ssh-ed25519 not-base64!
            host.test ssh-ed25519 ${newKey.base64()}
            host.test rsa-sha2-512 ${rsaKey.base64()}
        """.trimIndent()

        val result = LegacyKnownHostsParser.parse(source)

        assertEquals(2, result.records.size)
        assertEquals(1, result.warnings.size)
        assertEquals(LegacyKnownHostsWarningCode.INVALID_KEY, result.warnings.single().code)
        assertArrayEquals(
            newKey,
            result.records.single { it.algorithm == "ssh-ed25519" }.publicKey,
        )
        assertNotEquals(
            result.records[0].fingerprintSha256,
            result.records[1].fingerprintSha256,
        )
    }

    @Test
    fun importsCommaSeparatedPlainHostsAndRejectsPatternsWithoutEchoingThem() {
        val source = """
            one.test,two.test ssh-ed25519 ${byteArrayOf(7).base64()}
            *.private.test ssh-ed25519 ${byteArrayOf(8).base64()}
            [bad.test]:70000 ssh-ed25519 ${byteArrayOf(9).base64()}
            malformed
        """.trimIndent()

        val result = LegacyKnownHostsParser.parse(source)

        assertEquals(listOf("one.test", "two.test"), result.records.map { it.canonicalHost })
        assertEquals(
            listOf(
                LegacyKnownHostsWarningCode.UNSUPPORTED_HOST_PATTERN,
                LegacyKnownHostsWarningCode.INVALID_PORT,
                LegacyKnownHostsWarningCode.MALFORMED_LINE,
            ),
            result.warnings.map { it.code },
        )
    }

    @Test
    fun invalidTargetHostsAndAlgorithmsAreDroppedWithTypedWarnings() {
        val key = byteArrayOf(10, 11, 12).base64()
        val oversizedAlgorithm = "a".repeat(65)
        val source = """
            good.test ssh-ed25519 $key
            bad_.test ssh-ed25519 $key
            256.1.1.1 ssh-ed25519 $key
            good.test ssh/ed25519 $key
            good.test $oversizedAlgorithm $key
        """.trimIndent()

        val result = LegacyKnownHostsParser.parse(source)

        assertEquals(listOf("good.test"), result.records.map { it.canonicalHost })
        assertEquals(
            listOf(
                LegacyKnownHostsWarningCode.INVALID_HOST,
                LegacyKnownHostsWarningCode.INVALID_HOST,
                LegacyKnownHostsWarningCode.INVALID_ALGORITHM,
                LegacyKnownHostsWarningCode.INVALID_ALGORITHM,
            ),
            result.warnings.map { it.code },
        )
        assertEquals(listOf(2, 3, 4, 5), result.warnings.map { it.lineNumber })
    }

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)
}
