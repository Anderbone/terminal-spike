package com.yanjiyu.terminalspike.core.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.core.data.credential.EncryptedSecretState
import com.yanjiyu.terminalspike.core.data.credential.RoomCredentialAggregateStore
import com.yanjiyu.terminalspike.core.data.credential.RoomCredentialCiphertextStore
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.db.EncryptedSecretEntity
import com.yanjiyu.terminalspike.core.data.db.SshCredentialEntity
import com.yanjiyu.terminalspike.core.data.db.SshKeyIdentityEntity
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import com.yanjiyu.terminalspike.core.model.SshKeyIdentity
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.core.security.credential.AesGcmCredentialStore
import com.yanjiyu.terminalspike.core.security.credential.CredentialKeyProvider
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialRepositoriesRoomTest {
    private lateinit var database: AppDatabase
    private lateinit var credentialRepository: SshCredentialRepository
    private lateinit var identityRepository: SshKeyIdentityRepository

    @Before
    fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val crypto = AesGcmCredentialStore(
            records = RoomCredentialCiphertextStore(database),
            keys = FixedKeyProvider(),
        )
        val mutations = RoomCredentialAggregateStore(database, crypto)
        credentialRepository = SshCredentialRepository(database.sshCredentialDao(), mutations)
        identityRepository = SshKeyIdentityRepository(database.sshKeyIdentityDao(), mutations)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun metadataReadsKeepEveryUnavailableSecretAsAnOpaqueReference() = runBlocking {
        val records = database.credentialRecordDao()
        listOf(
            unavailableSecret(PASSWORD_SECRET_ID, CredentialSecretKind.PASSWORD),
            unavailableSecret(PASSPHRASE_SECRET_ID, CredentialSecretKind.KEY_PASSPHRASE),
            unavailableSecret(KEYBOARD_SECRET_ID, CredentialSecretKind.KEYBOARD_INTERACTIVE),
            unavailableSecret(PRIVATE_KEY_SECRET_ID, CredentialSecretKind.PRIVATE_KEY),
        ).forEach { records.insertSecret(it) }
        records.insertIdentity(identityRow(publicKey = null))
        records.insertCredential(credentialRow(CREDENTIAL_ID, "Password", "password", PASSWORD_SECRET_ID))
        records.insertCredential(
            credentialRow(
                PRIVATE_KEY_CREDENTIAL_ID,
                "Private key",
                "private_key",
                PASSPHRASE_SECRET_ID,
                IDENTITY_ID,
            ),
        )
        records.insertCredential(
            credentialRow(
                KEYBOARD_CREDENTIAL_ID,
                "Keyboard interactive",
                "keyboard_interactive",
                KEYBOARD_SECRET_ID,
            ),
        )

        val credentials = credentialRepository.observeAll().first().associateBy(SshCredential::id)
        val identity = requireNotNull(identityRepository.get(IDENTITY_ID))

        assertEquals(
            PASSWORD_SECRET_ID,
            (credentials.getValue(CREDENTIAL_ID).authentication as SshAuthentication.Password)
                .secretReferenceId,
        )
        assertEquals(
            PASSPHRASE_SECRET_ID,
            (
                credentials.getValue(PRIVATE_KEY_CREDENTIAL_ID).authentication as
                    SshAuthentication.PrivateKey
                ).passphraseSecretReferenceId,
        )
        assertEquals(
            KEYBOARD_SECRET_ID,
            (
                credentials.getValue(KEYBOARD_CREDENTIAL_ID).authentication as
                    SshAuthentication.KeyboardInteractive
                ).reusableResponseSecretReferenceId,
        )
        assertEquals(PRIVATE_KEY_SECRET_ID, identity.privateKeySecretReferenceId)
        assertNull(identity.publicKey)
    }

    @Test
    fun repositoryWritesJoinMetadataAndPurposeBoundCiphertextWithoutDirectSecretCrud() = runBlocking {
        val keyBlob = keyBlob()
        val identity = identity(
            publicKey = "ssh-ed25519 ${Base64.getEncoder().encodeToString(keyBlob)}",
            fingerprint = keyBlob.fingerprint(),
        )
        val privateKey = "private key test material".toByteArray()
        identityRepository.saveWithPrivateKey(identity, privateKey)
        assertTrue(privateKey.all { it == 0.toByte() })

        val passphraseCredential = credential(
            id = PRIVATE_KEY_CREDENTIAL_ID,
            authentication = SshAuthentication.PrivateKey(IDENTITY_ID, PASSPHRASE_SECRET_ID),
        )
        val passphrase = "purpose-bound passphrase".toByteArray()
        credentialRepository.saveWithSecret(passphraseCredential, passphrase)
        assertTrue(passphrase.all { it == 0.toByte() })

        val keyboardCredential = credential(
            id = KEYBOARD_CREDENTIAL_ID,
            authentication = SshAuthentication.KeyboardInteractive(KEYBOARD_SECRET_ID),
        )
        val keyboardResponse = "one-time-like reusable response".toByteArray()
        credentialRepository.saveWithSecret(keyboardCredential, keyboardResponse)
        assertTrue(keyboardResponse.all { it == 0.toByte() })

        assertEquals(
            CredentialSecretKind.PRIVATE_KEY.wireCode,
            database.credentialRecordDao().findSecretById(PRIVATE_KEY_SECRET_ID)?.kindCode,
        )
        assertEquals(
            CredentialSecretKind.KEY_PASSPHRASE.wireCode,
            database.credentialRecordDao().findSecretById(PASSPHRASE_SECRET_ID)?.kindCode,
        )
        assertEquals(
            CredentialSecretKind.KEYBOARD_INTERACTIVE.wireCode,
            database.credentialRecordDao().findSecretById(KEYBOARD_SECRET_ID)?.kindCode,
        )

        credentialRepository.saveMetadata(
            keyboardCredential.copy(displayName = "Renamed", updatedAtEpochMillis = 30),
        )
        val rebound = keyboardCredential.copy(
            authentication = SshAuthentication.KeyboardInteractive(SECOND_KEYBOARD_SECRET_ID),
            updatedAtEpochMillis = 40,
        )
        val failure = expectFailure<com.yanjiyu.terminalspike.core.data.credential.CredentialAggregateException.Malformed> {
            credentialRepository.saveMetadata(rebound)
        }
        assertEquals(
            com.yanjiyu.terminalspike.core.data.credential.CredentialAggregateMalformedReason.REFERENCE,
            failure.reason,
        )
        assertEquals("Renamed", credentialRepository.get(KEYBOARD_CREDENTIAL_ID)?.displayName)
        assertEquals(
            KEYBOARD_SECRET_ID,
            database.sshCredentialDao().findById(KEYBOARD_CREDENTIAL_ID)?.secretId,
        )
        assertNull(database.credentialRecordDao().findSecretById(SECOND_KEYBOARD_SECRET_ID))
    }

    private fun unavailableSecret(id: String, kind: CredentialSecretKind) = EncryptedSecretEntity(
        id = id,
        kindCode = kind.wireCode,
        envelopeVersion = 0,
        keyVersion = 0,
        nonce = null,
        ciphertext = null,
        stateCode = EncryptedSecretState.LEGACY_UNAVAILABLE.wireCode,
        failureCode = "legacy_key_unavailable",
        legacyId = "legacy/$id",
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 10,
    )

    private fun credential(
        id: String,
        authentication: SshAuthentication,
    ) = SshCredential(
        id = id,
        displayName = "Login",
        authentication = authentication,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    )

    private fun identity(publicKey: String, fingerprint: String) = SshKeyIdentity(
        id = IDENTITY_ID,
        name = "Work key",
        algorithm = "ssh-ed25519",
        publicKeyFingerprint = fingerprint,
        publicKey = publicKey,
        privateKeySecretReferenceId = PRIVATE_KEY_SECRET_ID,
        origin = SshKeyOrigin.IMPORTED,
        isPassphraseProtected = true,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    )

    private fun identityRow(publicKey: ByteArray?) = SshKeyIdentityEntity(
        id = IDENTITY_ID,
        name = "Retained key",
        algorithmCode = "ssh-ed25519",
        fingerprint = keyBlob().fingerprint(),
        publicKey = publicKey,
        provenanceCode = "imported",
        isPassphraseProtected = true,
        comment = null,
        privateSecretId = PRIVATE_KEY_SECRET_ID,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 10,
    )

    private fun credentialRow(
        id: String,
        name: String,
        kind: String,
        secretId: String?,
        identityId: String? = null,
    ) = SshCredentialEntity(
        id = id,
        name = name,
        kindCode = kind,
        secretId = secretId,
        keyIdentityId = identityId,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 10,
    )

    private fun keyBlob(): ByteArray {
        val algorithm = "ssh-ed25519".toByteArray()
        return ByteBuffer.allocate(4 + algorithm.size + 4)
            .putInt(algorithm.size)
            .put(algorithm)
            .put(byteArrayOf(1, 3, 3, 7))
            .array()
    }

    private fun ByteArray.fingerprint(): String = "SHA256:" +
        Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(this),
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

    private class FixedKeyProvider : CredentialKeyProvider {
        private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

        override fun getOrCreateEncryptionKey(keyVersion: Int): SecretKey = key

        override fun getExistingDecryptionKey(keyVersion: Int): SecretKey = key
    }

    private companion object {
        const val CREDENTIAL_ID = "30000000-0000-4000-8000-000000000001"
        const val PRIVATE_KEY_CREDENTIAL_ID = "30000000-0000-4000-8000-000000000002"
        const val KEYBOARD_CREDENTIAL_ID = "30000000-0000-4000-8000-000000000003"
        const val IDENTITY_ID = "30000000-0000-4000-8000-000000000004"
        const val PASSWORD_SECRET_ID = "30000000-0000-4000-8000-000000000005"
        const val PASSPHRASE_SECRET_ID = "30000000-0000-4000-8000-000000000006"
        const val KEYBOARD_SECRET_ID = "30000000-0000-4000-8000-000000000007"
        const val SECOND_KEYBOARD_SECRET_ID = "30000000-0000-4000-8000-000000000008"
        const val PRIVATE_KEY_SECRET_ID = "30000000-0000-4000-8000-000000000009"
    }
}
