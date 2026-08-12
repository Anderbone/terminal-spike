package com.yanjiyu.terminalspike.core.data.migration

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.core.data.credential.CredentialEpochClock
import com.yanjiyu.terminalspike.core.data.credential.RoomCredentialCiphertextStore
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.db.LegacyMigrationStateEntity
import com.yanjiyu.terminalspike.core.security.credential.AesGcmCredentialStore
import com.yanjiyu.terminalspike.core.security.credential.CredentialId
import com.yanjiyu.terminalspike.core.security.credential.CredentialKeyProvider
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.SshPasswordScope
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyStartupMigrationRoomTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun preparedLegacyPasswordCommitsThenReopensThroughRoomCredentialStore() = runBlocking {
        val expected = "migrated password".toByteArray()
        val consumed = expected.copyOf()
        val keys = FixedKeyProvider()
        val reference = CredentialSecretReference(
            credentialId = CredentialId.parseCanonical(LegacyIds.passwordCredential(PROFILE_ID)),
            secretId = SecretId.parseCanonical(LegacyIds.passwordSecret(PROFILE_ID)),
            kind = CredentialSecretKind.PASSWORD,
        )
        val coordinator = LegacyStartupMigrationCoordinator(
            store = RoomLegacyMigrationStore(database),
            userSettingsSource = LegacyUserSettingsSource {
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
                        extraKeys = listOf(TerminalExtraKey.ESC),
                    ),
                    sourceVersion = 3,
                    sourceDigestSha256 = SOURCE_DIGEST,
                )
            },
            secretsSource = object : LegacySecretsSource {
                override fun readPassword(scope: SshPasswordScope): LegacySecretReadResult =
                    LegacySecretReadResult.Loaded(consumed)

                override fun readPrivateKey(identityId: Long): LegacySecretReadResult =
                    LegacySecretReadResult.Unavailable(LegacySecretFailure.MISSING)
            },
            knownHostsSource = LegacyKnownHostsSource { LegacyKnownHostsReadResult.Missing },
            secretReencryptor = AesGcmLegacySecretReencryptor(keys),
            privateKeyMetadataDeriver = LegacyPrivateKeyMetadataDeriver { null },
            clock = CredentialEpochClock { TIMESTAMP },
        )

        val result = coordinator.migrate()

        assertEquals(LegacyMigrationSourceOutcome.APPLIED, result.userSettings.outcome)
        assertTrue(consumed.all { it == 0.toByte() })
        val stored = requireNotNull(
            database.credentialRecordDao().findSecretById(reference.secretId.value),
        )
        assertEquals("ready", stored.stateCode)
        assertNull(stored.legacyId)
        assertEquals("complete", database.legacyMigrationDao().findState(USER_SOURCE)?.stateCode)

        val credentialStore = AesGcmCredentialStore(
            RoomCredentialCiphertextStore(database, CredentialEpochClock { TIMESTAMP }),
            keys,
        )
        credentialStore.withSecret(reference) { reopened ->
            assertArrayEquals(expected, reopened)
        }

        assertEquals(
            LegacyMigrationSourceOutcome.ALREADY_APPLIED,
            coordinator.migrate().userSettings.outcome,
        )
        expected.fill(0)
    }

    @Test
    fun discardedRecoveryTombstonesSurviveCoordinatorRestartAndIgnoreRetainedSources() =
        runBlocking {
            var settingsReads = 0
            var knownHostsReads = 0
            var settingsResult: LegacyUserSettingsReadResult =
                LegacyUserSettingsReadResult.Blocked(LegacyUserSettingsFailure.CORRUPT)
            var knownHostsResult: LegacyKnownHostsReadResult =
                LegacyKnownHostsReadResult.Blocked(LegacyKnownHostsFailure.CORRUPT)
            fun coordinator() = LegacyStartupMigrationCoordinator(
                store = RoomLegacyMigrationStore(database),
                userSettingsSource = LegacyUserSettingsSource {
                    settingsReads += 1
                    settingsResult
                },
                secretsSource = object : LegacySecretsSource {
                    override fun readPassword(scope: SshPasswordScope) =
                        LegacySecretReadResult.Unavailable(LegacySecretFailure.MISSING)

                    override fun readPrivateKey(identityId: Long) =
                        LegacySecretReadResult.Unavailable(LegacySecretFailure.MISSING)
                },
                knownHostsSource = LegacyKnownHostsSource {
                    knownHostsReads += 1
                    knownHostsResult
                },
                secretReencryptor = AesGcmLegacySecretReencryptor(FixedKeyProvider()),
                privateKeyMetadataDeriver = LegacyPrivateKeyMetadataDeriver { null },
                clock = CredentialEpochClock { TIMESTAMP },
            )

            val firstCoordinator = coordinator()
            val blocked = firstCoordinator.migrate()
            assertEquals(LegacyMigrationSourceOutcome.BLOCKED, blocked.userSettings.outcome)
            assertEquals(LegacyMigrationSourceOutcome.BLOCKED, blocked.knownHosts.outcome)

            settingsResult = LegacyUserSettingsReadResult.Loaded(
                settings = UserSettings(
                    profiles = listOf(
                        SavedSshProfile(
                            PROFILE_ID,
                            "Retained",
                            "retained.example",
                            22,
                            "operator",
                        ),
                    ),
                ),
                sourceVersion = 1,
                sourceDigestSha256 = SOURCE_DIGEST,
            )
            knownHostsResult = LegacyKnownHostsReadResult.Loaded(
                source = "retained.example ssh-ed25519 AQID",
                sourceDigestSha256 = "dd".repeat(32),
            )
            firstCoordinator.discardUserSettingsAfterRecovery()
            firstCoordinator.discardKnownHostsAfterRecovery()

            assertEquals(
                LegacyMigrationStateEntity.STATE_DISCARDED_AFTER_RECOVERY,
                database.legacyMigrationDao().findState(USER_SOURCE)?.stateCode,
            )
            assertEquals(
                LegacyMigrationStateEntity.STATE_DISCARDED_AFTER_RECOVERY,
                database.legacyMigrationDao().findState(KNOWN_HOSTS_SOURCE)?.stateCode,
            )
            assertNotNull(
                database.terminalProfileDao().findById(LegacyIds.defaultTerminalProfile),
            )
            val defaultKeyboard = requireNotNull(
                database.keyboardProfileDao().findWithKeys(LegacyIds.defaultKeyboardProfile),
            )
            assertEquals(2, defaultKeyboard.profile.rowCount)
            assertEquals(18, defaultKeyboard.keys.size)

            val afterRestart = coordinator().migrate()

            assertEquals(
                LegacyMigrationSourceOutcome.ALREADY_APPLIED,
                afterRestart.userSettings.outcome,
            )
            assertEquals(
                LegacyMigrationSourceOutcome.ALREADY_APPLIED,
                afterRestart.knownHosts.outcome,
            )
            assertEquals(1, settingsReads)
            assertEquals(1, knownHostsReads)
            assertNull(database.hostProfileDao().findById(LegacyIds.hostProfile(PROFILE_ID)))
            assertTrue(database.knownHostDao().findForEndpoint("retained.example", 22).isEmpty())
        }

    private class FixedKeyProvider : CredentialKeyProvider {
        private val key = SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES")

        override fun getOrCreateEncryptionKey(keyVersion: Int): SecretKey = key

        override fun getExistingDecryptionKey(keyVersion: Int): SecretKey = key
    }

    private companion object {
        const val PROFILE_ID = 41L
        const val TIMESTAMP = 1_700_000_000_000L
        const val USER_SOURCE = "legacy_user_settings"
        const val KNOWN_HOSTS_SOURCE = "legacy_known_hosts"
        const val SOURCE_DIGEST =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
