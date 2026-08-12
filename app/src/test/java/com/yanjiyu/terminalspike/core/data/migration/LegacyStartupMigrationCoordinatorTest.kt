package com.yanjiyu.terminalspike.core.data.migration

import com.yanjiyu.terminalspike.core.data.credential.CredentialEpochClock
import com.yanjiyu.terminalspike.core.data.db.LegacyMigrationBatch
import com.yanjiyu.terminalspike.core.data.db.LegacyMigrationInsertResult
import com.yanjiyu.terminalspike.core.data.db.LegacyMigrationStateEntity
import com.yanjiyu.terminalspike.core.security.credential.AesGcmCredentialStore
import com.yanjiyu.terminalspike.core.security.credential.CredentialCiphertextStore
import com.yanjiyu.terminalspike.core.security.credential.CredentialKeyProvider
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.EncryptedCredentialRecord
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.SavedSshIdentity
import com.yanjiyu.terminalspike.settings.SshPasswordScope
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyStartupMigrationCoordinatorTest {
    @Test
    fun reencryptionWipesLegacyCleartextAndReopensTheNewEnvelope() = runTest {
        val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val keys = FixedKeyProvider(key, key)
        val reencryptor = AesGcmLegacySecretReencryptor(keys)
        val reference = passwordReference()
        val expected = "legacy password".toByteArray()
        val consumed = expected.copyOf()

        val encrypted = reencryptor.reencryptAndVerify(reference, consumed)

        assertAllZero(consumed)
        val records = MemoryCiphertextStore(encrypted)
        AesGcmCredentialStore(records, keys).withSecret(reference) { reopened ->
            assertArrayEquals(expected, reopened)
        }
        expected.fill(0)
    }

    @Test
    fun reopenFailureIsTypedAndStillWipesLegacyCleartext() = runTest {
        val encryptionKey = SecretKeySpec(ByteArray(32) { 1 }, "AES")
        val wrongDecryptionKey = SecretKeySpec(ByteArray(32) { 2 }, "AES")
        val reencryptor = AesGcmLegacySecretReencryptor(
            FixedKeyProvider(encryptionKey, wrongDecryptionKey),
        )
        val consumed = "must be wiped".toByteArray()

        val failure = expectFailure<LegacySecretReencryptionException> {
            reencryptor.reencryptAndVerify(passwordReference(), consumed)
        }

        assertEquals(LegacySecretReencryptionStage.REOPEN, failure.stage)
        assertAllZero(consumed)
    }

    @Test
    fun failedTargetTransactionRecordsBlockedThenRetryAndColdRestartAreIdempotent() = runTest {
        val settings = LoadedSettingsSource()
        val secrets = LoadedSecretsSource()
        val target = FakeMigrationStore(failNextInsert = true)
        val coordinator = coordinator(target, settings, secrets)

        val failed = coordinator.migrate()

        assertEquals(LegacyMigrationSourceOutcome.BLOCKED, failed.userSettings.outcome)
        assertEquals("target_transaction_failed", failed.userSettings.errorCode)
        assertTrue(target.batchesFor(USER_SETTINGS_SOURCE).isEmpty())
        assertEquals("blocked", target.states.getValue(USER_SETTINGS_SOURCE).stateCode)
        assertEquals(SETTINGS_DIGEST, target.states.getValue(USER_SETTINGS_SOURCE).sourceDigestSha256)
        assertAllZero(secrets.returnedCleartexts.single())

        val retried = coordinator.migrate()

        assertEquals(LegacyMigrationSourceOutcome.APPLIED, retried.userSettings.outcome)
        assertEquals(1, target.batchesFor(USER_SETTINGS_SOURCE).size)
        val completion = target.states.getValue(USER_SETTINGS_SOURCE)
        assertEquals("complete", completion.stateCode)
        assertEquals(SETTINGS_DIGEST, completion.sourceDigestSha256)
        assertNotNull(target.batchesFor(USER_SETTINGS_SOURCE).single().secrets.single().ciphertext)
        assertEquals(2, secrets.passwordReads)

        val afterRestart = coordinator(target, settings, secrets).migrate()

        assertEquals(LegacyMigrationSourceOutcome.ALREADY_APPLIED, afterRestart.userSettings.outcome)
        assertEquals(1, target.batchesFor(USER_SETTINGS_SOURCE).size)
        assertEquals(2, secrets.passwordReads)
        assertEquals(2, settings.reads)
    }

    @Test
    fun completedKnownHostsAreAuthoritativeBeforeRetainedSourceIsOpenedAgain() = runTest {
        val target = FakeMigrationStore()
        val knownHosts = MutableKnownHostsSource(KNOWN_HOSTS_TEXT, KNOWN_HOSTS_DIGEST)
        val coordinator = coordinator(
            target = target,
            settings = MissingSettingsSource,
            secrets = MissingSecretsSource,
            knownHosts = knownHosts,
        )

        val first = coordinator.migrate()
        assertEquals(LegacyMigrationSourceOutcome.APPLIED, first.userSettings.outcome)
        assertEquals(LegacyMigrationSourceOutcome.APPLIED, first.knownHosts.outcome)
        assertEquals(
            1,
            target.committedBatches
                .single { it.completion.sourceCode == KNOWN_HOSTS_SOURCE }
                .knownHosts.size,
        )

        knownHosts.digest = "33".repeat(32)
        val changed = coordinator.migrate()

        assertEquals(LegacyMigrationSourceOutcome.ALREADY_APPLIED, changed.knownHosts.outcome)
        assertEquals(null, changed.knownHosts.errorCode)
        assertEquals(
            KNOWN_HOSTS_DIGEST,
            target.states.getValue(KNOWN_HOSTS_SOURCE).sourceDigestSha256,
        )
        assertEquals(2, target.committedBatches.size)
        assertEquals(1, knownHosts.reads)
    }

    @Test
    fun missingKnownHostsCommitAbsentAndIgnoreAFileThatAppearsAfterRestart() = runTest {
        val target = FakeMigrationStore()
        val knownHosts = MutableKnownHostsResultSource(LegacyKnownHostsReadResult.Missing)
        val first = coordinator(
            target = target,
            settings = MissingSettingsSource,
            secrets = MissingSecretsSource,
            knownHosts = knownHosts,
        ).migrate()

        assertEquals(LegacyMigrationSourceOutcome.APPLIED, first.knownHosts.outcome)
        assertEquals(
            LegacyMigrationStateEntity.STATE_ABSENT,
            target.states.getValue(KNOWN_HOSTS_SOURCE).stateCode,
        )
        assertTrue(target.batchesFor(KNOWN_HOSTS_SOURCE).single().knownHosts.isEmpty())
        assertEquals(1, knownHosts.reads)

        knownHosts.result = LegacyKnownHostsReadResult.Loaded(
            source = KNOWN_HOSTS_TEXT,
            sourceDigestSha256 = KNOWN_HOSTS_DIGEST,
        )
        val afterRestart = coordinator(
            target = target,
            settings = MissingSettingsSource,
            secrets = MissingSecretsSource,
            knownHosts = knownHosts,
        ).migrate()

        assertEquals(LegacyMigrationSourceOutcome.ALREADY_APPLIED, afterRestart.knownHosts.outcome)
        assertEquals(1, knownHosts.reads)
        assertTrue(target.batchesFor(KNOWN_HOSTS_SOURCE).single().knownHosts.isEmpty())
    }

    @Test
    fun freshMissingSourceSeedsResolvableDefaultsOnceAndMissingAfterBlockedDoesNotDefault() = runTest {
        val mutableFreshSource = MutableSettingsSource(LegacyUserSettingsReadResult.Missing)
        val freshTarget = FakeMigrationStore()
        val fresh = coordinator(freshTarget, mutableFreshSource, MissingSecretsSource)

        val first = fresh.migrate()
        mutableFreshSource.result = LegacyUserSettingsReadResult.Loaded(
            settings = UserSettings(
                profiles = listOf(
                    SavedSshProfile(
                        PROFILE_ID,
                        "Must not import",
                        "later.example",
                        22,
                        "operator",
                    ),
                ),
            ),
            sourceVersion = 1,
            sourceDigestSha256 = SETTINGS_DIGEST,
        )
        val second = fresh.migrate()

        assertEquals(LegacyMigrationSourceOutcome.APPLIED, first.userSettings.outcome)
        assertEquals(LegacyMigrationSourceOutcome.ALREADY_APPLIED, second.userSettings.outcome)
        assertEquals(1, freshTarget.batchesFor(USER_SETTINGS_SOURCE).size)
        val defaults = freshTarget.batchesFor(USER_SETTINGS_SOURCE).single()
        val terminalProfile = defaults.terminalProfiles.single()
        val keyboardProfile = defaults.keyboardProfiles.single()
        assertEquals(LegacyIds.defaultTerminalProfile, terminalProfile.id)
        assertEquals(LegacyIds.defaultKeyboardProfile, keyboardProfile.id)
        assertTrue(defaults.keyboardKeys.isNotEmpty())
        assertTrue(defaults.keyboardKeys.all { it.profileId == keyboardProfile.id })
        assertEquals(
            defaults.keyboardKeys.indices.toList(),
            defaults.keyboardKeys.map { it.position },
        )
        assertEquals("absent", freshTarget.states.getValue(USER_SETTINGS_SOURCE).stateCode)
        assertEquals(1, mutableFreshSource.reads)
        assertTrue(defaults.hosts.isEmpty())

        val mutableSource = MutableSettingsSource(
            LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.KEY_UNAVAILABLE),
        )
        val blockedTarget = FakeMigrationStore()
        val blocked = coordinator(blockedTarget, mutableSource, MissingSecretsSource)
        assertEquals(LegacyMigrationSourceOutcome.BLOCKED, blocked.migrate().userSettings.outcome)

        mutableSource.result = LegacyUserSettingsReadResult.Missing
        val stillBlocked = blocked.migrate()

        assertEquals(LegacyMigrationSourceOutcome.BLOCKED, stillBlocked.userSettings.outcome)
        assertEquals("settings_key_unavailable", stillBlocked.userSettings.errorCode)
        assertTrue(blockedTarget.batchesFor(USER_SETTINGS_SOURCE).isEmpty())
    }

    @Test
    fun absentMarkerRetriesAfterItsTargetTransactionFails() = runTest {
        val target = FakeMigrationStore(failNextInsert = true)
        val settings = MutableSettingsSource(LegacyUserSettingsReadResult.Missing)
        val coordinator = coordinator(target, settings, MissingSecretsSource)

        val failed = coordinator.migrate()
        val retried = coordinator.migrate()

        assertEquals(LegacyMigrationSourceOutcome.BLOCKED, failed.userSettings.outcome)
        assertEquals("target_transaction_failed", failed.userSettings.errorCode)
        assertEquals(LegacyMigrationSourceOutcome.APPLIED, retried.userSettings.outcome)
        assertEquals(
            LegacyMigrationStateEntity.STATE_ABSENT,
            target.states.getValue(USER_SETTINGS_SOURCE).stateCode,
        )
        assertEquals(2, settings.reads)
        assertEquals(1, target.batchesFor(USER_SETTINGS_SOURCE).size)
    }

    @Test
    fun explicitRecoveryDiscardSeedsSettingsAndSurvivesColdRestartWithoutSourceReads() = runTest {
        val target = FakeMigrationStore()
        val settings = MutableSettingsSource(
            LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.CORRUPT),
        )
        val knownHosts = MutableKnownHostsResultSource(
            LegacyKnownHostsReadResult.Blocked(LegacyKnownHostsFailure.CORRUPT),
        )
        val coordinator = coordinator(
            target = target,
            settings = settings,
            secrets = MissingSecretsSource,
            knownHosts = knownHosts,
        )
        val blocked = coordinator.migrate()
        assertEquals(LegacyMigrationSourceOutcome.BLOCKED, blocked.userSettings.outcome)
        assertEquals(LegacyMigrationSourceOutcome.BLOCKED, blocked.knownHosts.outcome)

        settings.result = LegacyUserSettingsReadResult.Loaded(
            settings = UserSettings(
                profiles = listOf(
                    SavedSshProfile(
                        PROFILE_ID,
                        "Retained settings",
                        "retained.example",
                        22,
                        "operator",
                    ),
                ),
            ),
            sourceVersion = 1,
            sourceDigestSha256 = SETTINGS_DIGEST,
        )
        knownHosts.result = LegacyKnownHostsReadResult.Loaded(
            source = KNOWN_HOSTS_TEXT,
            sourceDigestSha256 = KNOWN_HOSTS_DIGEST,
        )
        val discardedSettings = coordinator.discardUserSettingsAfterRecovery()
        val discardedKnownHosts = coordinator.discardKnownHostsAfterRecovery()

        assertEquals(LegacyMigrationSourceOutcome.APPLIED, discardedSettings.outcome)
        assertEquals(LegacyMigrationSourceOutcome.APPLIED, discardedKnownHosts.outcome)
        assertEquals(
            LegacyMigrationStateEntity.STATE_DISCARDED_AFTER_RECOVERY,
            target.states.getValue(USER_SETTINGS_SOURCE).stateCode,
        )
        assertEquals(
            LegacyMigrationStateEntity.STATE_DISCARDED_AFTER_RECOVERY,
            target.states.getValue(KNOWN_HOSTS_SOURCE).stateCode,
        )
        val settingsDiscard = target.batchesFor(USER_SETTINGS_SOURCE).single()
        assertEquals(LegacyIds.defaultTerminalProfile, settingsDiscard.terminalProfiles.single().id)
        assertEquals(LegacyIds.defaultKeyboardProfile, settingsDiscard.keyboardProfiles.single().id)
        assertTrue(settingsDiscard.keyboardKeys.isNotEmpty())
        assertTrue(settingsDiscard.hosts.isEmpty())
        assertTrue(target.batchesFor(KNOWN_HOSTS_SOURCE).single().knownHosts.isEmpty())
        assertEquals(1, settings.reads)
        assertEquals(1, knownHosts.reads)

        val afterRestart = coordinator(
            target = target,
            settings = settings,
            secrets = MissingSecretsSource,
            knownHosts = knownHosts,
        ).migrate()

        assertEquals(LegacyMigrationSourceOutcome.ALREADY_APPLIED, afterRestart.userSettings.outcome)
        assertEquals(LegacyMigrationSourceOutcome.ALREADY_APPLIED, afterRestart.knownHosts.outcome)
        assertEquals(1, settings.reads)
        assertEquals(1, knownHosts.reads)
    }

    @Test
    fun recoveryDiscardRequiresAnExistingBlockedMarker() = runTest {
        val coordinator = coordinator(
            target = FakeMigrationStore(),
            settings = MissingSettingsSource,
            secrets = MissingSecretsSource,
        )

        val failure = expectFailure<LegacyRecoveryDiscardUnavailableException> {
            coordinator.discardUserSettingsAfterRecovery()
        }

        assertEquals(USER_SETTINGS_SOURCE, failure.sourceCode)
    }

    @Test
    fun individualSecretFailuresAreRetainedAndDerivedPublicMetadataStillCommits() = runTest {
        val password = "legacy password".toByteArray()
        val privateKey = "legacy private key".toByteArray()
        val target = FakeMigrationStore()
        val settings = LegacyUserSettingsSource {
            LegacyUserSettingsReadResult.Loaded(
                settings = UserSettings(
                    profiles = listOf(
                        SavedSshProfile(
                            PROFILE_ID,
                            "Production",
                            "shell.example",
                            22,
                            "operator",
                            hasSavedPassword = true,
                        ),
                    ),
                    identities = listOf(
                        SavedSshIdentity(
                            IDENTITY_ID,
                            "Production key",
                            "legacy",
                            "legacy-fingerprint",
                            passphraseRequired = false,
                        ),
                    ),
                    extraKeys = listOf(TerminalExtraKey.ESC),
                ),
                sourceVersion = 3,
                sourceDigestSha256 = SETTINGS_DIGEST,
            )
        }
        val secrets = object : LegacySecretsSource {
            override fun readPassword(scope: SshPasswordScope) =
                LegacySecretReadResult.Loaded(password)

            override fun readPrivateKey(identityId: Long) =
                LegacySecretReadResult.Loaded(privateKey)
        }
        val reencryptor = LegacySecretReencryptor { reference, cleartext ->
            cleartext.fill(0)
            if (reference.kind == CredentialSecretKind.PASSWORD) {
                throw LegacySecretReencryptionException(
                    LegacySecretReencryptionStage.ENCRYPT,
                    com.yanjiyu.terminalspike.core.security.credential.CredentialStoreException
                        .KeyUnavailable(reference.secretId),
                )
            }
            throw LegacySecretReencryptionException(
                LegacySecretReencryptionStage.REOPEN,
                com.yanjiyu.terminalspike.core.security.credential.CredentialStoreException
                    .Tampered(reference.secretId),
            )
        }
        val publicKey = sshPublicKeyBlob("ssh-ed25519", byteArrayOf(1, 3, 3, 7))
        val fingerprint = publicKey.sha256Fingerprint()
        val result = coordinator(
            target = target,
            settings = settings,
            secrets = secrets,
            reencryptor = reencryptor,
            metadataDeriver = LegacyPrivateKeyMetadataDeriver {
                LegacyPrivateKeyMetadata("ssh-ed25519", fingerprint, publicKey)
            },
        ).migrate()

        assertEquals(LegacyMigrationSourceOutcome.APPLIED, result.userSettings.outcome)
        val batch = target.batchesFor(USER_SETTINGS_SOURCE).single()
        assertEquals(
            listOf("key_unavailable", "tampered"),
            batch.secrets.mapNotNull { it.failureCode }.sorted(),
        )
        assertTrue(batch.secrets.all { it.stateCode == "legacy_unavailable" })
        assertTrue(batch.secrets.all { it.envelopeVersion == 0 && it.keyVersion == 0 })
        assertEquals("complete_with_warnings", batch.completion.stateCode)
        assertEquals("ssh-ed25519", batch.keyIdentities.single().algorithmCode)
        assertEquals(fingerprint, batch.keyIdentities.single().fingerprint)
        assertArrayEquals(publicKey, batch.keyIdentities.single().publicKey)
        assertAllZero(password)
        assertAllZero(privateKey)
    }

    @Test
    fun blockedSettingsDoNotDefaultOrPreventIndependentKnownHostMigration() = runTest {
        val target = FakeMigrationStore()
        val result = coordinator(
            target = target,
            settings = LegacyUserSettingsSource {
                LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.CORRUPT)
            },
            secrets = MissingSecretsSource,
            knownHosts = MutableKnownHostsSource(KNOWN_HOSTS_TEXT, KNOWN_HOSTS_DIGEST),
        ).migrate()

        assertEquals(LegacyMigrationSourceOutcome.BLOCKED, result.userSettings.outcome)
        assertEquals("settings_corrupt", result.userSettings.errorCode)
        assertEquals("blocked", target.states.getValue(USER_SETTINGS_SOURCE).stateCode)
        assertEquals(LegacyMigrationSourceOutcome.APPLIED, result.knownHosts.outcome)
        assertEquals(1, target.committedBatches.size)
        assertTrue(target.committedBatches.single().terminalProfiles.isEmpty())
        assertEquals(1, target.committedBatches.single().knownHosts.size)
    }

    @Test
    fun targetIncompatibleSettingsRemainUnchangedAndBlockAsOneAggregate() = runTest {
        val loaded = LegacyUserSettingsReadResult.Loaded(
            settings = UserSettings(
                profiles = listOf(
                    SavedSshProfile(
                        id = PROFILE_ID,
                        label = "Production",
                        // The legacy codec accepted any non-whitespace host text.
                        host = "bad_.example",
                        port = 22,
                        username = "operator",
                    ),
                ),
                extraKeys = listOf(TerminalExtraKey.ESC),
            ),
            sourceVersion = 1,
            sourceDigestSha256 = SETTINGS_DIGEST,
        )
        var settingsReads = 0
        val target = FakeMigrationStore()

        val result = coordinator(
            target = target,
            settings = LegacyUserSettingsSource {
                settingsReads += 1
                loaded
            },
            secrets = MissingSecretsSource,
            knownHosts = MutableKnownHostsSource(KNOWN_HOSTS_TEXT, KNOWN_HOSTS_DIGEST),
        ).migrate()

        assertEquals(LegacyMigrationSourceOutcome.BLOCKED, result.userSettings.outcome)
        assertEquals("invalid_source", result.userSettings.errorCode)
        assertEquals("blocked", target.states.getValue(USER_SETTINGS_SOURCE).stateCode)
        assertEquals(SETTINGS_DIGEST, target.states.getValue(USER_SETTINGS_SOURCE).sourceDigestSha256)
        assertEquals(1, settingsReads)
        assertEquals("bad_.example", loaded.settings.profiles.single().host)
        assertEquals(LegacyMigrationSourceOutcome.APPLIED, result.knownHosts.outcome)
        assertEquals(1, target.committedBatches.size)
        assertEquals(KNOWN_HOSTS_SOURCE, target.committedBatches.single().completion.sourceCode)
    }

    private fun coordinator(
        target: FakeMigrationStore,
        settings: LegacyUserSettingsSource,
        secrets: LegacySecretsSource,
        knownHosts: LegacyKnownHostsSource = MissingKnownHostsSource,
        reencryptor: LegacySecretReencryptor = FakeReencryptor,
        metadataDeriver: LegacyPrivateKeyMetadataDeriver = LegacyPrivateKeyMetadataDeriver { null },
    ) = LegacyStartupMigrationCoordinator(
        store = target,
        userSettingsSource = settings,
        secretsSource = secrets,
        knownHostsSource = knownHosts,
        secretReencryptor = reencryptor,
        privateKeyMetadataDeriver = metadataDeriver,
        clock = CredentialEpochClock { TIMESTAMP },
    )

    private fun passwordReference() = CredentialSecretReference(
        credentialId = com.yanjiyu.terminalspike.core.security.credential.CredentialId.parseCanonical(
            LegacyIds.passwordCredential(PROFILE_ID),
        ),
        secretId = SecretId.parseCanonical(LegacyIds.passwordSecret(PROFILE_ID)),
        kind = CredentialSecretKind.PASSWORD,
    )

    private class LoadedSettingsSource : LegacyUserSettingsSource {
        var reads = 0

        override fun read(): LegacyUserSettingsReadResult {
            reads += 1
            return LegacyUserSettingsReadResult.Loaded(
                settings = UserSettings(
                    profiles = listOf(
                        SavedSshProfile(
                            id = PROFILE_ID,
                            label = "Production",
                            host = "shell.example",
                            port = 22,
                            username = "operator",
                            hasSavedPassword = true,
                        ),
                    ),
                    extraKeys = listOf(TerminalExtraKey.ESC),
                ),
                sourceVersion = 3,
                sourceDigestSha256 = SETTINGS_DIGEST,
            )
        }
    }

    private object MissingSettingsSource : LegacyUserSettingsSource {
        override fun read(): LegacyUserSettingsReadResult = LegacyUserSettingsReadResult.Missing
    }

    private class MutableSettingsSource(
        var result: LegacyUserSettingsReadResult,
    ) : LegacyUserSettingsSource {
        var reads = 0

        override fun read(): LegacyUserSettingsReadResult {
            reads += 1
            return result
        }
    }

    private class LoadedSecretsSource : LegacySecretsSource {
        var passwordReads = 0
        val returnedCleartexts = mutableListOf<ByteArray>()

        override fun readPassword(scope: SshPasswordScope): LegacySecretReadResult {
            passwordReads += 1
            return LegacySecretReadResult.Loaded(
                "legacy password".toByteArray().also(returnedCleartexts::add),
            )
        }

        override fun readPrivateKey(identityId: Long): LegacySecretReadResult =
            LegacySecretReadResult.Unavailable(LegacySecretFailure.MISSING)
    }

    private object MissingSecretsSource : LegacySecretsSource {
        override fun readPassword(scope: SshPasswordScope): LegacySecretReadResult =
            LegacySecretReadResult.Unavailable(LegacySecretFailure.MISSING)

        override fun readPrivateKey(identityId: Long): LegacySecretReadResult =
            LegacySecretReadResult.Unavailable(LegacySecretFailure.MISSING)
    }

    private class MutableKnownHostsSource(
        private val source: String,
        var digest: String,
    ) : LegacyKnownHostsSource {
        var reads = 0

        override fun read(): LegacyKnownHostsReadResult {
            reads += 1
            return LegacyKnownHostsReadResult.Loaded(
                source = source,
                sourceDigestSha256 = digest,
            )
        }
    }

    private class MutableKnownHostsResultSource(
        var result: LegacyKnownHostsReadResult,
    ) : LegacyKnownHostsSource {
        var reads = 0

        override fun read(): LegacyKnownHostsReadResult {
            reads += 1
            return result
        }
    }

    private object MissingKnownHostsSource : LegacyKnownHostsSource {
        override fun read(): LegacyKnownHostsReadResult = LegacyKnownHostsReadResult.Missing
    }

    private object FakeReencryptor : LegacySecretReencryptor {
        override suspend fun reencryptAndVerify(
            reference: CredentialSecretReference,
            cleartext: ByteArray,
        ): EncryptedCredentialRecord = EncryptedCredentialRecord(
            secretId = reference.secretId.value,
            kindCode = reference.kind.wireCode,
            envelopeVersion = 2,
            keyVersion = 2,
            nonce = ByteArray(12) { 4 },
            ciphertext = ByteArray(cleartext.size + 16) { 5 },
        )
    }

    private class FakeMigrationStore(
        var failNextInsert: Boolean = false,
    ) : LegacyMigrationStore {
        val states = mutableMapOf<String, LegacyMigrationStateEntity>()
        val committedBatches = mutableListOf<LegacyMigrationBatch>()

        override suspend fun findState(sourceCode: String): LegacyMigrationStateEntity? =
            states[sourceCode]

        override suspend fun recordBlocked(
            state: LegacyMigrationStateEntity,
        ): LegacyMigrationStateEntity {
            val existing = states[state.sourceCode]
            if (existing?.isSuccessfulCompletion() == true) return existing
            states[state.sourceCode] = state
            return state
        }

        override suspend fun insertBatch(batch: LegacyMigrationBatch): LegacyMigrationInsertResult {
            if (failNextInsert) {
                failNextInsert = false
                throw IllegalStateException("simulated transaction failure")
            }
            val existing = states[batch.completion.sourceCode]
            if (existing?.isSuccessfulCompletion() == true) {
                return LegacyMigrationInsertResult.ALREADY_APPLIED
            }
            committedBatches += batch
            states[batch.completion.sourceCode] = batch.completion
            return LegacyMigrationInsertResult.INSERTED
        }

        fun batchesFor(sourceCode: String): List<LegacyMigrationBatch> =
            committedBatches.filter { it.completion.sourceCode == sourceCode }
    }

    private class FixedKeyProvider(
        private val encryptionKey: SecretKey,
        private val decryptionKey: SecretKey,
    ) : CredentialKeyProvider {
        override fun getOrCreateEncryptionKey(keyVersion: Int): SecretKey = encryptionKey

        override fun getExistingDecryptionKey(keyVersion: Int): SecretKey = decryptionKey
    }

    private class MemoryCiphertextStore(
        private var record: EncryptedCredentialRecord?,
    ) : CredentialCiphertextStore {
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

    private fun assertAllZero(value: ByteArray) {
        assertTrue(value.all { it == 0.toByte() })
    }

    private fun sshPublicKeyBlob(algorithm: String, payload: ByteArray): ByteArray {
        val algorithmBytes = algorithm.toByteArray()
        return ByteBuffer.allocate(4 + algorithmBytes.size + payload.size)
            .putInt(algorithmBytes.size)
            .put(algorithmBytes)
            .put(payload)
            .array()
    }

    private fun ByteArray.sha256Fingerprint(): String = "SHA256:" +
        Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(this),
        )

    private suspend inline fun <reified T : Throwable> expectFailure(
        crossinline block: suspend () -> Unit,
    ): T {
        var failure: Throwable? = null
        try {
            block()
        } catch (caught: Throwable) {
            failure = caught
        }
        val thrown = failure ?: throw AssertionError("Expected ${T::class.java.simpleName}")
        assertTrue("Expected ${T::class.java.name}, got ${thrown::class.java.name}", thrown is T)
        return thrown as T
    }

    private companion object {
        const val PROFILE_ID = 7L
        const val IDENTITY_ID = 11L
        const val TIMESTAMP = 1_700_000_000_000L
        const val SETTINGS_DIGEST =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val KNOWN_HOSTS_DIGEST =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val KNOWN_HOSTS_TEXT = "example.test ssh-ed25519 AQID"
        const val USER_SETTINGS_SOURCE = "legacy_user_settings"
        const val KNOWN_HOSTS_SOURCE = "legacy_known_hosts"
    }
}
