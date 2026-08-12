package com.yanjiyu.terminalspike.core.data.repository

import com.yanjiyu.terminalspike.core.data.credential.ClearCredentialSecretResult
import com.yanjiyu.terminalspike.core.data.credential.CredentialAggregateMutations
import com.yanjiyu.terminalspike.core.data.credential.DeleteCredentialMetadataResult
import com.yanjiyu.terminalspike.core.data.db.SshCredentialDao
import com.yanjiyu.terminalspike.core.data.db.SshCredentialEntity
import com.yanjiyu.terminalspike.core.data.db.SshKeyIdentityDao
import com.yanjiyu.terminalspike.core.data.db.SshKeyIdentityEntity
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import com.yanjiyu.terminalspike.core.model.SshKeyIdentity
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.core.security.credential.CredentialId
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRepositoriesTest {
    @Test
    fun readSurfacesExposeValidatedMetadataAndOpaqueReferencesOnly() = runTest {
        val mutations = RecordingMutations()
        val credentialRepository = SshCredentialRepository(
            FakeCredentialDao(
                listOf(
                    credentialRow("password", PASSWORD_SECRET_ID),
                    credentialRow(
                        "private_key",
                        PASSPHRASE_SECRET_ID,
                        IDENTITY_ID,
                        PRIVATE_KEY_CREDENTIAL_ID,
                    ),
                    credentialRow(
                        "keyboard_interactive",
                        KEYBOARD_SECRET_ID,
                        id = KEYBOARD_CREDENTIAL_ID,
                    ),
                ),
            ),
            mutations,
        )
        val identityRepository = SshKeyIdentityRepository(
            FakeIdentityDao(listOf(identityRow(publicKey = null))),
            mutations,
        )

        val credentials = credentialRepository.observeAll().first()
        val retainedIdentity = requireNotNull(identityRepository.get(IDENTITY_ID))

        assertEquals(PASSWORD_SECRET_ID, (credentials[0].authentication as SshAuthentication.Password).secretReferenceId)
        assertEquals(
            PASSPHRASE_SECRET_ID,
            (credentials[1].authentication as SshAuthentication.PrivateKey).passphraseSecretReferenceId,
        )
        assertEquals(
            KEYBOARD_SECRET_ID,
            (credentials[2].authentication as SshAuthentication.KeyboardInteractive)
                .reusableResponseSecretReferenceId,
        )
        assertEquals(PRIVATE_KEY_SECRET_ID, retainedIdentity.privateKeySecretReferenceId)
        assertEquals(null, retainedIdentity.publicKey)
    }

    @Test
    fun credentialSecretWritesUsePurposeSpecificAggregateReferencesAndAlwaysWipeInput() = runTest {
        val mutations = RecordingMutations()
        val repository = SshCredentialRepository(FakeCredentialDao(), mutations)
        val cases = listOf(
            credential(SshAuthentication.Password(PASSWORD_SECRET_ID)) to
                CredentialSecretKind.PASSWORD,
            credential(
                SshAuthentication.PrivateKey(IDENTITY_ID, PASSPHRASE_SECRET_ID),
                PRIVATE_KEY_CREDENTIAL_ID,
            ) to CredentialSecretKind.KEY_PASSPHRASE,
            credential(
                SshAuthentication.KeyboardInteractive(KEYBOARD_SECRET_ID),
                KEYBOARD_CREDENTIAL_ID,
            ) to CredentialSecretKind.KEYBOARD_INTERACTIVE,
        )

        cases.forEach { (credential, expectedKind) ->
            val plaintext = byteArrayOf(9, 8, 7)
            repository.saveWithSecret(credential, plaintext)
            assertTrue(plaintext.all { it == 0.toByte() })
            val call = mutations.credentialSecretWrites.last()
            assertEquals(credential.id, call.reference.credentialId.value)
            assertEquals(expectedKind, call.reference.kind)
            assertEquals(credential.toEntity(), call.credential)
        }

        val missingReferencePlaintext = byteArrayOf(4, 5, 6)
        var failed = false
        try {
            repository.saveWithSecret(
                credential(SshAuthentication.KeyboardInteractive(), KEYBOARD_CREDENTIAL_ID),
                missingReferencePlaintext,
            )
        } catch (_: InvalidRepositoryInputException) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(missingReferencePlaintext.all { it == 0.toByte() })
    }

    @Test
    fun metadataClearAndDeleteOperationsDelegateWithoutDirectDaoWrites() = runTest {
        val dao = FakeCredentialDao()
        val mutations = RecordingMutations()
        val repository = SshCredentialRepository(dao, mutations)
        val prompted = credential(SshAuthentication.Password())

        repository.saveMetadata(prompted)
        val clear = repository.clearSavedSecret(CREDENTIAL_ID)
        val delete = repository.delete(CREDENTIAL_ID)

        assertEquals(prompted.toEntity(), mutations.savedCredentialMetadata)
        assertEquals(CREDENTIAL_ID, mutations.clearedCredentialId?.value)
        assertEquals(CREDENTIAL_ID, mutations.deletedCredentialId?.value)
        assertEquals(ClearCredentialSecretResult(true, true, true), clear)
        assertEquals(DeleteCredentialMetadataResult(true, true), delete)
        assertEquals(0, dao.updateCalls)
        assertEquals(0, dao.deleteCalls)
    }

    @Test
    fun identityWritesPersistPublicMetadataAndDelegatePrivateMaterialAtomically() = runTest {
        val dao = FakeIdentityDao()
        val mutations = RecordingMutations()
        val repository = SshKeyIdentityRepository(dao, mutations)
        val keyBlob = keyBlob()
        val identity = identity(
            publicKey = "ssh-ed25519 ${Base64.getEncoder().encodeToString(keyBlob)}",
        )
        val privateKey = byteArrayOf(7, 6, 5, 4)

        repository.saveWithPrivateKey(identity, privateKey)
        val updated = repository.updateMetadata(identity.copy(name = "Renamed", updatedAtEpochMillis = 30))
        val deleted = repository.delete(IDENTITY_ID)

        assertTrue(privateKey.all { it == 0.toByte() })
        val saved = requireNotNull(mutations.identitySecretWrite)
        assertEquals(CredentialSecretKind.PRIVATE_KEY, saved.reference.kind)
        assertEquals(IDENTITY_ID, saved.reference.credentialId.value)
        assertEquals(PRIVATE_KEY_SECRET_ID, saved.reference.secretId.value)
        assertArrayEquals(keyBlob, saved.identity.publicKey)
        assertTrue(updated)
        assertEquals("Renamed", mutations.updatedIdentity?.name)
        assertEquals(DeleteCredentialMetadataResult(true, true), deleted)
        assertEquals(0, dao.updateCalls)
        assertEquals(0, dao.deleteCalls)
    }

    private class FakeCredentialDao(
        initial: List<SshCredentialEntity> = emptyList(),
    ) : SshCredentialDao {
        private val rows = MutableStateFlow(initial)
        var updateCalls = 0
        var deleteCalls = 0

        override fun observeAll(): Flow<List<SshCredentialEntity>> = rows

        override suspend fun findById(id: String): SshCredentialEntity? = rows.value.find { it.id == id }

        override suspend fun update(credential: SshCredentialEntity): Int {
            updateCalls += 1
            return 0
        }

        override suspend fun deleteById(id: String): Int {
            deleteCalls += 1
            return 0
        }
    }

    private class FakeIdentityDao(
        initial: List<SshKeyIdentityEntity> = emptyList(),
    ) : SshKeyIdentityDao {
        private val rows = MutableStateFlow(initial)
        var updateCalls = 0
        var deleteCalls = 0

        override fun observeAll(): Flow<List<SshKeyIdentityEntity>> = rows

        override suspend fun findById(id: String): SshKeyIdentityEntity? = rows.value.find { it.id == id }

        override suspend fun update(identity: SshKeyIdentityEntity): Int {
            updateCalls += 1
            return 0
        }

        override suspend fun deleteById(id: String): Int {
            deleteCalls += 1
            return 0
        }
    }

    private class RecordingMutations : CredentialAggregateMutations {
        data class CredentialWrite(
            val reference: CredentialSecretReference,
            val credential: SshCredentialEntity,
        )

        data class IdentityWrite(
            val reference: CredentialSecretReference,
            val identity: SshKeyIdentityEntity,
        )

        val credentialSecretWrites = mutableListOf<CredentialWrite>()
        var savedCredentialMetadata: SshCredentialEntity? = null
        var identitySecretWrite: IdentityWrite? = null
        var updatedIdentity: SshKeyIdentityEntity? = null
        var clearedCredentialId: CredentialId? = null
        var deletedCredentialId: CredentialId? = null
        var deletedIdentityId: CredentialId? = null

        override suspend fun saveCredentialMetadata(credential: SshCredentialEntity) {
            savedCredentialMetadata = credential
        }

        override suspend fun saveCredentialAndSecret(
            reference: CredentialSecretReference,
            secret: ByteArray,
            credential: SshCredentialEntity,
        ) {
            credentialSecretWrites += CredentialWrite(reference, credential)
            secret.fill(0)
        }

        override suspend fun updateIdentityMetadata(identity: SshKeyIdentityEntity): Boolean {
            updatedIdentity = identity
            return true
        }

        override suspend fun saveIdentityAndPrivateKey(
            reference: CredentialSecretReference,
            privateKey: ByteArray,
            identity: SshKeyIdentityEntity,
        ) {
            identitySecretWrite = IdentityWrite(reference, identity)
            privateKey.fill(0)
        }

        override suspend fun clearCredentialSecretAndDeleteIfUnreferenced(
            credentialId: CredentialId,
        ): ClearCredentialSecretResult {
            clearedCredentialId = credentialId
            return ClearCredentialSecretResult(true, true, true)
        }

        override suspend fun deleteCredentialAndUnreferencedSecret(
            credentialId: CredentialId,
        ): DeleteCredentialMetadataResult {
            deletedCredentialId = credentialId
            return DeleteCredentialMetadataResult(true, true)
        }

        override suspend fun deleteIdentityAndUnreferencedPrivateSecret(
            identityId: CredentialId,
        ): DeleteCredentialMetadataResult {
            deletedIdentityId = identityId
            return DeleteCredentialMetadataResult(true, true)
        }
    }

    private fun credential(authentication: SshAuthentication, id: String = CREDENTIAL_ID) =
        SshCredential(
            id = id,
            displayName = "Login",
            authentication = authentication,
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        )

    private fun identity(publicKey: String?): SshKeyIdentity {
        val keyBlob = publicKey?.substringAfter(' ')?.let(Base64.getDecoder()::decode)
            ?: keyBlob()
        return SshKeyIdentity(
        id = IDENTITY_ID,
        name = "Work key",
        algorithm = "ssh-ed25519",
        publicKeyFingerprint = keyBlob.fingerprint(),
        publicKey = publicKey,
        privateKeySecretReferenceId = PRIVATE_KEY_SECRET_ID,
        origin = SshKeyOrigin.IMPORTED,
        isPassphraseProtected = true,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    )
    }

    private fun credentialRow(
        kind: String,
        secretId: String?,
        identityId: String? = null,
        id: String = CREDENTIAL_ID,
    ) = SshCredentialEntity(
        id = id,
        name = "Login",
        kindCode = kind,
        secretId = secretId,
        keyIdentityId = identityId,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    )

    private fun identityRow(publicKey: ByteArray?) = SshKeyIdentityEntity(
        id = IDENTITY_ID,
        name = "Work key",
        algorithmCode = "ssh-ed25519",
        fingerprint = (publicKey ?: keyBlob()).fingerprint(),
        publicKey = publicKey,
        provenanceCode = "imported",
        isPassphraseProtected = true,
        comment = null,
        privateSecretId = PRIVATE_KEY_SECRET_ID,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    )

    private fun keyBlob(): ByteArray {
        val algorithm = "ssh-ed25519".toByteArray()
        return ByteBuffer.allocate(4 + algorithm.size + 3)
            .putInt(algorithm.size)
            .put(algorithm)
            .put(byteArrayOf(1, 2, 3))
            .array()
    }

    private fun ByteArray.fingerprint(): String = "SHA256:" +
        Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(this),
        )

    private companion object {
        const val CREDENTIAL_ID = "20000000-0000-4000-8000-000000000001"
        const val PRIVATE_KEY_CREDENTIAL_ID = "20000000-0000-4000-8000-000000000002"
        const val KEYBOARD_CREDENTIAL_ID = "20000000-0000-4000-8000-000000000003"
        const val IDENTITY_ID = "20000000-0000-4000-8000-000000000004"
        const val PASSWORD_SECRET_ID = "20000000-0000-4000-8000-000000000005"
        const val PASSPHRASE_SECRET_ID = "20000000-0000-4000-8000-000000000006"
        const val KEYBOARD_SECRET_ID = "20000000-0000-4000-8000-000000000007"
        const val PRIVATE_KEY_SECRET_ID = "20000000-0000-4000-8000-000000000008"
    }
}
