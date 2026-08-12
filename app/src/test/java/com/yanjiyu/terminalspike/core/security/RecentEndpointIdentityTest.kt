package com.yanjiyu.terminalspike.core.security

import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentEndpointIdentityTest {
    private val provider = RecentEndpointIdentityProvider(
        JvmHmacProvider(ByteArray(32) { index -> (index + 1).toByte() }),
    )

    @Test
    fun dnsUsesIdnRootDotAndLocaleIndependentLowercaseCanonicalization() {
        val unicode = token(host = "BÜCHER.Example.")
        val ascii = token(host = "xn--bcher-kva.example")

        assertEquals(ascii, unicode)
        assertEquals(64, unicode.length)
        assertTrue(unicode.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(unicode.contains("example", ignoreCase = true))
        assertFalse(unicode.contains("alice", ignoreCase = true))
    }

    @Test
    fun ipv6UsesRawNetworkBytesAndScopedZoneRemainsCaseSensitive() {
        assertEquals(
            token(host = "[2001:DB8::1]"),
            token(host = "2001:db8:0:0:0:0:0:1"),
        )
        assertEquals(
            token(host = "::ffff:192.0.2.1"),
            token(host = "0:0:0:0:0:ffff:c000:201"),
        )
        assertNotEquals(
            token(host = "fe80::1%wlan0"),
            token(host = "fe80::1%WLAN0"),
        )
        assertNotEquals(token(host = "fe80::1"), token(host = "fe80::1%wlan0"))
    }

    @Test
    fun everyCanonicalEndpointComponentParticipatesInTheToken() {
        val baseline = token()

        assertNotEquals(baseline, token(protocol = ConnectionProtocol.MOSH))
        assertNotEquals(baseline, token(host = "other.example"))
        assertNotEquals(baseline, token(port = 2222))
        assertNotEquals(baseline, token(username = "Alice"))
        assertNotEquals(baseline, token(username = "a\u0301lice"))
    }

    @Test
    fun encodingIsVersionedAndStable() {
        assertEquals(
            "c8e6d60d74377faa9a8e872cb760827acbc105483d9cf39319c631fecb38596c",
            token(),
        )
    }

    @Test
    fun invalidOrAmbiguousLiteralsAreRejectedInsteadOfBecomingDnsIdentities() {
        listOf(
            "192.168.001.1",
            "256.0.0.1",
            "1.2.3",
            "2001::db8::1",
            "2001:db8:1",
            "[example.test]",
            "[2001:db8::1",
            "fe80::1%bad zone",
            "fe80::1%",
            "example.test..",
        ).forEach { host ->
            assertInvalid(host)
        }
    }

    @Test
    fun keyLifecycleStateIsReturnedWithoutChangingTokenShape() {
        val token = RecentEndpointIdentityProvider(
            RecentEndpointHmacProvider {
                RecentEndpointHmac(
                    bytes = ByteArray(32) { 0xab.toByte() },
                    keyState = RecentEndpointIdentityKeyState.REPLACED_INVALIDATED,
                )
            },
        ).create(ConnectionProtocol.SSH, "example.test", 22, "alice")

        assertEquals("ab".repeat(32), token.value)
        assertEquals(RecentEndpointIdentityKeyState.REPLACED_INVALIDATED, token.keyState)
    }

    private fun token(
        protocol: ConnectionProtocol = ConnectionProtocol.SSH,
        host: String = "example.test",
        port: Int = 22,
        username: String = "alice",
    ): String = provider.create(protocol, host, port, username).value

    private fun assertInvalid(host: String) {
        val result = runCatching { token(host = host) }
        assertTrue("Expected invalid endpoint host: $host", result.exceptionOrNull() is IllegalArgumentException)
    }

    private class JvmHmacProvider(key: ByteArray) : RecentEndpointHmacProvider {
        private val key = key.copyOf()

        override fun hmacSha256(message: ByteArray): RecentEndpointHmac {
            val digest = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(message)
            }
            return RecentEndpointHmac(digest, RecentEndpointIdentityKeyState.EXISTING)
        }
    }
}
