package com.yanjiyu.terminalspike.core.data.migration

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyIdsTest {
    @Test
    fun eachLegacyTypeHasAnIndependentStableNamespace() {
        val legacyId = 7L
        val identifiers = setOf(
            LegacyIds.hostProfile(legacyId),
            LegacyIds.snippet(legacyId),
            LegacyIds.keyIdentity(legacyId),
            LegacyIds.passwordCredential(legacyId),
            LegacyIds.passwordSecret(legacyId),
            LegacyIds.privateKeySecret(legacyId),
        )

        assertEquals(6, identifiers.size)
        identifiers.forEach { identifier ->
            assertEquals(identifier.lowercase(), identifier)
            assertEquals(5, UUID.fromString(identifier).version())
        }
    }

    @Test
    fun resultsAreDeterministicAndCanonicalizeOnlyHostCase() {
        assertEquals("2e4d5783-58b2-5367-bcc6-f2eec7f4ee68", LegacyIds.hostProfile(42))
        assertEquals(
            LegacyIds.knownHost("EXAMPLE.test", 2222, "ssh-ed25519"),
            LegacyIds.knownHost("example.test", 2222, "ssh-ed25519"),
        )
        assertEquals(
            "22e045c6-988e-5cd5-9999-50ca6363867d",
            LegacyIds.knownHost("example.test", 2222, "ssh-ed25519"),
        )
        assertNotEquals(
            LegacyIds.knownHost("example.test", 2222, "ssh-ed25519"),
            LegacyIds.knownHost("example.test", 2222, "rsa-sha2-512"),
        )
    }

    @Test
    fun fixedDefaultsAreDistinctVersionFiveUuids() {
        assertEquals("df558cdb-05fb-50f3-baf9-e7dd6e911ce5", LegacyIds.defaultTerminalProfile)
        assertEquals("f23f85fd-3122-5f8b-b28a-d0320f402866", LegacyIds.defaultKeyboardProfile)
        assertNotEquals(LegacyIds.defaultTerminalProfile, LegacyIds.defaultKeyboardProfile)
        assertTrue(UUID.fromString(LegacyIds.defaultTerminalProfile).version() == 5)
        assertTrue(UUID.fromString(LegacyIds.defaultKeyboardProfile).version() == 5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveLegacyIdIsRejected() {
        LegacyIds.hostProfile(0)
    }
}
