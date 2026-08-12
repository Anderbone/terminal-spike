package com.yanjiyu.terminalspike.core.data.credential

import com.yanjiyu.terminalspike.core.data.db.EncryptedSecretEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedSecretMappingTest {
    @Test
    fun readyEntityRoundTripsWithTimestampsAndDefensivePayloads() {
        val entity = readyEntity()

        val stored = entity.toStoredEncryptedCredentialRecord()

        assertEquals(CREATED_AT, stored.createdAtEpochMillis)
        assertEquals(UPDATED_AT, stored.updatedAtEpochMillis)
        entity.nonce?.fill(0)
        entity.ciphertext?.fill(0)
        assertArrayEquals(NONCE, stored.encrypted.copyNonce())
        assertArrayEquals(CIPHERTEXT, stored.encrypted.copyCiphertext())

        val restored = stored.encrypted.toReadyEntity(CREATED_AT, UPDATED_AT)
        val exposedNonce = stored.encrypted.copyNonce().apply { fill(9) }
        val exposedCiphertext = stored.encrypted.copyCiphertext().apply { fill(9) }
        assertTrue(exposedNonce.all { it == 9.toByte() })
        assertTrue(exposedCiphertext.all { it == 9.toByte() })
        assertArrayEquals(NONCE, restored.nonce)
        assertArrayEquals(CIPHERTEXT, restored.ciphertext)
        assertEquals("ready", restored.stateCode)
        assertNull(restored.failureCode)
        assertNull(restored.legacyId)
    }

    @Test
    fun retainedLegacyFailureIsTypedAndKeepsRecoveryMetadata() {
        val entity = readyEntity().copy(
            envelopeVersion = 0,
            keyVersion = 0,
            nonce = null,
            ciphertext = null,
            stateCode = "legacy_unavailable",
            failureCode = "legacy_key_unavailable",
            legacyId = "legacy-password-4",
        )

        val error = assertThrows(PersistedCredentialException.LegacyUnavailable::class.java) {
            entity.toStoredEncryptedCredentialRecord()
        }

        assertEquals(SECRET_ID, error.secretId.value)
        assertEquals("legacy_key_unavailable", error.failureCode)
        assertEquals("legacy-password-4", error.legacyId)
    }

    @Test
    fun retainedLegacyFailureRejectsInventedEnvelopeVersions() {
        val entity = readyEntity().copy(
            keyVersion = 0,
            nonce = null,
            ciphertext = null,
            stateCode = "legacy_unavailable",
            failureCode = "legacy_key_unavailable",
            legacyId = "legacy-password-4",
        )

        val error = assertThrows(PersistedCredentialException.Malformed::class.java) {
            entity.toStoredEncryptedCredentialRecord()
        }

        assertEquals(PersistedCredentialMalformedReason.STATE_METADATA, error.reason)
    }

    @Test
    fun nullableReadyPayloadAndMalformedStateMetadataHaveTypedReasons() {
        listOf(
            readyEntity().copy(nonce = null) to PersistedCredentialMalformedReason.NONCE,
            readyEntity().copy(ciphertext = null) to PersistedCredentialMalformedReason.CIPHERTEXT,
            readyEntity().copy(stateCode = "unknown") to PersistedCredentialMalformedReason.STATE,
            readyEntity().copy(failureCode = "unexpected") to
                PersistedCredentialMalformedReason.STATE_METADATA,
            readyEntity().copy(id = SECRET_ID.uppercase()) to
                PersistedCredentialMalformedReason.RECORD_ID,
            readyEntity().copy(kindCode = "PASSWORD") to PersistedCredentialMalformedReason.KIND,
        ).forEach { (entity, expectedReason) ->
            val error = assertThrows(PersistedCredentialException.Malformed::class.java) {
                entity.toStoredEncryptedCredentialRecord()
            }
            assertEquals(expectedReason, error.reason)
        }
    }

    @Test
    fun readyRowsAndNewWritesRequireTheCurrentCryptoVersionsExactly() {
        listOf(
            readyEntity().copy(envelopeVersion = 1) to
                PersistedCredentialMalformedReason.ENVELOPE_VERSION,
            readyEntity().copy(envelopeVersion = 3) to
                PersistedCredentialMalformedReason.ENVELOPE_VERSION,
            readyEntity().copy(keyVersion = 1) to PersistedCredentialMalformedReason.KEY_VERSION,
            readyEntity().copy(keyVersion = 3) to PersistedCredentialMalformedReason.KEY_VERSION,
        ).forEach { (entity, expectedReason) ->
            val readFailure = assertThrows(PersistedCredentialException.Malformed::class.java) {
                entity.toStoredEncryptedCredentialRecord()
            }
            assertEquals(expectedReason, readFailure.reason)

            val record = com.yanjiyu.terminalspike.core.security.credential.EncryptedCredentialRecord(
                secretId = entity.id,
                kindCode = entity.kindCode,
                envelopeVersion = entity.envelopeVersion,
                keyVersion = entity.keyVersion,
                nonce = requireNotNull(entity.nonce),
                ciphertext = requireNotNull(entity.ciphertext),
            )
            val writeFailure = assertThrows(PersistedCredentialException.Malformed::class.java) {
                record.toReadyEntity(CREATED_AT, UPDATED_AT)
            }
            assertEquals(expectedReason, writeFailure.reason)
        }
    }

    private fun readyEntity() = EncryptedSecretEntity(
        id = SECRET_ID,
        kindCode = "password",
        envelopeVersion = 2,
        keyVersion = 2,
        nonce = NONCE.copyOf(),
        ciphertext = CIPHERTEXT.copyOf(),
        stateCode = "ready",
        failureCode = null,
        legacyId = null,
        createdAtEpochMillis = CREATED_AT,
        updatedAtEpochMillis = UPDATED_AT,
    )

    private companion object {
        const val SECRET_ID = "89a95a82-5149-43bc-80b1-14b1ca457f99"
        const val CREATED_AT = 1_000L
        const val UPDATED_AT = 2_000L
        val NONCE = ByteArray(12) { (it + 1).toByte() }
        val CIPHERTEXT = ByteArray(32) { (it + 31).toByte() }
    }
}
