package com.yanjiyu.terminalspike.core.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.core.data.credential.CredentialEpochClock
import com.yanjiyu.terminalspike.core.data.credential.RoomCredentialAggregateStore
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.db.SshCredentialEntity
import com.yanjiyu.terminalspike.core.data.db.SshKeyIdentityEntity
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.security.credential.CredentialId
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.CredentialStore
import com.yanjiyu.terminalspike.core.security.credential.EncryptedCredentialRecord
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomTerminalToolsMutationsTest {
    private lateinit var database: AppDatabase
    private lateinit var clock: MutableClock
    private lateinit var crypto: RecordingCredentialStore

    @Before
    fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        clock = MutableClock(CREATED_AT)
        crypto = RecordingCredentialStore(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun createAndUpdateHostWithPasswordCommitAllRowsAndWipePlaintext() = runBlocking {
        val mutations = mutations()
        val createPassword = "create-only plaintext".toByteArray()

        mutations.upsertHostWithPassword(
            profile = host(HOST_ID, CREDENTIAL_ID),
            expectedCredentialId = null,
            credentialId = CREDENTIAL_ID,
            secretId = SECRET_ID,
            password = createPassword,
        )

        assertTrue(createPassword.all { it == 0.toByte() })
        assertEquals(listOf(false), crypto.encryptInsideTransactions)
        assertEquals(
            CREDENTIAL_ID,
            database.hostProfileDao().findById(HOST_ID)?.credentialId,
        )
        assertEquals(
            SECRET_ID,
            database.credentialRecordDao().findCredentialById(CREDENTIAL_ID)?.secretId,
        )
        assertNotNull(database.credentialRecordDao().findSecretById(SECRET_ID))

        clock.now = UPDATED_AT
        val updatePassword = "replacement plaintext".toByteArray()
        mutations.upsertHostWithPassword(
            profile = host(
                id = HOST_ID,
                credentialId = CREDENTIAL_ID,
                displayName = "Renamed host",
                updatedAt = UPDATED_AT,
            ),
            expectedCredentialId = CREDENTIAL_ID,
            credentialId = CREDENTIAL_ID,
            secretId = SECOND_SECRET_ID,
            password = updatePassword,
        )

        assertTrue(updatePassword.all { it == 0.toByte() })
        assertEquals(listOf(false, false), crypto.encryptInsideTransactions)
        assertEquals("Renamed host", database.hostProfileDao().findById(HOST_ID)?.displayName)
        assertEquals(
            SECOND_SECRET_ID,
            database.credentialRecordDao().findCredentialById(CREDENTIAL_ID)?.secretId,
        )
        assertNull(database.credentialRecordDao().findSecretById(SECRET_ID))
        assertNotNull(database.credentialRecordDao().findSecretById(SECOND_SECRET_ID))
    }

    @Test
    fun injectedCreateFailureRollsBackCiphertextCredentialAndHost() = runBlocking {
        val failure = InjectedFailure()
        val mutations = mutations(failAt = RoomTerminalToolsMutationCheckpoint.CREDENTIAL_COMMITTED) {
            failure
        }
        val password = "must be wiped after rollback".toByteArray()

        val thrown = expectFailure<InjectedFailure> {
            mutations.upsertHostWithPassword(
                profile = host(HOST_ID, CREDENTIAL_ID),
                expectedCredentialId = null,
                credentialId = CREDENTIAL_ID,
                secretId = SECRET_ID,
                password = password,
            )
        }

        assertTrue(thrown === failure)
        assertTrue(password.all { it == 0.toByte() })
        assertEquals(listOf(false), crypto.encryptInsideTransactions)
        assertNull(database.hostProfileDao().findById(HOST_ID))
        assertNull(database.credentialRecordDao().findCredentialById(CREDENTIAL_ID))
        assertNull(database.credentialRecordDao().findSecretById(SECRET_ID))
    }

    @Test
    fun injectedUpdateFailureRestoresPreviousHostCredentialAndCiphertext() = runBlocking {
        val seed = mutations()
        seed.upsertHostWithPassword(
            profile = host(HOST_ID, CREDENTIAL_ID),
            expectedCredentialId = null,
            credentialId = CREDENTIAL_ID,
            secretId = SECRET_ID,
            password = "original password".toByteArray(),
        )
        clock.now = UPDATED_AT
        val failure = InjectedFailure()
        val failing = mutations(failAt = RoomTerminalToolsMutationCheckpoint.HOST_WRITTEN) {
            failure
        }
        val replacement = "replacement password".toByteArray()

        val thrown = expectFailure<InjectedFailure> {
            failing.upsertHostWithPassword(
                profile = host(
                    id = HOST_ID,
                    credentialId = CREDENTIAL_ID,
                    displayName = "Must roll back",
                    updatedAt = UPDATED_AT,
                ),
                expectedCredentialId = CREDENTIAL_ID,
                credentialId = CREDENTIAL_ID,
                secretId = SECOND_SECRET_ID,
                password = replacement,
            )
        }

        assertTrue(thrown === failure)
        assertTrue(replacement.all { it == 0.toByte() })
        assertEquals("Primary", database.hostProfileDao().findById(HOST_ID)?.displayName)
        assertEquals(
            SECRET_ID,
            database.credentialRecordDao().findCredentialById(CREDENTIAL_ID)?.secretId,
        )
        assertNotNull(database.credentialRecordDao().findSecretById(SECRET_ID))
        assertNull(database.credentialRecordDao().findSecretById(SECOND_SECRET_ID))
    }

    @Test
    fun forgetUnsharedPasswordRetainsPromptOnlyCredentialAndHostBinding() = runBlocking {
        val mutations = mutations()
        mutations.upsertHostWithPassword(
            profile = host(HOST_ID, CREDENTIAL_ID),
            expectedCredentialId = null,
            credentialId = CREDENTIAL_ID,
            secretId = SECRET_ID,
            password = "saved password".toByteArray(),
        )

        val result = mutations.forgetPassword(HOST_ID)

        assertTrue(result is ForgetHostPasswordResult.RetainedPromptCredential)
        assertEquals(
            CREDENTIAL_ID,
            (result as ForgetHostPasswordResult.RetainedPromptCredential).credentialId.value,
        )
        assertEquals(
            CREDENTIAL_ID,
            database.hostProfileDao().findById(HOST_ID)?.credentialId,
        )
        assertNull(database.credentialRecordDao().findCredentialById(CREDENTIAL_ID)?.secretId)
        assertNull(database.credentialRecordDao().findSecretById(SECRET_ID))
    }

    @Test
    fun forgetSharedPasswordSplitsTargetToPromptOnlyAndPreservesOtherHostSecret() = runBlocking {
        val mutations = mutations()
        mutations.upsertHostWithPassword(
            profile = host(HOST_ID, CREDENTIAL_ID),
            expectedCredentialId = null,
            credentialId = CREDENTIAL_ID,
            secretId = SECRET_ID,
            password = "shared saved password".toByteArray(),
        )
        mutations.upsertHost(host(SECOND_HOST_ID, CREDENTIAL_ID, displayName = "Secondary"))

        val result = mutations.forgetPassword(HOST_ID)

        assertTrue(result is ForgetHostPasswordResult.SplitPromptCredential)
        val split = result as ForgetHostPasswordResult.SplitPromptCredential
        assertEquals(SPLIT_CREDENTIAL_ID, split.credentialId.value)
        assertEquals(CREDENTIAL_ID, split.sharedCredentialId.value)
        assertEquals(
            SPLIT_CREDENTIAL_ID,
            database.hostProfileDao().findById(HOST_ID)?.credentialId,
        )
        assertEquals(
            CREDENTIAL_ID,
            database.hostProfileDao().findById(SECOND_HOST_ID)?.credentialId,
        )
        assertNull(
            database.credentialRecordDao().findCredentialById(SPLIT_CREDENTIAL_ID)?.secretId,
        )
        assertEquals(
            SECRET_ID,
            database.credentialRecordDao().findCredentialById(CREDENTIAL_ID)?.secretId,
        )
        assertNotNull(database.credentialRecordDao().findSecretById(SECRET_ID))
    }

    @Test
    fun injectedForgetFailureRollsBackClearedReferenceAndCiphertextDeletion() = runBlocking {
        mutations().upsertHostWithPassword(
            profile = host(HOST_ID, CREDENTIAL_ID),
            expectedCredentialId = null,
            credentialId = CREDENTIAL_ID,
            secretId = SECRET_ID,
            password = "saved password".toByteArray(),
        )
        val failure = InjectedFailure()
        val failing = mutations(
            failAt = RoomTerminalToolsMutationCheckpoint.PASSWORD_SECRET_CLEARED,
        ) { failure }

        val thrown = expectFailure<InjectedFailure> { failing.forgetPassword(HOST_ID) }

        assertTrue(thrown === failure)
        assertEquals(
            CREDENTIAL_ID,
            database.hostProfileDao().findById(HOST_ID)?.credentialId,
        )
        assertEquals(
            SECRET_ID,
            database.credentialRecordDao().findCredentialById(CREDENTIAL_ID)?.secretId,
        )
        assertNotNull(database.credentialRecordDao().findSecretById(SECRET_ID))
    }

    @Test
    fun deleteHostRemovesOnlyCredentialNoOtherHostReferences() = runBlocking {
        val mutations = mutations()
        mutations.upsertHostWithPassword(
            profile = host(HOST_ID, CREDENTIAL_ID),
            expectedCredentialId = null,
            credentialId = CREDENTIAL_ID,
            secretId = SECRET_ID,
            password = "shared saved password".toByteArray(),
        )
        mutations.upsertHost(host(SECOND_HOST_ID, CREDENTIAL_ID, displayName = "Secondary"))

        assertTrue(mutations.deleteHostAndUnreferencedCredential(HOST_ID))
        assertNull(database.hostProfileDao().findById(HOST_ID))
        assertNotNull(database.hostProfileDao().findById(SECOND_HOST_ID))
        assertNotNull(database.credentialRecordDao().findCredentialById(CREDENTIAL_ID))
        assertNotNull(database.credentialRecordDao().findSecretById(SECRET_ID))

        assertTrue(mutations.deleteHostAndUnreferencedCredential(SECOND_HOST_ID))
        assertNull(database.hostProfileDao().findById(SECOND_HOST_ID))
        assertNull(database.credentialRecordDao().findCredentialById(CREDENTIAL_ID))
        assertNull(database.credentialRecordDao().findSecretById(SECRET_ID))
        assertFalse(mutations.deleteHostAndUnreferencedCredential(SECOND_HOST_ID))
    }

    @Test
    fun detachingKeyboardInteractiveCredentialPreservesReusableMetadataAndSecret() = runBlocking {
        seedKeyboardInteractiveCredential()
        val mutations = mutations()
        mutations.upsertHost(host(HOST_ID, NON_PASSWORD_CREDENTIAL_ID))

        mutations.upsertHost(
            host(
                id = HOST_ID,
                credentialId = null,
                updatedAt = UPDATED_AT,
            ),
        )

        assertNull(database.hostProfileDao().findById(HOST_ID)?.credentialId)
        assertEquals(
            NON_PASSWORD_SECRET_ID,
            database.credentialRecordDao()
                .findCredentialById(NON_PASSWORD_CREDENTIAL_ID)
                ?.secretId,
        )
        assertNotNull(
            database.credentialRecordDao().findSecretById(NON_PASSWORD_SECRET_ID),
        )
    }

    @Test
    fun metadataOnlyEndpointEditDetachesButNeverDeletesSavedPassword() = runBlocking {
        val mutations = mutations()
        mutations.upsertHostWithPassword(
            profile = host(HOST_ID, CREDENTIAL_ID),
            expectedCredentialId = null,
            credentialId = CREDENTIAL_ID,
            secretId = SECRET_ID,
            password = "preserved saved password".toByteArray(),
        )

        mutations.upsertHost(
            host(
                id = HOST_ID,
                credentialId = null,
                updatedAt = UPDATED_AT,
            ).copy(hostname = "renamed-endpoint.example"),
        )

        assertNull(database.hostProfileDao().findById(HOST_ID)?.credentialId)
        assertEquals(
            SECRET_ID,
            database.credentialRecordDao().findCredentialById(CREDENTIAL_ID)?.secretId,
        )
        assertNotNull(database.credentialRecordDao().findSecretById(SECRET_ID))
    }

    @Test
    fun deletingLastPrivateKeyHostPreservesReusableCredentialAndBothSecrets() = runBlocking {
        seedPrivateKeyCredential()
        val mutations = mutations()
        mutations.upsertHost(host(HOST_ID, PRIVATE_KEY_CREDENTIAL_ID))

        assertTrue(mutations.deleteHostAndUnreferencedCredential(HOST_ID))

        assertNull(database.hostProfileDao().findById(HOST_ID))
        assertEquals(
            PASSPHRASE_SECRET_ID,
            database.credentialRecordDao()
                .findCredentialById(PRIVATE_KEY_CREDENTIAL_ID)
                ?.secretId,
        )
        assertNotNull(database.credentialRecordDao().findSecretById(PASSPHRASE_SECRET_ID))
        assertNotNull(database.credentialRecordDao().findIdentityById(IDENTITY_ID))
        assertNotNull(database.credentialRecordDao().findSecretById(PRIVATE_KEY_SECRET_ID))
    }

    @Test
    fun replacingKeyboardInteractiveBindingWithPasswordPreservesOldMetadataAndSecret() =
        runBlocking {
            seedKeyboardInteractiveCredential()
            val mutations = mutations()
            mutations.upsertHost(host(HOST_ID, NON_PASSWORD_CREDENTIAL_ID))
            clock.now = UPDATED_AT
            val password = "new password".toByteArray()

            mutations.savePassword(
                hostId = HOST_ID,
                expectedCredentialId = NON_PASSWORD_CREDENTIAL_ID,
                credentialId = REPLACEMENT_PASSWORD_CREDENTIAL_ID,
                secretId = REPLACEMENT_PASSWORD_SECRET_ID,
                password = password,
            )

            assertTrue(password.all { it == 0.toByte() })
            assertEquals(
                REPLACEMENT_PASSWORD_CREDENTIAL_ID,
                database.hostProfileDao().findById(HOST_ID)?.credentialId,
            )
            assertEquals(
                NON_PASSWORD_SECRET_ID,
                database.credentialRecordDao()
                    .findCredentialById(NON_PASSWORD_CREDENTIAL_ID)
                    ?.secretId,
            )
            assertNotNull(
                database.credentialRecordDao().findSecretById(NON_PASSWORD_SECRET_ID),
            )
            assertEquals(
                REPLACEMENT_PASSWORD_SECRET_ID,
                database.credentialRecordDao()
                    .findCredentialById(REPLACEMENT_PASSWORD_CREDENTIAL_ID)
                    ?.secretId,
            )
            assertNotNull(
                database.credentialRecordDao().findSecretById(REPLACEMENT_PASSWORD_SECRET_ID),
            )
        }

    @Test
    fun injectedDeleteFailureRestoresHostCredentialAndSecret() = runBlocking {
        mutations().upsertHostWithPassword(
            profile = host(HOST_ID, CREDENTIAL_ID),
            expectedCredentialId = null,
            credentialId = CREDENTIAL_ID,
            secretId = SECRET_ID,
            password = "saved password".toByteArray(),
        )
        val failure = InjectedFailure()
        val failing = mutations(failAt = RoomTerminalToolsMutationCheckpoint.HOST_DELETED) {
            failure
        }

        val thrown = expectFailure<InjectedFailure> {
            failing.deleteHostAndUnreferencedCredential(HOST_ID)
        }

        assertTrue(thrown === failure)
        assertNotNull(database.hostProfileDao().findById(HOST_ID))
        assertNotNull(database.credentialRecordDao().findCredentialById(CREDENTIAL_ID))
        assertNotNull(database.credentialRecordDao().findSecretById(SECRET_ID))
    }

    @Test
    fun invalidIdentifierWipesPlaintextBeforeEncryptionAndWritesNothing() = runBlocking {
        val password = "invalid input still wiped".toByteArray()

        expectFailure<IllegalArgumentException> {
            mutations().savePassword(
                hostId = "not-a-uuid",
                expectedCredentialId = null,
                credentialId = CREDENTIAL_ID,
                secretId = SECRET_ID,
                password = password,
            )
        }

        assertTrue(password.all { it == 0.toByte() })
        assertTrue(crypto.encryptInsideTransactions.isEmpty())
        assertNull(database.credentialRecordDao().findCredentialById(CREDENTIAL_ID))
        assertNull(database.credentialRecordDao().findSecretById(SECRET_ID))
    }

    @Test
    fun corruptStoredHostBlocksDeletionWithoutDeletingAnything() = runBlocking {
        database.hostProfileDao().insert(host(HOST_ID, null).toEntity().copy(protocolCode = "future"))

        expectFailure<CorruptStoredDataException> {
            mutations().deleteHostAndUnreferencedCredential(HOST_ID)
        }

        assertNotNull(database.hostProfileDao().findById(HOST_ID))
    }

    private suspend fun seedKeyboardInteractiveCredential() {
        val reference = CredentialSecretReference(
            credentialId = CredentialId.parseCanonical(NON_PASSWORD_CREDENTIAL_ID),
            secretId = SecretId.parseCanonical(NON_PASSWORD_SECRET_ID),
            kind = CredentialSecretKind.KEYBOARD_INTERACTIVE,
        )
        RoomCredentialAggregateStore(database, crypto, clock).saveCredentialAndSecret(
            reference = reference,
            secret = "reusable response".toByteArray(),
            credential = SshCredentialEntity(
                id = NON_PASSWORD_CREDENTIAL_ID,
                name = "Keyboard interactive",
                kindCode = "keyboard_interactive",
                secretId = NON_PASSWORD_SECRET_ID,
                keyIdentityId = null,
                createdAtEpochMillis = CREATED_AT,
                updatedAtEpochMillis = CREATED_AT,
            ),
        )
    }

    private suspend fun seedPrivateKeyCredential() {
        val aggregate = RoomCredentialAggregateStore(database, crypto, clock)
        aggregate.saveIdentityAndPrivateKey(
            reference = CredentialSecretReference(
                credentialId = CredentialId.parseCanonical(IDENTITY_ID),
                secretId = SecretId.parseCanonical(PRIVATE_KEY_SECRET_ID),
                kind = CredentialSecretKind.PRIVATE_KEY,
            ),
            privateKey = "private key".toByteArray(),
            identity = SshKeyIdentityEntity(
                id = IDENTITY_ID,
                name = "Reusable key",
                algorithmCode = "ssh-ed25519",
                fingerprint = ZERO_SHA256_FINGERPRINT,
                publicKey = null,
                provenanceCode = "imported",
                isPassphraseProtected = true,
                comment = null,
                privateSecretId = PRIVATE_KEY_SECRET_ID,
                createdAtEpochMillis = CREATED_AT,
                updatedAtEpochMillis = CREATED_AT,
            ),
        )
        aggregate.saveCredentialAndSecret(
            reference = CredentialSecretReference(
                credentialId = CredentialId.parseCanonical(PRIVATE_KEY_CREDENTIAL_ID),
                secretId = SecretId.parseCanonical(PASSPHRASE_SECRET_ID),
                kind = CredentialSecretKind.KEY_PASSPHRASE,
            ),
            secret = "key passphrase".toByteArray(),
            credential = SshCredentialEntity(
                id = PRIVATE_KEY_CREDENTIAL_ID,
                name = "Private key login",
                kindCode = "private_key",
                secretId = PASSPHRASE_SECRET_ID,
                keyIdentityId = IDENTITY_ID,
                createdAtEpochMillis = CREATED_AT,
                updatedAtEpochMillis = CREATED_AT,
            ),
        )
    }

    private fun mutations(
        failAt: RoomTerminalToolsMutationCheckpoint? = null,
        failure: () -> Throwable = ::InjectedFailure,
    ): RoomTerminalToolsMutations = RoomTerminalToolsMutations(
        database = database,
        credentialMutations = RoomCredentialAggregateStore(database, crypto, clock),
        credentialStore = crypto,
        clock = clock,
        newCredentialId = { CredentialId.parseCanonical(SPLIT_CREDENTIAL_ID) },
        afterMutationStep = { checkpoint ->
            if (checkpoint == failAt) throw failure()
        },
    )

    private fun host(
        id: String,
        credentialId: String?,
        displayName: String = "Primary",
        updatedAt: Long = CREATED_AT,
    ) = HostProfile(
        id = id,
        displayName = displayName,
        hostname = if (id == HOST_ID) "primary.example" else "secondary.example",
        port = 22,
        username = "operator",
        protocol = ConnectionProtocol.SSH,
        credentialId = credentialId,
        createdAtEpochMillis = CREATED_AT,
        updatedAtEpochMillis = updatedAt,
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

    private class MutableClock(var now: Long) : CredentialEpochClock {
        override fun nowEpochMillis(): Long = now
    }

    private class RecordingCredentialStore(
        private val database: AppDatabase,
    ) : CredentialStore {
        val encryptInsideTransactions = mutableListOf<Boolean>()

        override suspend fun encryptAndWipe(
            reference: CredentialSecretReference,
            secret: ByteArray,
        ): EncryptedCredentialRecord = try {
            encryptInsideTransactions += database.inTransaction()
            EncryptedCredentialRecord(
                secretId = reference.secretId.value,
                kindCode = reference.kind.wireCode,
                envelopeVersion = 2,
                keyVersion = 2,
                nonce = ByteArray(12) { 7 },
                ciphertext = ByteArray(32) { 9 },
            )
        } finally {
            secret.fill(0)
        }

        override suspend fun saveAndWipe(
            reference: CredentialSecretReference,
            secret: ByteArray,
        ) {
            secret.fill(0)
            error("Not used by the aggregate test")
        }

        override suspend fun <T> withSecret(
            reference: CredentialSecretReference,
            use: suspend (ByteArray) -> T,
        ): T = error("Not used by the aggregate test")

        override suspend fun delete(secretId: SecretId): Boolean =
            error("Not used by the aggregate test")
    }

    private class InjectedFailure : RuntimeException()

    private companion object {
        const val CREATED_AT = 10L
        const val UPDATED_AT = 30L
        const val HOST_ID = "40000000-0000-4000-8000-000000000001"
        const val SECOND_HOST_ID = "40000000-0000-4000-8000-000000000002"
        const val CREDENTIAL_ID = "40000000-0000-4000-8000-000000000003"
        const val SPLIT_CREDENTIAL_ID = "40000000-0000-4000-8000-000000000004"
        const val SECRET_ID = "40000000-0000-4000-8000-000000000005"
        const val SECOND_SECRET_ID = "40000000-0000-4000-8000-000000000006"
        const val NON_PASSWORD_CREDENTIAL_ID = "40000000-0000-4000-8000-000000000007"
        const val NON_PASSWORD_SECRET_ID = "40000000-0000-4000-8000-000000000008"
        const val IDENTITY_ID = "40000000-0000-4000-8000-000000000009"
        const val PRIVATE_KEY_SECRET_ID = "40000000-0000-4000-8000-00000000000a"
        const val PRIVATE_KEY_CREDENTIAL_ID = "40000000-0000-4000-8000-00000000000b"
        const val PASSPHRASE_SECRET_ID = "40000000-0000-4000-8000-00000000000c"
        const val REPLACEMENT_PASSWORD_CREDENTIAL_ID = "40000000-0000-4000-8000-00000000000d"
        const val REPLACEMENT_PASSWORD_SECRET_ID = "40000000-0000-4000-8000-00000000000e"
        const val ZERO_SHA256_FINGERPRINT =
            "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
