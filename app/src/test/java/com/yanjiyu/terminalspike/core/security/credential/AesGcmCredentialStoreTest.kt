package com.yanjiyu.terminalspike.core.security.credential

import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AesGcmCredentialStoreTest {
    @Test
    fun encryptAndWipeReturnsDefensiveRecordWithoutPersistingIt() = runTest {
        val records = MemoryCiphertextStore()
        val store = AesGcmCredentialStore(records, FixedKeyProvider())
        val plaintext = "transactional password".toByteArray()

        val encrypted = store.encryptAndWipe(reference(), plaintext)

        assertAllZero(plaintext)
        assertNull(records.record)
        val firstNonce = encrypted.copyNonce()
        val firstCiphertext = encrypted.copyCiphertext()
        firstNonce.fill(0)
        firstCiphertext.fill(0)
        assertTrue(encrypted.copyNonce().any { it != 0.toByte() })
        assertTrue(encrypted.copyCiphertext().any { it != 0.toByte() })
    }

    @Test
    fun roundTripPersistsOnlyCiphertextAndWipesBothPlaintextScopes() = runTest {
        val records = MemoryCiphertextStore()
        val store = AesGcmCredentialStore(records, FixedKeyProvider())
        val reference = reference()
        val expected = "correct horse battery staple".toByteArray()
        val consumed = expected.copyOf()

        store.saveAndWipe(reference, consumed)

        assertAllZero(consumed)
        val record = requireNotNull(records.record)
        assertEquals(reference.secretId.value, record.secretId)
        assertEquals(CredentialSecretKind.PASSWORD.wireCode, record.kindCode)
        assertEquals(AesGcmCredentialStore.ENVELOPE_VERSION, record.envelopeVersion)
        assertEquals(AesGcmCredentialStore.KEY_VERSION, record.keyVersion)
        assertEquals(AesGcmCredentialStore.NONCE_BYTES, record.copyNonce().size)
        assertEquals(expected.size + GCM_TAG_BYTES, record.copyCiphertext().size)
        assertFalse(record.copyCiphertext().containsSubsequence(expected))

        var scopedArray: ByteArray? = null
        val result = store.withSecret(reference) { plaintext ->
            scopedArray = plaintext
            assertArrayEquals(expected, plaintext)
            plaintext.decodeToString()
        }

        assertEquals(expected.decodeToString(), result)
        assertAllZero(requireNotNull(scopedArray))
        expected.fill(0)
    }

    @Test
    fun capturedCiphertextDecryptsItsOwnRevisionWithoutRereadingLiveStorage() = runTest {
        val records = MemoryCiphertextStore()
        val store = AesGcmCredentialStore(records, FixedKeyProvider())
        val reference = reference()
        store.saveAndWipe(reference, "revision A".toByteArray())
        val capturedRevision = requireNotNull(records.record)
        store.saveAndWipe(reference, "revision B".toByteArray())

        var scopedArray: ByteArray? = null
        val restored = store.withEncryptedRecord(reference, capturedRevision) { plaintext ->
            scopedArray = plaintext
            plaintext.decodeToString()
        }

        assertEquals("revision A", restored)
        assertAllZero(requireNotNull(scopedArray))
        assertEquals("revision B", store.withSecret(reference) { it.decodeToString() })
    }

    @Test
    fun canonicalAadBindsApplicationVersionCredentialSecretAndKind() = runTest {
        val originalReference = reference()
        val aad = CredentialAssociatedData.encode(originalReference).decodeToString()
        assertEquals(
            listOf(
                "com.yanjiyu.terminalspike",
                "credential-store",
                "2",
                originalReference.credentialId.value,
                originalReference.secretId.value,
                "password",
            ).joinToString("\u0000"),
            aad,
        )

        val records = MemoryCiphertextStore()
        val store = AesGcmCredentialStore(records, FixedKeyProvider())
        store.saveAndWipe(originalReference, "bound secret".toByteArray())

        val differentCredential = originalReference.copy(
            credentialId = CredentialId.parseCanonical("fba5c145-aac9-4a86-a0e9-018ded02cb56"),
        )
        expectFailure<CredentialStoreException.Tampered> {
            store.withSecret(differentCredential) { _ -> }
        }

        val originalRecord = requireNotNull(records.record)
        val differentKindReference = originalReference.copy(kind = CredentialSecretKind.PRIVATE_KEY)
        records.record = originalRecord.rebuilt(
            kindCode = CredentialSecretKind.PRIVATE_KEY.wireCode,
        )
        expectFailure<CredentialStoreException.Tampered> {
            store.withSecret(differentKindReference) { _ -> }
        }

        val keyboardInteractiveReference = originalReference.copy(
            kind = CredentialSecretKind.KEYBOARD_INTERACTIVE,
        )
        records.record = originalRecord.rebuilt(
            kindCode = CredentialSecretKind.KEYBOARD_INTERACTIVE.wireCode,
        )
        expectFailure<CredentialStoreException.Tampered> {
            store.withSecret(keyboardInteractiveReference) { _ -> }
        }

        val differentSecretReference = originalReference.copy(
            secretId = SecretId.parseCanonical("cfe7dcaa-47d8-4de2-90b0-416515ac4066"),
        )
        records.record = originalRecord.rebuilt(secretId = differentSecretReference.secretId.value)
        expectFailure<CredentialStoreException.Tampered> {
            store.withSecret(differentSecretReference) { _ -> }
        }
    }

    @Test
    fun missingMalformedTamperedAndUnavailableKeyHaveDistinctTypes() = runTest {
        val reference = reference()
        val records = MemoryCiphertextStore()
        val keyProvider = FixedKeyProvider()
        val store = AesGcmCredentialStore(records, keyProvider)

        expectFailure<CredentialStoreException.Missing> {
            store.withSecret(reference) { _ -> }
        }

        store.saveAndWipe(reference, "saved password".toByteArray())
        val valid = requireNotNull(records.record)

        records.record = valid.rebuilt(envelopeVersion = 99)
        val malformed = expectFailure<CredentialStoreException.Malformed> {
            store.withSecret(reference) { _ -> }
        }
        assertEquals(MalformedCredentialReason.ENVELOPE_VERSION, malformed.reason)

        val tamperedCiphertext = valid.copyCiphertext().apply { this[lastIndex] = last().inc() }
        records.record = valid.rebuilt(ciphertext = tamperedCiphertext)
        expectFailure<CredentialStoreException.Tampered> {
            store.withSecret(reference) { _ -> }
        }

        records.record = valid
        keyProvider.decryptionKey = null
        expectFailure<CredentialStoreException.KeyUnavailable> {
            store.withSecret(reference) { _ -> }
        }
        assertEquals(0, keyProvider.createCallsAfterSave)
    }

    @Test
    fun strictVersionNonceAndSizeChecksRunBeforeDecryption() = runTest {
        val reference = reference()
        val records = MemoryCiphertextStore()
        val store = AesGcmCredentialStore(records, FixedKeyProvider())
        store.saveAndWipe(reference, "saved password".toByteArray())
        val valid = requireNotNull(records.record)

        listOf(
            valid.rebuilt(secretId = "not-a-uuid") to MalformedCredentialReason.RECORD_ID,
            valid.rebuilt(kindCode = "unknown") to MalformedCredentialReason.KIND,
            valid.rebuilt(keyVersion = 1) to MalformedCredentialReason.KEY_VERSION,
            valid.rebuilt(nonce = ByteArray(11)) to MalformedCredentialReason.NONCE,
            valid.rebuilt(ciphertext = ByteArray(GCM_TAG_BYTES)) to
                MalformedCredentialReason.CIPHERTEXT_SIZE,
        ).forEach { (record, expectedReason) ->
            records.record = record
            val error = expectFailure<CredentialStoreException.Malformed> {
                store.withSecret(reference) { _ -> }
            }
            assertEquals(expectedReason, error.reason)
        }

        val empty = ByteArray(0)
        val emptyError = expectFailure<CredentialStoreException.Malformed> {
            store.saveAndWipe(reference, empty)
        }
        assertEquals(MalformedCredentialReason.PLAINTEXT_SIZE, emptyError.reason)
        assertAllZero(empty)

        val oversized = ByteArray(CredentialSecretKind.PASSWORD.maximumPlaintextBytes + 1) { 7 }
        expectFailure<CredentialStoreException.Malformed> {
            store.saveAndWipe(reference, oversized)
        }
        assertAllZero(oversized)
    }

    @Test
    fun scopedPlaintextIsWipedWhenCallbackThrows() = runTest {
        val records = MemoryCiphertextStore()
        val store = AesGcmCredentialStore(records, FixedKeyProvider())
        val reference = reference(kind = CredentialSecretKind.KEY_PASSPHRASE)
        store.saveAndWipe(reference, "passphrase".toByteArray())
        var exposed: ByteArray? = null

        expectFailure<ExpectedCallbackException> {
            store.withSecret(reference) { plaintext ->
                exposed = plaintext
                throw ExpectedCallbackException()
            }
        }

        assertAllZero(requireNotNull(exposed))
    }

    @Test
    fun identifiersRequireCanonicalLowercaseUuidText() {
        assertEquals(
            "ce13ab93-7597-4d9c-b66a-5ca16ea7a535",
            CredentialId.parseCanonical("ce13ab93-7597-4d9c-b66a-5ca16ea7a535").value,
        )
        assertEquals(
            "89a95a82-5149-43bc-80b1-14b1ca457f99",
            SecretId.from(UUID.fromString("89a95a82-5149-43bc-80b1-14b1ca457f99")).value,
        )
        assertThrows(IllegalArgumentException::class.java) {
            CredentialId.parseCanonical("CE13AB93-7597-4D9C-B66A-5CA16EA7A535")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SecretId.parseCanonical("not-a-uuid")
        }
    }

    private fun reference(
        kind: CredentialSecretKind = CredentialSecretKind.PASSWORD,
    ) = CredentialSecretReference(
        credentialId = CredentialId.parseCanonical("ce13ab93-7597-4d9c-b66a-5ca16ea7a535"),
        secretId = SecretId.parseCanonical("89a95a82-5149-43bc-80b1-14b1ca457f99"),
        kind = kind,
    )

    private class MemoryCiphertextStore : CredentialCiphertextStore {
        var record: EncryptedCredentialRecord? = null

        override suspend fun read(secretId: SecretId): EncryptedCredentialRecord? = record

        override suspend fun write(record: EncryptedCredentialRecord) {
            this.record = record
        }

        override suspend fun delete(secretId: SecretId): Boolean {
            val existed = record != null
            record = null
            return existed
        }
    }

    private class FixedKeyProvider : CredentialKeyProvider {
        private val encryptionKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        var decryptionKey: SecretKey? = encryptionKey
        var createCallsAfterSave = 0
        private var encryptionCalls = 0

        override fun getOrCreateEncryptionKey(keyVersion: Int): SecretKey {
            if (encryptionCalls > 0) createCallsAfterSave += 1
            encryptionCalls += 1
            return encryptionKey
        }

        override fun getExistingDecryptionKey(keyVersion: Int): SecretKey? = decryptionKey
    }

    private fun EncryptedCredentialRecord.rebuilt(
        secretId: String = this.secretId,
        kindCode: String = this.kindCode,
        envelopeVersion: Int = this.envelopeVersion,
        keyVersion: Int = this.keyVersion,
        nonce: ByteArray = copyNonce(),
        ciphertext: ByteArray = copyCiphertext(),
    ) = EncryptedCredentialRecord(
        secretId = secretId,
        kindCode = kindCode,
        envelopeVersion = envelopeVersion,
        keyVersion = keyVersion,
        nonce = nonce,
        ciphertext = ciphertext,
    )

    private suspend inline fun <reified T : Throwable> expectFailure(
        crossinline block: suspend () -> Unit,
    ): T = try {
        block()
        throw AssertionError("Expected ${T::class.java.simpleName}")
    } catch (error: Throwable) {
        if (error !is T) throw error
        error
    }

    private fun assertAllZero(bytes: ByteArray) {
        assertTrue("Expected mutable secret buffer to be wiped", bytes.all { it == 0.toByte() })
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return (0..size - candidate.size).any { start ->
            candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
        }
    }

    private class ExpectedCallbackException : Exception()

    companion object {
        private const val GCM_TAG_BYTES = 16
    }
}
