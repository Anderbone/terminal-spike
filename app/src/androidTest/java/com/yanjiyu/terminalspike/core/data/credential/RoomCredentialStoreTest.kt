package com.yanjiyu.terminalspike.core.data.credential

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.db.HostProfileEntity
import com.yanjiyu.terminalspike.core.data.db.SshCredentialEntity
import com.yanjiyu.terminalspike.core.data.db.SshKeyIdentityEntity
import com.yanjiyu.terminalspike.core.security.credential.CredentialId
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.CredentialStore
import com.yanjiyu.terminalspike.core.security.credential.EncryptedCredentialRecord
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomCredentialStoreTest {
    private lateinit var database: AppDatabase
    private lateinit var clock: MutableClock

    @Before
    fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        clock = MutableClock(CREATED_AT)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun roomCiphertextRoundTripUsesDefensiveCopiesAndPreservesCreatedTimeOnUpdate() = runBlocking {
        val store = RoomCredentialCiphertextStore(database, clock)
        val original = encrypted(PASSWORD_SECRET_ID, CredentialSecretKind.PASSWORD, fill = 3)

        store.write(original)

        val inserted = requireNotNull(database.credentialRecordDao().findSecretById(PASSWORD_SECRET_ID))
        assertEquals(CREATED_AT, inserted.createdAtEpochMillis)
        assertEquals(CREATED_AT, inserted.updatedAtEpochMillis)
        val exposed = original.copyCiphertext().apply { fill(0) }
        assertTrue(exposed.all { it == 0.toByte() })
        assertTrue(requireNotNull(inserted.ciphertext).all { it == 3.toByte() })

        clock.now = UPDATED_AT
        val replacement = encrypted(PASSWORD_SECRET_ID, CredentialSecretKind.PASSWORD, fill = 7)
        store.write(replacement)

        val updated = requireNotNull(database.credentialRecordDao().findSecretById(PASSWORD_SECRET_ID))
        assertEquals(CREATED_AT, updated.createdAtEpochMillis)
        assertEquals(UPDATED_AT, updated.updatedAtEpochMillis)
        assertArrayEquals(ByteArray(32) { 7 }, store.read(secretId(PASSWORD_SECRET_ID))?.copyCiphertext())
    }

    @Test
    fun saveEncryptsBeforeTransactionThenCommitsSecretAndCredentialAtomically() = runBlocking {
        val crypto = RecordingCredentialStore(database)
        val aggregate = RoomCredentialAggregateStore(database, crypto, clock)
        val plaintext = "never sqlite plaintext".toByteArray()

        aggregate.saveCredentialAndSecret(
            reference = passwordReference(),
            secret = plaintext,
            credential = passwordCredential(),
        )

        assertTrue(crypto.encryptCalled)
        assertFalse(crypto.encryptCalledInsideTransaction)
        assertTrue(plaintext.all { it == 0.toByte() })
        val entity = requireNotNull(database.credentialRecordDao().findSecretById(PASSWORD_SECRET_ID))
        assertArrayEquals(RecordingCredentialStore.CIPHERTEXT, entity.ciphertext)
        assertFalse(requireNotNull(entity.ciphertext).contentEquals("never sqlite plaintext".toByteArray()))
        assertEquals(
            PASSWORD_SECRET_ID,
            database.credentialRecordDao().findCredentialById(CREDENTIAL_ID)?.secretId,
        )
    }

    @Test
    fun foreignKeyFailureRollsBackBothPassphraseSecretAndCredential() = runBlocking {
        val aggregate = aggregate()
        val reference = CredentialSecretReference(
            credentialId = credentialId(CREDENTIAL_ID),
            secretId = secretId(PASSPHRASE_SECRET_ID),
            kind = CredentialSecretKind.KEY_PASSPHRASE,
        )
        val missingIdentityCredential = SshCredentialEntity(
            id = CREDENTIAL_ID,
            name = "Key login",
            kindCode = "private_key",
            secretId = PASSPHRASE_SECRET_ID,
            keyIdentityId = MISSING_IDENTITY_ID,
            createdAtEpochMillis = CREATED_AT,
            updatedAtEpochMillis = CREATED_AT,
        )

        expectConstraint {
            aggregate.commitCredentialAndSecret(
                reference,
                encrypted(PASSPHRASE_SECRET_ID, CredentialSecretKind.KEY_PASSPHRASE),
                missingIdentityCredential,
            )
        }

        assertNull(database.credentialRecordDao().findSecretById(PASSPHRASE_SECRET_ID))
        assertNull(database.credentialRecordDao().findCredentialById(CREDENTIAL_ID))
    }

    @Test
    fun clearingCredentialReferenceAndDeletingItsLastSecretIsOneTransaction() = runBlocking {
        val aggregate = aggregate()
        aggregate.commitCredentialAndSecret(
            passwordReference(),
            encrypted(PASSWORD_SECRET_ID, CredentialSecretKind.PASSWORD),
            passwordCredential(),
        )
        assertFalse(aggregate.deleteUnreferencedSecret(secretId(PASSWORD_SECRET_ID)))

        clock.now = UPDATED_AT
        val result = aggregate.clearCredentialSecretAndDeleteIfUnreferenced(
            credentialId(CREDENTIAL_ID),
        )

        assertEquals(ClearCredentialSecretResult(true, true, true), result)
        val retainedMetadata = requireNotNull(
            database.credentialRecordDao().findCredentialById(CREDENTIAL_ID),
        )
        assertNull(retainedMetadata.secretId)
        assertEquals(UPDATED_AT, retainedMetadata.updatedAtEpochMillis)
        assertNull(database.credentialRecordDao().findSecretById(PASSWORD_SECRET_ID))
    }

    @Test
    fun identityDeleteIsRestrictedAndRollsBackWithoutDanglingCredentialMetadata() = runBlocking {
        val aggregate = aggregate()
        val identityReference = CredentialSecretReference(
            credentialId = credentialId(IDENTITY_ID),
            secretId = secretId(PRIVATE_KEY_SECRET_ID),
            kind = CredentialSecretKind.PRIVATE_KEY,
        )
        aggregate.commitIdentityAndPrivateKey(
            identityReference,
            encrypted(PRIVATE_KEY_SECRET_ID, CredentialSecretKind.PRIVATE_KEY),
            keyIdentity(),
        )
        database.credentialRecordDao().insertCredential(
            SshCredentialEntity(
                id = CREDENTIAL_ID,
                name = "Key login",
                kindCode = "private_key",
                secretId = null,
                keyIdentityId = IDENTITY_ID,
                createdAtEpochMillis = CREATED_AT,
                updatedAtEpochMillis = CREATED_AT,
            ),
        )

        expectConstraint {
            aggregate.deleteIdentityAndUnreferencedPrivateSecret(credentialId(IDENTITY_ID))
        }

        assertNotNull(database.credentialRecordDao().findIdentityById(IDENTITY_ID))
        assertNotNull(database.credentialRecordDao().findSecretById(PRIVATE_KEY_SECRET_ID))
        assertEquals(
            IDENTITY_ID,
            database.credentialRecordDao().findCredentialById(CREDENTIAL_ID)?.keyIdentityId,
        )
    }

    @Test
    fun clearAllSavedCredentialsRemovesEveryKindButPreservesPromptingHosts() = runBlocking {
        seedBulkCredentialGraph(database)
        val aggregate = aggregate()

        assertEquals(
            SavedCredentialClearPreview(credentialCount = 3, keyIdentityCount = 1),
            aggregate.previewClearAllSavedCredentials(),
        )
        val result = aggregate.clearAllSavedCredentials()

        assertEquals(4, result.hostReferencesDetached)
        assertEquals(3, result.credentialMetadataDeleted)
        assertEquals(1, result.keyIdentitiesDeleted)
        assertEquals(4, result.secretEnvelopesDeleted)
        listOf(CREDENTIAL_ID, KEYBOARD_CREDENTIAL_ID, PRIVATE_KEY_CREDENTIAL_ID).forEach { id ->
            assertNull(database.credentialRecordDao().findCredentialById(id))
        }
        assertNull(database.credentialRecordDao().findIdentityById(IDENTITY_ID))
        listOf(
            PASSWORD_SECRET_ID,
            KEYBOARD_SECRET_ID,
            PASSPHRASE_SECRET_ID,
            PRIVATE_KEY_SECRET_ID,
        ).forEach { id ->
            assertNull(database.credentialRecordDao().findSecretById(id))
        }
        BULK_HOST_IDS.forEach { id ->
            assertNotNull(database.hostProfileDao().findById(id))
            assertNull(database.hostProfileDao().findById(id)?.credentialId)
        }
    }

    @Test
    fun clearAllSavedCredentialsRollsBackAtEveryTransactionStage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CredentialBulkClearCheckpoint.entries.forEach { failingCheckpoint ->
            val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                seedBulkCredentialGraph(target)
                val aggregate = RoomCredentialAggregateStore(
                    database = target,
                    credentialStore = RecordingCredentialStore(target),
                    clock = clock,
                    afterBulkClearStep = { checkpoint ->
                        if (checkpoint == failingCheckpoint) throw ExpectedBulkClearFailure()
                    },
                )

                val failure = runCatching { aggregate.clearAllSavedCredentials() }.exceptionOrNull()

                assertTrue(
                    "Expected rollback failure at $failingCheckpoint",
                    failure is ExpectedBulkClearFailure,
                )
                assertEquals(
                    SavedCredentialClearPreview(credentialCount = 3, keyIdentityCount = 1),
                    aggregate.previewClearAllSavedCredentials(),
                )
                BULK_HOST_IDS.forEach { id ->
                    assertNotNull(target.hostProfileDao().findById(id)?.credentialId)
                }
                listOf(
                    PASSWORD_SECRET_ID,
                    KEYBOARD_SECRET_ID,
                    PASSPHRASE_SECRET_ID,
                    PRIVATE_KEY_SECRET_ID,
                ).forEach { id ->
                    assertNotNull(target.credentialRecordDao().findSecretById(id))
                }
            } finally {
                target.close()
            }
        }
    }

    @Test
    fun aSecretUuidCannotBeReboundToAnotherCredentialOrAnIdentity() = runBlocking {
        val aggregate = aggregate()
        aggregate.commitCredentialAndSecret(
            passwordReference(),
            encrypted(PASSWORD_SECRET_ID, CredentialSecretKind.PASSWORD, fill = 3),
            passwordCredential(),
        )

        val secondReference = CredentialSecretReference(
            credentialId = credentialId(SECOND_CREDENTIAL_ID),
            secretId = secretId(PASSWORD_SECRET_ID),
            kind = CredentialSecretKind.PASSWORD,
        )
        val credentialFailure = expectFailure<CredentialAggregateException.Malformed> {
            aggregate.commitCredentialAndSecret(
                secondReference,
                encrypted(PASSWORD_SECRET_ID, CredentialSecretKind.PASSWORD, fill = 8),
                passwordCredential().copy(id = SECOND_CREDENTIAL_ID, name = "Second"),
            )
        }
        assertEquals(CredentialAggregateMalformedReason.REFERENCE, credentialFailure.reason)

        val identityReference = CredentialSecretReference(
            credentialId = credentialId(IDENTITY_ID),
            secretId = secretId(PASSWORD_SECRET_ID),
            kind = CredentialSecretKind.PRIVATE_KEY,
        )
        val identityFailure = expectFailure<CredentialAggregateException.Malformed> {
            aggregate.commitIdentityAndPrivateKey(
                identityReference,
                encrypted(PASSWORD_SECRET_ID, CredentialSecretKind.PRIVATE_KEY, fill = 9),
                keyIdentity().copy(privateSecretId = PASSWORD_SECRET_ID),
            )
        }
        assertEquals(CredentialAggregateMalformedReason.REFERENCE, identityFailure.reason)

        val retainedSecret = requireNotNull(
            database.credentialRecordDao().findSecretById(PASSWORD_SECRET_ID),
        )
        assertArrayEquals(ByteArray(32) { 3 }, retainedSecret.ciphertext)
        assertEquals(
            PASSWORD_SECRET_ID,
            database.credentialRecordDao().findCredentialById(CREDENTIAL_ID)?.secretId,
        )
        assertNull(database.credentialRecordDao().findCredentialById(SECOND_CREDENTIAL_ID))
        assertNull(database.credentialRecordDao().findIdentityById(IDENTITY_ID))
    }

    @Test
    fun unsupportedReadyVersionsAreRejectedBeforeMutatingExistingRows() = runBlocking {
        val aggregate = aggregate()
        aggregate.commitCredentialAndSecret(
            passwordReference(),
            encrypted(PASSWORD_SECRET_ID, CredentialSecretKind.PASSWORD, fill = 4),
            passwordCredential(),
        )
        val changedMetadata = passwordCredential().copy(name = "Must not persist")

        val envelopeFailure = expectFailure<PersistedCredentialException.Malformed> {
            aggregate.commitCredentialAndSecret(
                passwordReference(),
                encrypted(
                    PASSWORD_SECRET_ID,
                    CredentialSecretKind.PASSWORD,
                    fill = 7,
                    envelopeVersion = 1,
                ),
                changedMetadata,
            )
        }
        assertEquals(PersistedCredentialMalformedReason.ENVELOPE_VERSION, envelopeFailure.reason)
        val keyFailure = expectFailure<PersistedCredentialException.Malformed> {
            aggregate.commitCredentialAndSecret(
                passwordReference(),
                encrypted(
                    PASSWORD_SECRET_ID,
                    CredentialSecretKind.PASSWORD,
                    fill = 8,
                    keyVersion = 3,
                ),
                changedMetadata,
            )
        }
        assertEquals(PersistedCredentialMalformedReason.KEY_VERSION, keyFailure.reason)

        assertEquals(
            "Password",
            database.credentialRecordDao().findCredentialById(CREDENTIAL_ID)?.name,
        )
        assertArrayEquals(
            ByteArray(32) { 4 },
            database.credentialRecordDao().findSecretById(PASSWORD_SECRET_ID)?.ciphertext,
        )
    }

    private fun aggregate() = RoomCredentialAggregateStore(
        database = database,
        credentialStore = RecordingCredentialStore(database),
        clock = clock,
    )

    private suspend fun seedBulkCredentialGraph(target: AppDatabase) {
        val aggregate = RoomCredentialAggregateStore(
            database = target,
            credentialStore = RecordingCredentialStore(target),
            clock = clock,
        )
        aggregate.commitIdentityAndPrivateKey(
            reference = CredentialSecretReference(
                credentialId = credentialId(IDENTITY_ID),
                secretId = secretId(PRIVATE_KEY_SECRET_ID),
                kind = CredentialSecretKind.PRIVATE_KEY,
            ),
            encrypted = encrypted(PRIVATE_KEY_SECRET_ID, CredentialSecretKind.PRIVATE_KEY),
            identity = keyIdentity(),
        )
        aggregate.commitCredentialAndSecret(
            reference = passwordReference(),
            encrypted = encrypted(PASSWORD_SECRET_ID, CredentialSecretKind.PASSWORD),
            credential = passwordCredential(),
        )
        aggregate.commitCredentialAndSecret(
            reference = CredentialSecretReference(
                credentialId = credentialId(KEYBOARD_CREDENTIAL_ID),
                secretId = secretId(KEYBOARD_SECRET_ID),
                kind = CredentialSecretKind.KEYBOARD_INTERACTIVE,
            ),
            encrypted = encrypted(KEYBOARD_SECRET_ID, CredentialSecretKind.KEYBOARD_INTERACTIVE),
            credential = SshCredentialEntity(
                id = KEYBOARD_CREDENTIAL_ID,
                name = "Keyboard interactive",
                kindCode = "keyboard_interactive",
                secretId = KEYBOARD_SECRET_ID,
                keyIdentityId = null,
                createdAtEpochMillis = CREATED_AT,
                updatedAtEpochMillis = CREATED_AT,
            ),
        )
        aggregate.commitCredentialAndSecret(
            reference = CredentialSecretReference(
                credentialId = credentialId(PRIVATE_KEY_CREDENTIAL_ID),
                secretId = secretId(PASSPHRASE_SECRET_ID),
                kind = CredentialSecretKind.KEY_PASSPHRASE,
            ),
            encrypted = encrypted(PASSPHRASE_SECRET_ID, CredentialSecretKind.KEY_PASSPHRASE),
            credential = SshCredentialEntity(
                id = PRIVATE_KEY_CREDENTIAL_ID,
                name = "Private key",
                kindCode = "private_key",
                secretId = PASSPHRASE_SECRET_ID,
                keyIdentityId = IDENTITY_ID,
                createdAtEpochMillis = CREATED_AT,
                updatedAtEpochMillis = CREATED_AT,
            ),
        )
        listOf(
            BULK_HOST_IDS[0] to CREDENTIAL_ID,
            BULK_HOST_IDS[1] to CREDENTIAL_ID,
            BULK_HOST_IDS[2] to KEYBOARD_CREDENTIAL_ID,
            BULK_HOST_IDS[3] to PRIVATE_KEY_CREDENTIAL_ID,
        ).forEach { (hostId, credentialId) ->
            target.hostProfileDao().insert(
                HostProfileEntity(
                    id = hostId,
                    displayName = "Credential host",
                    hostname = "example.test",
                    port = 22,
                    username = "tester",
                    protocolCode = "ssh",
                    credentialId = credentialId,
                    terminalProfileId = null,
                    keyboardProfileId = null,
                    isFavorite = false,
                    groupName = null,
                    tag = null,
                    startupCommand = null,
                    keepaliveIntervalSeconds = null,
                    reconnectPolicyCode = null,
                    moshPortStart = null,
                    moshPortEnd = null,
                    moshServerCommand = null,
                    createdAtEpochMillis = CREATED_AT,
                    updatedAtEpochMillis = CREATED_AT,
                ),
            )
        }
    }

    private fun passwordReference() = CredentialSecretReference(
        credentialId = credentialId(CREDENTIAL_ID),
        secretId = secretId(PASSWORD_SECRET_ID),
        kind = CredentialSecretKind.PASSWORD,
    )

    private fun passwordCredential() = SshCredentialEntity(
        id = CREDENTIAL_ID,
        name = "Password",
        kindCode = "password",
        secretId = PASSWORD_SECRET_ID,
        keyIdentityId = null,
        createdAtEpochMillis = CREATED_AT,
        updatedAtEpochMillis = CREATED_AT,
    )

    private fun keyIdentity() = SshKeyIdentityEntity(
        id = IDENTITY_ID,
        name = "Work key",
        algorithmCode = "ssh_ed25519",
        fingerprint = "SHA256:test",
        publicKey = byteArrayOf(1, 2, 3),
        provenanceCode = "imported",
        isPassphraseProtected = false,
        comment = null,
        privateSecretId = PRIVATE_KEY_SECRET_ID,
        createdAtEpochMillis = CREATED_AT,
        updatedAtEpochMillis = CREATED_AT,
    )

    private fun encrypted(
        id: String,
        kind: CredentialSecretKind,
        fill: Int = 5,
        envelopeVersion: Int = 2,
        keyVersion: Int = 2,
    ) = EncryptedCredentialRecord(
        secretId = id,
        kindCode = kind.wireCode,
        envelopeVersion = envelopeVersion,
        keyVersion = keyVersion,
        nonce = ByteArray(12) { 1 },
        ciphertext = ByteArray(32) { fill.toByte() },
    )

    private suspend fun expectConstraint(block: suspend () -> Unit) {
        var failure: Throwable? = null
        try {
            block()
        } catch (caught: Throwable) {
            failure = caught
        }
        val thrown = failure ?: throw AssertionError("Expected a SQLite constraint failure")
        assertTrue(
            "Expected SQLiteConstraintException but got ${thrown::class.java.name}",
            generateSequence(thrown) { it.cause }.any { it is SQLiteConstraintException },
        )
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

    private fun credentialId(value: String) = CredentialId.parseCanonical(value)

    private fun secretId(value: String) = SecretId.parseCanonical(value)

    private class MutableClock(var now: Long) : CredentialEpochClock {
        override fun nowEpochMillis(): Long = now
    }

    private class RecordingCredentialStore(
        private val database: AppDatabase,
    ) : CredentialStore {
        var encryptCalled = false
        var encryptCalledInsideTransaction = false

        override suspend fun encryptAndWipe(
            reference: CredentialSecretReference,
            secret: ByteArray,
        ): EncryptedCredentialRecord {
            encryptCalled = true
            encryptCalledInsideTransaction = database.inTransaction()
            secret.fill(0)
            return EncryptedCredentialRecord(
                secretId = reference.secretId.value,
                kindCode = reference.kind.wireCode,
                envelopeVersion = 2,
                keyVersion = 2,
                nonce = ByteArray(12) { 6 },
                ciphertext = CIPHERTEXT,
            )
        }

        override suspend fun saveAndWipe(reference: CredentialSecretReference, secret: ByteArray) {
            throw AssertionError("Aggregate store must encrypt before persisting")
        }

        override suspend fun <T> withSecret(
            reference: CredentialSecretReference,
            use: suspend (ByteArray) -> T,
        ): T = throw AssertionError("Not used")

        override suspend fun delete(secretId: SecretId): Boolean = false

        companion object {
            val CIPHERTEXT = ByteArray(32) { 11 }
        }
    }

    private class ExpectedBulkClearFailure : RuntimeException()

    private companion object {
        const val CREATED_AT = 1_000L
        const val UPDATED_AT = 2_000L
        const val CREDENTIAL_ID = "ce13ab93-7597-4d9c-b66a-5ca16ea7a535"
        const val SECOND_CREDENTIAL_ID = "9af27d7b-a51f-437a-bf5c-210bd31ef459"
        const val KEYBOARD_CREDENTIAL_ID = "c15f8585-f770-4bb9-a4ae-bca86c8241be"
        const val PRIVATE_KEY_CREDENTIAL_ID = "f4e2470e-4510-43df-a862-4ddd5aa236cc"
        const val IDENTITY_ID = "52c7bc84-51ac-4ea7-82fd-58eae4708ada"
        const val MISSING_IDENTITY_ID = "f056b41c-1b29-467a-a8ae-405bbb7cb7b0"
        const val PASSWORD_SECRET_ID = "89a95a82-5149-43bc-80b1-14b1ca457f99"
        const val PASSPHRASE_SECRET_ID = "f9739c05-6b93-47c3-b72e-9f26594c3c09"
        const val KEYBOARD_SECRET_ID = "a17c43e1-6758-4f79-b846-a2d3862a4ccc"
        const val PRIVATE_KEY_SECRET_ID = "093a872b-43ac-491f-9f97-431660828b20"
        val BULK_HOST_IDS = listOf(
            "0b93941e-dd5c-4d0a-83a9-9999120687b0",
            "b9eb79e8-57a3-4c99-818c-e2aaea11cd30",
            "17ce530b-27e7-43e9-9a33-a5816fc9299e",
            "e9bf913c-4e76-4565-9d98-0c92768cae37",
        )
    }
}
