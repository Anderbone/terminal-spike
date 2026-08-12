package com.yanjiyu.terminalspike.core.data.repository

import com.yanjiyu.terminalspike.core.data.db.SshCredentialEntity
import com.yanjiyu.terminalspike.core.data.db.SshKeyIdentityEntity
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialEntityMappersTest {
    @Test
    fun everyCredentialKindRoundTripsOpaqueReadyOrUnavailableSecretReferences() {
        val rows = listOf(
            credentialRow(kind = "password", secretId = PASSWORD_SECRET_ID),
            credentialRow(
                id = PRIVATE_KEY_CREDENTIAL_ID,
                kind = "private_key",
                secretId = PASSPHRASE_SECRET_ID,
                keyIdentityId = IDENTITY_ID,
            ),
            credentialRow(
                id = KEYBOARD_CREDENTIAL_ID,
                kind = "keyboard_interactive",
                secretId = KEYBOARD_SECRET_ID,
            ),
        )

        val authentications = rows.map { row ->
            val domain = row.toDomainModel()
            assertEquals(row, domain.toEntity())
            domain.authentication
        }

        assertEquals(SshAuthentication.Password(PASSWORD_SECRET_ID), authentications[0])
        assertEquals(
            SshAuthentication.PrivateKey(IDENTITY_ID, PASSPHRASE_SECRET_ID),
            authentications[1],
        )
        assertEquals(
            SshAuthentication.KeyboardInteractive(KEYBOARD_SECRET_ID),
            authentications[2],
        )
    }

    @Test
    fun promptOnlyCredentialReferencesRoundTripAsNull() {
        val password = credentialRow(kind = "password", secretId = null).toDomainModel()
        val keyboard = credentialRow(
            id = KEYBOARD_CREDENTIAL_ID,
            kind = "keyboard_interactive",
            secretId = null,
        ).toDomainModel()

        assertNull((password.authentication as SshAuthentication.Password).secretReferenceId)
        assertNull(
            (keyboard.authentication as SshAuthentication.KeyboardInteractive)
                .reusableResponseSecretReferenceId,
        )
    }

    @Test
    fun corruptCredentialReferenceCombinationsAndCodesNeverDefault() {
        listOf(
            credentialRow(kind = "password", keyIdentityId = IDENTITY_ID),
            credentialRow(kind = "private_key", keyIdentityId = null),
            credentialRow(kind = "future_authentication"),
        ).forEach { row ->
            val failure = assertThrows(CorruptStoredDataException::class.java) {
                row.toDomainModel()
            }
            assertEquals(RepositoryRecordType.SSH_CREDENTIAL, failure.recordType)
            assertEquals(CREDENTIAL_ID, failure.recordKey)
        }
    }

    @Test
    fun keyIdentityUsesCanonicalOpenSshTextWhilePersistingOnlyTheBinaryPublicBlob() {
        val keyBlob = keyBlob()
        val row = identityRow(publicKey = keyBlob)

        val domain = row.toDomainModel()
        val restored = domain.toEntity()

        assertEquals(
            "ssh-ed25519 ${Base64.getEncoder().encodeToString(keyBlob)}",
            domain.publicKey,
        )
        assertArrayEquals(keyBlob, restored.publicKey)
        assertEquals(row.copy(publicKey = null), restored.copy(publicKey = null))
    }

    @Test
    fun retainedUnavailableImportedIdentityCanKeepANullPublicKey() {
        val row = identityRow(publicKey = null)

        val domain = row.toDomainModel()

        assertNull(domain.publicKey)
        assertNull(domain.toEntity().publicKey)
        assertEquals(PRIVATE_KEY_SECRET_ID, domain.privateKeySecretReferenceId)
    }

    @Test
    fun malformedOrMismatchedPublicKeyTextIsRejectedBeforePersistence() {
        val identity = identityRow().toDomainModel()
        listOf(
            "ssh-rsa AQID",
            "ssh-ed25519 not-base64!",
            "ssh-ed25519",
            "ssh-ed25519 AQID comment",
        ).forEach { publicKey ->
            val failure = assertThrows(InvalidRepositoryInputException::class.java) {
                identity.copy(publicKey = publicKey).toEntity()
            }
            assertEquals(RepositoryRecordType.SSH_KEY_IDENTITY, failure.recordType)
        }
    }

    @Test
    fun generatedStoredIdentityWithoutPublicKeyIsReportedAsCorrupt() {
        val failure = assertThrows(CorruptStoredDataException::class.java) {
            identityRow(publicKey = null, provenance = "generated").toDomainModel()
        }

        assertEquals(RepositoryRecordType.SSH_KEY_IDENTITY, failure.recordType)
        assertEquals(IDENTITY_ID, failure.recordKey)
    }

    @Test
    fun storedFingerprintAndEmbeddedAlgorithmMustMatchTheExactPublicBlob() {
        val wrongFingerprint = identityRow().copy(
            fingerprint = keyBlob(payload = byteArrayOf(9, 9, 9)).fingerprint(),
        )
        val wrongEmbeddedAlgorithm = identityRow(
            publicKey = keyBlob(algorithm = "ssh-rsa"),
        ).copy(fingerprint = keyBlob(algorithm = "ssh-rsa").fingerprint())

        listOf(wrongFingerprint, wrongEmbeddedAlgorithm).forEach { row ->
            val failure = assertThrows(CorruptStoredDataException::class.java) {
                row.toDomainModel()
            }
            assertEquals(RepositoryRecordType.SSH_KEY_IDENTITY, failure.recordType)
        }
    }

    private fun credentialRow(
        id: String = CREDENTIAL_ID,
        kind: String,
        secretId: String? = null,
        keyIdentityId: String? = null,
    ) = SshCredentialEntity(
        id = id,
        name = "Login",
        kindCode = kind,
        secretId = secretId,
        keyIdentityId = keyIdentityId,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    )

    private fun identityRow(
        publicKey: ByteArray? = keyBlob(),
        provenance: String = "imported",
    ) = SshKeyIdentityEntity(
        id = IDENTITY_ID,
        name = "Work key",
        algorithmCode = "ssh-ed25519",
        fingerprint = (publicKey ?: keyBlob()).fingerprint(),
        publicKey = publicKey,
        provenanceCode = provenance,
        isPassphraseProtected = true,
        comment = "Imported",
        privateSecretId = PRIVATE_KEY_SECRET_ID,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    )

    private fun keyBlob(
        algorithm: String = "ssh-ed25519",
        payload: ByteArray = byteArrayOf(1, 2, 3),
    ): ByteArray {
        val algorithmBytes = algorithm.toByteArray()
        return ByteBuffer.allocate(4 + algorithmBytes.size + payload.size)
            .putInt(algorithmBytes.size)
            .put(algorithmBytes)
            .put(payload)
            .array()
    }

    private fun ByteArray.fingerprint(): String = "SHA256:" +
        Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(this),
        )

    private companion object {
        const val CREDENTIAL_ID = "10000000-0000-4000-8000-000000000001"
        const val PRIVATE_KEY_CREDENTIAL_ID = "10000000-0000-4000-8000-000000000002"
        const val KEYBOARD_CREDENTIAL_ID = "10000000-0000-4000-8000-000000000003"
        const val IDENTITY_ID = "10000000-0000-4000-8000-000000000004"
        const val PASSWORD_SECRET_ID = "10000000-0000-4000-8000-000000000005"
        const val PASSPHRASE_SECRET_ID = "10000000-0000-4000-8000-000000000006"
        const val KEYBOARD_SECRET_ID = "10000000-0000-4000-8000-000000000007"
        const val PRIVATE_KEY_SECRET_ID = "10000000-0000-4000-8000-000000000008"
    }
}
