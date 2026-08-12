package com.yanjiyu.terminalspike.core.security

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLoggerTest {
    @Test
    fun releasePolicyEmitsOnlyStableEventCode() {
        val messages = mutableListOf<String>()
        val logger = AppLogger(
            diagnosticsEnabled = false,
            sink = AppLogSink { _, _, message -> messages += message },
        )

        logger.error(
            event = AppLogEvent.CONNECTION_FAILED,
            error = IllegalStateException("password=hunter2"),
            detail = { "admin@private.example:22" },
        )

        assertEquals(listOf("connection_failed"), messages)
    }

    @Test
    fun debugPolicyRedactsSensitiveDiagnosticsAndAvoidsThrowableMessages() {
        val writes = mutableListOf<Triple<Int, String, String>>()
        val logger = AppLogger(
            diagnosticsEnabled = true,
            sink = AppLogSink { priority, tag, message -> writes += Triple(priority, tag, message) },
        )

        logger.warning(
            event = AppLogEvent.CONNECTION_FAILED,
            error = IllegalArgumentException("password=still-secret"),
            detail = { "ssh://person:secret@private.example:22 password=hunter2" },
        )

        assertEquals(1, writes.size)
        assertEquals(Log.WARN, writes.single().first)
        assertTrue(writes.single().third.contains("exception=IllegalArgumentException"))
        assertTrue(writes.single().third.contains("[REDACTED_CONNECTION_URI]"))
        assertTrue(writes.single().third.contains("password=[REDACTED]"))
        assertFalse(writes.single().third.contains("hunter2"))
        assertFalse(writes.single().third.contains("still-secret"))
        assertFalse(writes.single().third.contains("private.example"))
    }

    @Test
    fun redactorCoversKeysEndpointsAddressesAndMoshTokens() {
        val diagnostic = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            abcdef
            -----END OPENSSH PRIVATE KEY-----
            endpoint=tester@host.example:2200 peer=192.0.2.10:22 peer6=[2001:db8::1]:60000
            MOSH_KEY=super-secret UnknownHostException: hidden.example
        """.trimIndent()

        val redacted = SensitiveLogRedactor.redact(diagnostic)

        assertTrue(redacted.contains("[REDACTED_PRIVATE_KEY]"))
        assertTrue(redacted.contains("[REDACTED_ENDPOINT]"))
        assertTrue(redacted.contains("[REDACTED_IP]"))
        assertTrue(redacted.contains("mosh_key=[REDACTED]"))
        assertTrue(redacted.contains("[REDACTED_HOST]"))
        listOf("abcdef", "host.example", "192.0.2.10", "2001:db8::1", "super-secret", "hidden.example")
            .forEach { secret -> assertFalse(redacted.contains(secret)) }
    }

    @Test
    fun redactorCoversTruncatedAndUnterminatedPrivateKeys() {
        val keyMaterial = "secret-key-body-".repeat(300)
        val truncatedBlock = "-----BEGIN RSA PRIVATE KEY-----\n$keyMaterial\n" +
            "-----END RSA PRIVATE KEY-----"
        val unterminatedBlock = "prefix\n-----BEGIN OPENSSH PRIVATE KEY-----\nunterminated-secret"

        val truncated = SensitiveLogRedactor.redact(truncatedBlock)
        val unterminated = SensitiveLogRedactor.redact(unterminatedBlock)

        assertTrue(truncated.contains("[REDACTED_PRIVATE_KEY]"))
        assertFalse(truncated.contains("secret-key-body"))
        assertTrue(unterminated.contains("[REDACTED_PRIVATE_KEY]"))
        assertFalse(unterminated.contains("unterminated-secret"))
    }

    @Test
    fun redactorRemovesCompleteAuthorizationCredentials() {
        val credential = "eyJhbGciOiJIUzI1NiJ9.super-secret.signature"

        val redacted = SensitiveLogRedactor.redact(
            "request failed Authorization: Bearer $credential",
        )

        assertTrue(redacted.contains("authorization=[REDACTED]"))
        assertFalse(redacted.contains("Bearer"))
        assertFalse(redacted.contains(credential))
    }
}
