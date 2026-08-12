package com.yanjiyu.terminalspike.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SshFailureRedactionTest {
    @Test
    fun knownFailureCategoriesNeverEchoRawEndpointOrCredentialText() {
        val fixtures = mapOf(
            "Auth fail for admin@private.example password=hunter2" to ExpectedFailure(
                "SSH authentication failed.",
                ConnectionFailureDisposition.TERMINAL,
            ),
            "Auth cancel for admin@private.example" to ExpectedFailure(
                "SSH authentication failed.",
                ConnectionFailureDisposition.TERMINAL,
            ),
            "java.net.UnknownHostException: hidden.example" to ExpectedFailure(
                "SSH host could not be resolved.",
                ConnectionFailureDisposition.TRANSIENT_TRANSPORT,
            ),
            "Connection refused: 192.0.2.10:22" to ExpectedFailure(
                "SSH connection was refused.",
                ConnectionFailureDisposition.TRANSIENT_TRANSPORT,
            ),
            "Algorithm negotiation fail for hidden.example" to
                ExpectedFailure(
                    "No compatible SSH security algorithm was found.",
                    ConnectionFailureDisposition.TERMINAL,
                ),
            "Socket timeout while connecting to hidden.example" to ExpectedFailure(
                "SSH connection timed out.",
                ConnectionFailureDisposition.TRANSIENT_TRANSPORT,
            ),
        )

        fixtures.forEach { (raw, expected) ->
            val safe = safeJschFailure(raw)
            assertEquals(expected.message, safe.message)
            assertEquals(expected.disposition, safe.disposition)
            listOf("admin", "private.example", "hunter2", "hidden.example", "192.0.2.10")
                .forEach { sensitive -> assertFalse(safe.message.contains(sensitive)) }
        }
    }

    @Test
    fun unknownOrMissingDetailsFailClosed() {
        assertEquals("SSH connection failed.", safeJschFailureMessage(null))
        assertEquals("SSH connection failed.", safeJschFailureMessage("secret internal detail"))
        assertEquals(ConnectionFailureDisposition.TERMINAL, safeJschFailure(null).disposition)
        assertEquals(
            ConnectionFailureDisposition.TERMINAL,
            safeJschFailure("secret internal detail").disposition,
        )
    }

    private data class ExpectedFailure(
        val message: String,
        val disposition: ConnectionFailureDisposition,
    )
}
