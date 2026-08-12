package com.yanjiyu.terminalspike.core.security.credential

import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidKeystoreCredentialStoreTest {
    @Test
    fun realKeystoreKeyIsNonExportableAndMissingReadNeverCreatesAReplacement() = runBlocking {
        val alias = "terminal-spike-credential-v2-test-${System.nanoTime()}"
        val keyStore = androidKeyStore()
        val records = MemoryCiphertextStore()
        val provider = AndroidKeystoreCredentialKeyProvider(alias)
        val store = AesGcmCredentialStore(records, provider)
        val reference = reference()
        val expected = "device-bound password".toByteArray()
        val consumed = expected.copyOf()

        try {
            assertFalse(keyStore.containsAlias(alias))
            store.saveAndWipe(reference, consumed)

            assertTrue(consumed.all { it == 0.toByte() })
            assertTrue(keyStore.containsAlias(alias))
            assertNull(keyStore.getKey(alias, null).encoded)
            assertFalse(requireNotNull(records.record).copyCiphertext().containsSubsequence(expected))

            var scopedPlaintext: ByteArray? = null
            store.withSecret(reference) { plaintext ->
                scopedPlaintext = plaintext
                assertArrayEquals(expected, plaintext)
            }
            assertTrue(requireNotNull(scopedPlaintext).all { it == 0.toByte() })

            keyStore.deleteEntry(alias)
            expectFailure<CredentialStoreException.KeyUnavailable> {
                store.withSecret(reference) { _ -> }
            }
            assertFalse(keyStore.containsAlias(alias))
        } finally {
            expected.fill(0)
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    @Test
    fun existingKeyOnlyLookupDoesNotCreateAlias() {
        val alias = "terminal-spike-credential-v2-read-test-${System.nanoTime()}"
        val keyStore = androidKeyStore()
        val provider = AndroidKeystoreCredentialKeyProvider(alias)
        try {
            assertFalse(keyStore.containsAlias(alias))
            assertNull(
                provider.getExistingDecryptionKey(AesGcmCredentialStore.KEY_VERSION),
            )
            assertFalse(keyStore.containsAlias(alias))
        } finally {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    private fun reference() = CredentialSecretReference(
        credentialId = CredentialId.parseCanonical("f44fd5b8-5444-4076-95bd-959b23ae5a17"),
        secretId = SecretId.parseCanonical("59a10970-e67e-4542-a26e-06babc7e1080"),
        kind = CredentialSecretKind.PASSWORD,
    )

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

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

    private suspend inline fun <reified T : Throwable> expectFailure(
        crossinline block: suspend () -> Unit,
    ): T = try {
        block()
        throw AssertionError("Expected ${T::class.java.simpleName}")
    } catch (error: Throwable) {
        if (error !is T) throw error
        error
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return (0..size - candidate.size).any { start ->
            candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
        }
    }
}
