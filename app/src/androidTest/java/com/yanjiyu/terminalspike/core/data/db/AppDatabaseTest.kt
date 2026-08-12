package com.yanjiyu.terminalspike.core.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeDataGate
import com.yanjiyu.terminalspike.core.data.repository.RecentEndpointIdentityGenerationChangedException
import com.yanjiyu.terminalspike.core.data.repository.RecentEndpointIdentityPersistence
import com.yanjiyu.terminalspike.core.data.repository.RecentSessionRepository
import com.yanjiyu.terminalspike.core.security.RecentEndpointHmac
import com.yanjiyu.terminalspike.core.security.RecentEndpointHmacProvider
import com.yanjiyu.terminalspike.core.security.RecentEndpointIdentityKeyState
import com.yanjiyu.terminalspike.core.security.RecentEndpointIdentityProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
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
    fun createsTheElevenVersionTwoProductTables() {
        val expected = setOf(
            "terminal_profiles",
            "keyboard_profiles",
            "keyboard_profile_keys",
            "encrypted_secrets",
            "ssh_key_identities",
            "ssh_credentials",
            "host_profiles",
            "recent_sessions",
            "known_hosts",
            "snippets",
            "legacy_migration_state",
        )
        val actual = buildSet {
            database.openHelper.writableDatabase
                .query("SELECT name FROM sqlite_master WHERE type = 'table'")
                .use { cursor ->
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
        }

        assertEquals(expected, actual.intersect(expected))
        assertEquals(AppDatabase.SCHEMA_VERSION, database.openHelper.writableDatabase.version)
    }

    @Test
    fun keyboardKeysAreOrderedUniqueAndCascadeWithTheirProfile() = runBlocking {
        val dao = database.keyboardProfileDao()
        dao.insertWithKeys(
            keyboardProfile(),
            listOf(
                KeyboardProfileKeyEntity(KEYBOARD_ID, position = 1, actionCode = "control"),
                KeyboardProfileKeyEntity(KEYBOARD_ID, position = 0, actionCode = "escape"),
            ),
        )

        assertEquals(listOf("escape", "control"), dao.findKeys(KEYBOARD_ID).map { it.actionCode })
        expectConstraint {
            dao.insertKeys(
                listOf(KeyboardProfileKeyEntity(KEYBOARD_ID, position = 2, actionCode = "escape")),
            )
        }

        assertEquals(1, dao.deleteById(KEYBOARD_ID))
        assertTrue(dao.findKeys(KEYBOARD_ID).isEmpty())
    }

    @Test
    fun foreignKeysSetMissingHostReferencesToNullAndRestrictSecrets() = runBlocking {
        database.terminalProfileDao().insert(terminalProfile())
        database.keyboardProfileDao().insert(keyboardProfile())
        val secret = encryptedSecret(KEY_SECRET_ID, kindCode = "ssh_private_key")
        val identity = keyIdentity()
        database.credentialRecordDao().insertIdentityWithSecret(secret, identity)
        database.credentialRecordDao().insertCredential(privateKeyCredential())
        database.hostProfileDao().insert(hostProfile())

        expectConstraint { database.credentialRecordDao().deleteSecretById(KEY_SECRET_ID) }
        expectConstraint {
            database.hostProfileDao().insert(
                hostProfile(
                    id = INVALID_HOST_ID,
                    terminalProfileId = MISSING_ID,
                ),
            )
        }

        database.terminalProfileDao().deleteById(TERMINAL_ID)
        database.keyboardProfileDao().deleteById(KEYBOARD_ID)
        database.sshCredentialDao().deleteById(CREDENTIAL_ID)

        val host = database.hostProfileDao().findById(HOST_ID)
        assertNotNull(host)
        assertNull(host?.terminalProfileId)
        assertNull(host?.keyboardProfileId)
        assertNull(host?.credentialId)
        assertNotNull(database.credentialRecordDao().findSecretById(KEY_SECRET_ID))
    }

    @Test
    fun credentialTransactionRollsBackSecretWhenMetadataConflicts() = runBlocking {
        val dao = database.credentialRecordDao()
        dao.insertCredentialWithSecret(
            encryptedSecret(PASSWORD_SECRET_ID, kindCode = "password"),
            passwordCredential(PASSWORD_SECRET_ID),
        )
        val uncommittedSecret = encryptedSecret(SECOND_SECRET_ID, kindCode = "password")

        expectConstraint {
            dao.insertCredentialWithSecret(
                uncommittedSecret,
                passwordCredential(SECOND_SECRET_ID),
            )
        }

        assertNull(dao.findSecretById(SECOND_SECRET_ID))
        assertEquals(PASSWORD_SECRET_ID, database.sshCredentialDao().findById(CREDENTIAL_ID)?.secretId)
    }

    @Test
    fun recentSessionRetainsDisplaySafeHistoryWhenItsHostIsDeleted() = runBlocking {
        database.hostProfileDao().insert(
            hostProfile(
                credentialId = null,
                terminalProfileId = null,
                keyboardProfileId = null,
            ),
        )
        database.recentSessionDao().insert(recentSession())

        database.hostProfileDao().deleteById(HOST_ID)

        val retained = database.recentSessionDao().findById(SESSION_ID)
        assertNotNull(retained)
        assertNull(retained?.hostProfileId)
        assertEquals("Example", retained?.hostDisplayName)
        assertEquals("ssh", retained?.protocolCode)
        assertEquals("disconnected", retained?.stateCode)
        assertEquals(NOW, retained?.endedAtEpochMillis)
        assertEquals("server shell", retained?.terminalTitle)
    }

    @Test
    fun recentSessionHistoryExcludesLiveRowsAndReconcilesOnlyTheRequestedBound() = runBlocking {
        val dao = database.recentSessionDao()
        val ended = recentSession().copy(hostProfileId = null)
        val olderActive = ended.copy(
            id = SECOND_SESSION_ID,
            stateCode = "connecting",
            startedAtEpochMillis = NOW - 5_000,
            lastActivityAtEpochMillis = NOW - 4_000,
            endedAtEpochMillis = null,
        )
        val newerActive = olderActive.copy(
            id = THIRD_SESSION_ID,
            stateCode = "connected",
            lastActivityAtEpochMillis = NOW - 3_000,
        )
        dao.upsert(ended)
        dao.upsert(olderActive)
        dao.upsert(newerActive)
        val repository = RecentSessionRepository(dao)

        assertEquals(listOf(SESSION_ID), repository.observeEnded(8).first().map { it.id })
        assertEquals(1, repository.reconcileStaleActive(NOW + 1_000, limit = 1))
        assertEquals(
            listOf(SECOND_SESSION_ID),
            dao.findActive(listOf("connecting", "connected", "reconnecting"), 8).map { it.id },
        )
        assertEquals(
            setOf(SESSION_ID, THIRD_SESSION_ID),
            repository.observeEnded(8).first().map { it.id }.toSet(),
        )
    }

    @Test
    fun recentHostActivityIsGroupedBoundedAndStableAcrossActiveAndEndedHistory() = runBlocking {
        val hostDao = database.hostProfileDao()
        listOf(HOST_ID, SECOND_HOST_ID, THIRD_HOST_ID).forEach { hostId ->
            hostDao.insert(
                hostProfile(
                    id = hostId,
                    credentialId = null,
                    terminalProfileId = null,
                    keyboardProfileId = null,
                ),
            )
        }
        val sessionDao = database.recentSessionDao()
        sessionDao.upsert(
            recentSession().copy(
                hostProfileId = HOST_ID,
                lastActivityAtEpochMillis = NOW - 100,
            ),
        )
        sessionDao.upsert(
            recentSession().copy(
                id = SECOND_SESSION_ID,
                hostProfileId = HOST_ID,
                stateCode = "connected",
                lastActivityAtEpochMillis = NOW + 200,
                endedAtEpochMillis = null,
            ),
        )
        sessionDao.upsert(
            recentSession().copy(
                id = THIRD_SESSION_ID,
                hostProfileId = SECOND_HOST_ID,
                lastActivityAtEpochMillis = NOW + 200,
                endedAtEpochMillis = NOW + 300,
            ),
        )
        sessionDao.upsert(
            recentSession().copy(
                id = FOURTH_SESSION_ID,
                hostProfileId = THIRD_HOST_ID,
                lastActivityAtEpochMillis = NOW + 100,
                endedAtEpochMillis = NOW + 200,
            ),
        )
        sessionDao.upsert(
            recentSession().copy(
                id = FIFTH_SESSION_ID,
                hostProfileId = null,
                lastActivityAtEpochMillis = NOW + 1_000,
                endedAtEpochMillis = NOW + 1_100,
            ),
        )

        val repository = RecentSessionRepository(sessionDao)
        val allHostActivity = repository.observeRecentHostActivity(limit = 8).first()
        assertEquals(
            listOf(HOST_ID, SECOND_HOST_ID, THIRD_HOST_ID),
            allHostActivity.map { it.hostProfileId },
        )
        assertEquals(
            listOf(NOW + 200, NOW + 200, NOW + 100),
            allHostActivity.map { it.lastActivityAtEpochMillis },
        )
        assertEquals(
            listOf(HOST_ID, SECOND_HOST_ID),
            repository.observeRecentHostActivity(limit = 2).first().map { it.hostProfileId },
        )
    }

    @Test
    fun endingDuplicateSessionsRetainsOnlyTheNewestEndedIdentity() = runBlocking {
        val dao = database.recentSessionDao()
        val token = "ab".repeat(32)
        val older = recentSession().copy(
            hostProfileId = null,
            stateCode = "connected",
            lastActivityAtEpochMillis = NOW - 2_000,
            endedAtEpochMillis = null,
            endpointIdentityToken = token,
        )
        val newer = older.copy(
            id = SECOND_SESSION_ID,
            lastActivityAtEpochMillis = NOW - 1_000,
        )
        dao.insert(older)
        dao.insert(newer)
        assertEquals(2, dao.observeActive(listOf("connected")).first().size)

        assertEquals(
            1,
            dao.update(newer.copy(stateCode = "disconnected", endedAtEpochMillis = NOW)),
        )
        assertEquals(
            1,
            dao.update(older.copy(stateCode = "disconnected", endedAtEpochMillis = NOW + 1)),
        )

        val retained = dao.observeEndedRecent(10).first().single()
        assertEquals(SECOND_SESSION_ID, retained.id)
        assertEquals(token, retained.endpointIdentityToken)
    }

    @Test
    fun savedHostBackfillDeduplicatesEndedRowsWithoutPersistingRawEndpointFields() = runBlocking {
        database.hostProfileDao().insert(
            hostProfile(
                credentialId = null,
                terminalProfileId = null,
                keyboardProfileId = null,
            ),
        )
        val dao = database.recentSessionDao()
        dao.insert(
            recentSession().copy(
                lastActivityAtEpochMillis = NOW - 2_000,
                endedAtEpochMillis = NOW - 1_000,
            ),
        )
        dao.insert(
            recentSession().copy(
                id = SECOND_SESSION_ID,
                lastActivityAtEpochMillis = NOW - 1_000,
                endedAtEpochMillis = NOW,
            ),
        )

        val candidates = dao.findEndpointIdentityBackfillRows(10)
        assertEquals(setOf(SESSION_ID, SECOND_SESSION_ID), candidates.map { it.sessionId }.toSet())
        assertEquals(setOf("example.test"), candidates.map { it.hostname }.toSet())
        assertEquals(setOf("tester"), candidates.map { it.username }.toSet())
        val token = "cd".repeat(32)
        assertEquals(
            2,
            dao.applyEndpointIdentityBackfill(
                candidates.map { candidate ->
                    RecentEndpointIdentityBackfillUpdate(candidate.sessionId, token)
                },
            ),
        )

        val retained = dao.observeEndedRecent(10).first().single()
        assertEquals(SECOND_SESSION_ID, retained.id)
        assertEquals(token, retained.endpointIdentityToken)
    }

    @Test
    fun firstEndpointKeyCreationBackfillsAndPreservesNullMigrationRows() = runBlocking {
        database.hostProfileDao().insert(
            hostProfile(
                credentialId = null,
                terminalProfileId = null,
                keyboardProfileId = null,
            ),
        )
        val dao = database.recentSessionDao()
        dao.insert(recentSession())
        dao.insert(
            recentSession().copy(
                id = SECOND_SESSION_ID,
                stateCode = "connected",
                lastActivityAtEpochMillis = NOW + 1,
                endedAtEpochMillis = null,
            ),
        )
        assertEquals(0, dao.countEndpointIdentityTokens())
        val authority = AuthoritativeDataGate().also { gate ->
            gate.resolveStartup(hasPendingRecovery = { false }, recoverPending = { true })
        }
        val persistence = RecentEndpointIdentityPersistence(
            dao = dao,
            identityProvider = RecentEndpointIdentityProvider(FirstCreationHmacProvider()),
            authority = authority,
        )

        val result = persistence.backfillSavedHosts(limit = 10)

        assertEquals(2, result.candidatesScanned)
        assertEquals(2, result.tokensStored)
        assertEquals(false, result.hasMore)
        val ended = requireNotNull(dao.findById(SESSION_ID))
        val active = requireNotNull(dao.findById(SECOND_SESSION_ID))
        assertNotNull(ended.endpointIdentityToken)
        assertEquals(ended.endpointIdentityToken, active.endpointIdentityToken)
        assertEquals(2, dao.countEndpointIdentityTokens())
    }

    @Test
    fun backfillRefusesNewKeyWhenPersistedGenerationAlreadyExists() = runBlocking {
        database.hostProfileDao().insert(
            hostProfile(
                credentialId = null,
                terminalProfileId = null,
                keyboardProfileId = null,
            ),
        )
        val dao = database.recentSessionDao()
        val oldToken = "ab".repeat(32)
        dao.insert(
            recentSession().copy(
                hostProfileId = null,
                endpointIdentityToken = oldToken,
            ),
        )
        dao.insert(recentSession().copy(id = SECOND_SESSION_ID))
        val authority = AuthoritativeDataGate().also { gate ->
            gate.resolveStartup(hasPendingRecovery = { false }, recoverPending = { true })
        }
        val persistence = RecentEndpointIdentityPersistence(
            dao = dao,
            identityProvider = RecentEndpointIdentityProvider(FirstCreationHmacProvider()),
            authority = authority,
        )

        var failure: Throwable? = null
        try {
            persistence.backfillSavedHosts(limit = 10)
        } catch (caught: Throwable) {
            failure = caught
        }

        val generationChange = failure as? RecentEndpointIdentityGenerationChangedException
            ?: throw AssertionError("Expected an existing endpoint generation to require reset")
        assertEquals(RecentEndpointIdentityKeyState.CREATED, generationChange.keyState)
        assertEquals(oldToken, dao.findById(SESSION_ID)?.endpointIdentityToken)
        assertNull(dao.findById(SECOND_SESSION_ID)?.endpointIdentityToken)
        assertEquals(2, dao.observeEndedRecent(10).first().size)
    }

    @Test
    fun generationReplacementPreservesHistoryWhenOnlyNullMigrationRowsExist() = runBlocking {
        val dao = database.recentSessionDao()
        dao.insert(recentSession().copy(hostProfileId = null))
        dao.insert(
            recentSession().copy(
                id = SECOND_SESSION_ID,
                hostProfileId = null,
                stateCode = "connected",
                endedAtEpochMillis = null,
            ),
        )
        assertEquals(0, dao.countEndpointIdentityTokens())

        val result = dao.replaceEndpointIdentityGeneration(
            listOf(
                RecentEndpointIdentityBackfillUpdate(
                    SECOND_SESSION_ID,
                    "ef".repeat(32),
                ),
            ),
        )

        assertEquals(
            RecentEndpointIdentityResetResult(
                endedRowsDeleted = 0,
                activeTokensCleared = 0,
            ),
            result,
        )
        assertNotNull(dao.findById(SESSION_ID))
        assertNull(dao.findById(SESSION_ID)?.endpointIdentityToken)
        assertNull(dao.findById(SECOND_SESSION_ID)?.endpointIdentityToken)
    }

    @Test
    fun replacingEndpointIdentityGenerationDeletesEndedAndReissuesOnlyActiveRows() = runBlocking {
        val dao = database.recentSessionDao()
        dao.insert(
            recentSession().copy(
                hostProfileId = null,
                endpointIdentityToken = "ab".repeat(32),
            ),
        )
        dao.insert(
            recentSession().copy(
                id = SECOND_SESSION_ID,
                hostProfileId = null,
                stateCode = "connected",
                endedAtEpochMillis = null,
                endpointIdentityToken = "ab".repeat(32),
            ),
        )
        assertEquals(2, dao.countEndpointIdentityTokens())

        val replacement = "ef".repeat(32)
        val result = dao.replaceEndpointIdentityGeneration(
            listOf(RecentEndpointIdentityBackfillUpdate(SECOND_SESSION_ID, replacement)),
        )

        assertEquals(1, result.endedRowsDeleted)
        assertEquals(1, result.activeTokensCleared)
        assertEquals(1, result.activeTokensReissued)
        assertNull(dao.findById(SESSION_ID))
        assertEquals(replacement, dao.findById(SECOND_SESSION_ID)?.endpointIdentityToken)
        assertEquals(1, dao.countEndpointIdentityTokens())
    }

    @Test
    fun legacyUnavailableRowsDoNotInventPublicKeyOrKnownHostHistory() = runBlocking {
        val unavailableSecret = encryptedSecret(KEY_SECRET_ID, kindCode = "ssh_private_key").copy(
            envelopeVersion = 0,
            keyVersion = 0,
            nonce = null,
            ciphertext = null,
            stateCode = "legacy_unavailable",
            failureCode = "legacy_key_unavailable",
            legacyId = "legacy-key-7",
        )
        database.credentialRecordDao().insertIdentityWithSecret(
            unavailableSecret,
            keyIdentity().copy(publicKey = null),
        )
        val knownHost = KnownHostEntity(
            id = KNOWN_HOST_ID,
            host = "example.test",
            port = 22,
            algorithmCode = "ssh_ed25519",
            fingerprint = "SHA256:test",
            publicKey = byteArrayOf(1, 2, 3),
            firstSeenAtEpochMillis = null,
            lastSeenAtEpochMillis = null,
        )
        database.knownHostDao().insert(knownHost)
        expectConstraint {
            database.knownHostDao().insert(knownHost.copy(id = DUPLICATE_KNOWN_HOST_ID))
        }

        assertNull(database.sshKeyIdentityDao().findById(IDENTITY_ID)?.publicKey)
        val restoredKnownHost = database.knownHostDao().find(
            knownHost.host,
            knownHost.port,
            knownHost.algorithmCode,
        )
        assertNull(restoredKnownHost?.firstSeenAtEpochMillis)
        assertNull(restoredKnownHost?.lastSeenAtEpochMillis)
    }

    @Test
    fun knownHostVerificationChecksEveryAlgorithmAndRecordsOnlyExactKeySeen() = runBlocking {
        val dao = database.knownHostDao()
        val ed25519 = knownHost(
            id = KNOWN_HOST_ID,
            algorithm = "ssh-ed25519",
            key = byteArrayOf(1, 2, 3),
            firstSeen = null,
            lastSeen = null,
        )
        val rsa = knownHost(
            id = SECOND_KNOWN_HOST_ID,
            algorithm = "rsa-sha2-512",
            key = byteArrayOf(4, 5, 6),
            firstSeen = null,
            lastSeen = null,
        )
        dao.insert(ed25519)
        dao.insert(rsa)

        val changedAlgorithm = dao.verifyAndRecordSeen(
            knownHost(
                id = THIRD_KNOWN_HOST_ID,
                algorithm = "ecdsa-sha2-nistp256",
                key = byteArrayOf(7, 8, 9),
            ),
            seenAtEpochMillis = NOW,
        )
        assertTrue(changedAlgorithm is KnownHostVerificationResult.Mismatch)
        assertEquals(
            listOf("rsa-sha2-512", "ssh-ed25519"),
            (changedAlgorithm as KnownHostVerificationResult.Mismatch)
                .trustedKeys
                .map { it.algorithmCode },
        )

        val exact = dao.verifyAndRecordSeen(
            ed25519.copy(id = THIRD_KNOWN_HOST_ID, fingerprint = "display-value-is-not-trust"),
            seenAtEpochMillis = NOW,
        )
        assertTrue(exact is KnownHostVerificationResult.Trusted)
        val observed = (exact as KnownHostVerificationResult.Trusted).knownHost
        assertEquals(KNOWN_HOST_ID, observed.id)
        assertEquals(NOW, observed.firstSeenAtEpochMillis)
        assertEquals(NOW, observed.lastSeenAtEpochMillis)
        assertNull(dao.find(rsa.host, rsa.port, rsa.algorithmCode)?.firstSeenAtEpochMillis)
    }

    @Test
    fun concurrentFirstContactTrustSavesOnlyOneKeyForTheEndpoint() = runBlocking {
        val dao = database.knownHostDao()
        val candidates = listOf(
            knownHost(KNOWN_HOST_ID, "ssh-ed25519", byteArrayOf(1, 2, 3)),
            knownHost(SECOND_KNOWN_HOST_ID, "rsa-sha2-512", byteArrayOf(4, 5, 6)),
        )
        val start = CompletableDeferred<Unit>()

        val results = coroutineScope {
            candidates.map { candidate ->
                async(Dispatchers.IO) {
                    start.await()
                    dao.trustIfUntrusted(candidate)
                }
            }.also { start.complete(Unit) }.awaitAll()
        }

        assertEquals(1, results.count { it is KnownHostSaveResult.Saved })
        assertEquals(1, results.count { it is KnownHostSaveResult.Conflict })
        assertEquals(1, dao.findForEndpoint("example.test", 22).size)
    }

    @Test
    fun explicitEndpointReplacementRollsBackDeletionWhenInsertFails() = runBlocking {
        val dao = database.knownHostDao()
        val original = knownHost(KNOWN_HOST_ID, "ssh-ed25519", byteArrayOf(1, 2, 3))
        val unrelated = knownHost(
            SECOND_KNOWN_HOST_ID,
            "ssh-ed25519",
            byteArrayOf(4, 5, 6),
            host = "other.test",
        )
        dao.insert(original)
        dao.insert(unrelated)

        expectConstraint {
            dao.replaceEndpoint(
                knownHost(
                    SECOND_KNOWN_HOST_ID,
                    "rsa-sha2-512",
                    byteArrayOf(7, 8, 9),
                ),
            )
        }

        assertEquals(listOf(KNOWN_HOST_ID), dao.findForEndpoint("example.test", 22).map { it.id })
        assertNotNull(dao.find("other.test", 22, "ssh-ed25519"))
    }

    @Test
    fun promptedEndpointReplacementComparesEveryExpectedPublicKey() = runBlocking {
        val dao = database.knownHostDao()
        val ed25519 = knownHost(KNOWN_HOST_ID, "ssh-ed25519", byteArrayOf(1, 2, 3))
        val rsa = knownHost(SECOND_KNOWN_HOST_ID, "ssh-rsa", byteArrayOf(4, 5, 6))
        dao.insert(ed25519)
        dao.insert(rsa)
        val promptedSnapshot = dao.findForEndpoint("example.test", 22)

        val intervening = rsa.copy(publicKey = byteArrayOf(7, 8, 9))
        assertEquals(1, dao.update(intervening))
        val stale = dao.replaceEndpointIfUnchanged(
            candidate = knownHost(THIRD_KNOWN_HOST_ID, "ssh-ed25519", byteArrayOf(10, 11, 12)),
            expectedTrustedKeys = promptedSnapshot,
        )

        assertTrue(stale is KnownHostConditionalReplacementResult.Stale)
        assertEquals(2, dao.findForEndpoint("example.test", 22).size)
        assertArrayEquals(
            intervening.publicKey,
            requireNotNull(dao.find("example.test", 22, "ssh-rsa")).publicKey,
        )

        val refreshedSnapshot = dao.findForEndpoint("example.test", 22)
        val replacement = knownHost(
            THIRD_KNOWN_HOST_ID,
            "ssh-ed25519",
            byteArrayOf(10, 11, 12),
        )
        val replaced = dao.replaceEndpointIfUnchanged(replacement, refreshedSnapshot)

        assertTrue(replaced is KnownHostConditionalReplacementResult.Replaced)
        assertEquals(
            listOf(THIRD_KNOWN_HOST_ID),
            dao.findForEndpoint("example.test", 22).map { it.id },
        )
    }

    @Test
    fun legacyBatchRollsBackRowsAndCompletionMarkerOnConflict() = runBlocking {
        val existingSnippet = snippet()
        database.snippetDao().insert(existingSnippet)
        val completion = LegacyMigrationStateEntity(
            sourceCode = "settings_v1_v3",
            sourceDigestSha256 = "00".repeat(32),
            sourceVersion = 3,
            stateCode = "complete",
            errorCode = null,
            warningCodes = "",
            lastAttemptAtEpochMillis = NOW,
            completedAtEpochMillis = NOW,
        )

        expectConstraint {
            database.legacyMigrationDao().insertBatch(
                LegacyMigrationBatch(
                    terminalProfiles = listOf(terminalProfile()),
                    snippets = listOf(existingSnippet),
                    completion = completion,
                ),
            )
        }

        assertNull(database.terminalProfileDao().findById(TERMINAL_ID))
        assertNull(database.legacyMigrationDao().findState(completion.sourceCode))
        assertNotNull(database.snippetDao().findById(SNIPPET_ID))
    }

    @Test
    fun authoritativeLegacyCompletionIgnoresLaterSourceChanges() = runBlocking {
        val dao = database.legacyMigrationDao()
        val completion = LegacyMigrationStateEntity(
            sourceCode = "settings_v1_v3",
            sourceDigestSha256 = "00".repeat(32),
            sourceVersion = 3,
            stateCode = "complete",
            errorCode = null,
            warningCodes = "",
            lastAttemptAtEpochMillis = NOW,
            completedAtEpochMillis = NOW,
        )
        val batch = LegacyMigrationBatch(
            terminalProfiles = listOf(terminalProfile()),
            completion = completion,
        )

        assertEquals(LegacyMigrationInsertResult.INSERTED, dao.insertBatch(batch))
        assertEquals(
            LegacyMigrationInsertResult.ALREADY_APPLIED,
            dao.insertBatch(
                batch.copy(
                    terminalProfiles = listOf(terminalProfile().copy(name = "Must not overwrite")),
                    completion = completion.copy(
                        lastAttemptAtEpochMillis = NOW + 1,
                        completedAtEpochMillis = NOW + 1,
                    ),
                ),
            ),
        )
        assertEquals("Default", database.terminalProfileDao().findById(TERMINAL_ID)?.name)
        assertEquals(NOW, dao.findState(completion.sourceCode)?.completedAtEpochMillis)

        assertEquals(
            LegacyMigrationInsertResult.ALREADY_APPLIED,
            dao.insertBatch(
                LegacyMigrationBatch(
                    completion = completion.copy(sourceDigestSha256 = "11".repeat(32)),
                ),
            ),
        )
        assertEquals(NOW, dao.findState(completion.sourceCode)?.completedAtEpochMillis)
    }

    @Test
    fun absentAndDiscardedMarkersAreTerminalAndHaveCanonicalSourceFreeShape() = runBlocking {
        val dao = database.legacyMigrationDao()
        val absent = LegacyMigrationStateEntity(
            sourceCode = "legacy_known_hosts",
            sourceDigestSha256 = null,
            sourceVersion = null,
            stateCode = LegacyMigrationStateEntity.STATE_ABSENT,
            errorCode = null,
            warningCodes = "",
            lastAttemptAtEpochMillis = NOW,
            completedAtEpochMillis = NOW,
        )
        assertEquals(
            LegacyMigrationInsertResult.INSERTED,
            dao.insertBatch(LegacyMigrationBatch(completion = absent)),
        )
        assertEquals(
            LegacyMigrationInsertResult.ALREADY_APPLIED,
            dao.insertBatch(
                LegacyMigrationBatch(
                    knownHosts = listOf(
                        knownHost(KNOWN_HOST_ID, "ssh-ed25519", byteArrayOf(1, 2, 3)),
                    ),
                    completion = absent.copy(
                        sourceDigestSha256 = "33".repeat(32),
                        stateCode = LegacyMigrationStateEntity.STATE_COMPLETE,
                        lastAttemptAtEpochMillis = NOW + 1,
                        completedAtEpochMillis = NOW + 1,
                    ),
                ),
            ),
        )
        assertTrue(database.knownHostDao().findForEndpoint("example.test", 22).isEmpty())

        val blocked = LegacyMigrationStateEntity(
            sourceCode = "legacy_user_settings",
            sourceDigestSha256 = null,
            sourceVersion = null,
            stateCode = LegacyMigrationStateEntity.STATE_BLOCKED,
            errorCode = "settings_corrupt",
            warningCodes = "",
            lastAttemptAtEpochMillis = NOW,
            completedAtEpochMillis = null,
        )
        dao.insertState(blocked)
        val discarded = blocked.copy(
            stateCode = LegacyMigrationStateEntity.STATE_DISCARDED_AFTER_RECOVERY,
            errorCode = null,
            lastAttemptAtEpochMillis = NOW + 1,
            completedAtEpochMillis = NOW + 1,
        )
        assertEquals(
            LegacyMigrationInsertResult.INSERTED,
            dao.insertBatch(
                LegacyMigrationBatch(
                    terminalProfiles = listOf(terminalProfile()),
                    completion = discarded,
                ),
            ),
        )
        assertEquals(discarded, dao.findState(discarded.sourceCode))
        assertNotNull(database.terminalProfileDao().findById(TERMINAL_ID))
    }

    @Test
    fun blockedLegacySourceCanRetryWithoutDeletingItsRecoveryMarkerFirst() = runBlocking {
        val dao = database.legacyMigrationDao()
        val blocked = LegacyMigrationStateEntity(
            sourceCode = "legacy_user_settings",
            sourceDigestSha256 = null,
            sourceVersion = null,
            stateCode = "blocked",
            errorCode = "key_unavailable",
            warningCodes = "",
            lastAttemptAtEpochMillis = NOW,
            completedAtEpochMillis = null,
        )
        dao.insertState(blocked)
        val complete = blocked.copy(
            sourceDigestSha256 = "22".repeat(32),
            sourceVersion = 3,
            stateCode = "complete",
            errorCode = null,
            lastAttemptAtEpochMillis = NOW + 1,
            completedAtEpochMillis = NOW + 1,
        )

        assertEquals(
            LegacyMigrationInsertResult.INSERTED,
            dao.insertBatch(
                LegacyMigrationBatch(
                    terminalProfiles = listOf(terminalProfile()),
                    completion = complete,
                ),
            ),
        )
        assertNotNull(database.terminalProfileDao().findById(TERMINAL_ID))
        assertEquals(complete, dao.findState(blocked.sourceCode))
    }

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

    private fun terminalProfile(id: String = TERMINAL_ID) = TerminalProfileEntity(
        id = id,
        name = "Default",
        themeId = "midnight",
        fontId = "monospace",
        fontSizeSp = 14f,
        lineHeightMultiplier = 1.1f,
        letterSpacingEm = 0f,
        cursorStyleCode = "block",
        cursorBlink = true,
        scrollbackLines = 10_000,
        visualBellEnabled = true,
        vibrationBellEnabled = false,
        audibleBellEnabled = false,
        touchScrollModeCode = "auto",
        twoFingerLocalScrollOverride = true,
        jumpToBottomOnKeyboardInput = true,
        keepViewportPositionOnOutput = true,
        detectPlainTextUrls = true,
        osc8HyperlinksEnabled = true,
        remoteClipboardModeCode = "ask",
        termType = "xterm-256color",
        retainAlternateScreenHistory = false,
        createdAtEpochMillis = NOW,
        updatedAtEpochMillis = NOW,
    )

    private fun keyboardProfile() = KeyboardProfileEntity(
        id = KEYBOARD_ID,
        name = "Default",
        rowCount = 1,
        modifierPolicyCode = "latching",
        hapticEnabled = true,
        keyRepeatEnabled = true,
        inputModeCode = "auto",
        tmuxPrefix = "C-b",
        createdAtEpochMillis = NOW,
        updatedAtEpochMillis = NOW,
    )

    private fun encryptedSecret(
        id: String,
        kindCode: String,
    ) = EncryptedSecretEntity(
        id = id,
        kindCode = kindCode,
        envelopeVersion = 1,
        keyVersion = 1,
        nonce = ByteArray(12) { 1 },
        ciphertext = ByteArray(32) { 2 },
        stateCode = "ready",
        failureCode = null,
        legacyId = null,
        createdAtEpochMillis = NOW,
        updatedAtEpochMillis = NOW,
    )

    private fun keyIdentity() = SshKeyIdentityEntity(
        id = IDENTITY_ID,
        name = "Work key",
        algorithmCode = "ssh_ed25519",
        fingerprint = "SHA256:test",
        publicKey = byteArrayOf(1, 2, 3),
        provenanceCode = "imported",
        isPassphraseProtected = true,
        comment = "test@example",
        privateSecretId = KEY_SECRET_ID,
        createdAtEpochMillis = NOW,
        updatedAtEpochMillis = NOW,
    )

    private fun privateKeyCredential() = SshCredentialEntity(
        id = CREDENTIAL_ID,
        name = "Work key",
        kindCode = "private_key",
        secretId = null,
        keyIdentityId = IDENTITY_ID,
        createdAtEpochMillis = NOW,
        updatedAtEpochMillis = NOW,
    )

    private fun passwordCredential(secretId: String) = SshCredentialEntity(
        id = CREDENTIAL_ID,
        name = "Password",
        kindCode = "password",
        secretId = secretId,
        keyIdentityId = null,
        createdAtEpochMillis = NOW,
        updatedAtEpochMillis = NOW,
    )

    private fun hostProfile(
        id: String = HOST_ID,
        credentialId: String? = CREDENTIAL_ID,
        terminalProfileId: String? = TERMINAL_ID,
        keyboardProfileId: String? = KEYBOARD_ID,
    ) = HostProfileEntity(
        id = id,
        displayName = "Example",
        hostname = "example.test",
        port = 22,
        username = "tester",
        protocolCode = "ssh",
        credentialId = credentialId,
        terminalProfileId = terminalProfileId,
        keyboardProfileId = keyboardProfileId,
        isFavorite = true,
        groupName = "Work",
        tag = "production",
        startupCommand = "tmux attach",
        keepaliveIntervalSeconds = 30,
        reconnectPolicyCode = "bounded",
        moshPortStart = null,
        moshPortEnd = null,
        moshServerCommand = null,
        createdAtEpochMillis = NOW,
        updatedAtEpochMillis = NOW,
    )

    private fun recentSession() = RecentSessionEntity(
        id = SESSION_ID,
        hostProfileId = HOST_ID,
        hostDisplayName = "Example",
        protocolCode = "ssh",
        stateCode = "disconnected",
        startedAtEpochMillis = NOW - 2_000,
        lastActivityAtEpochMillis = NOW - 1_000,
        endedAtEpochMillis = NOW,
        terminalTitle = "server shell",
    )

    private fun snippet() = SnippetEntity(
        id = SNIPPET_ID,
        name = "List files",
        groupName = "Shell",
        command = "ls -la",
        actionCode = "send_immediately",
        appendEnter = true,
        confirmMultiline = true,
        isFavorite = true,
        createdAtEpochMillis = NOW,
        updatedAtEpochMillis = NOW,
    )

    private fun knownHost(
        id: String,
        algorithm: String,
        key: ByteArray,
        host: String = "example.test",
        firstSeen: Long? = NOW,
        lastSeen: Long? = NOW,
    ) = KnownHostEntity(
        id = id,
        host = host,
        port = 22,
        algorithmCode = algorithm,
        fingerprint = "SHA256:test",
        publicKey = key,
        firstSeenAtEpochMillis = firstSeen,
        lastSeenAtEpochMillis = lastSeen,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val TERMINAL_ID = "00000000-0000-4000-8000-000000000001"
        const val KEYBOARD_ID = "00000000-0000-4000-8000-000000000002"
        const val KEY_SECRET_ID = "00000000-0000-4000-8000-000000000003"
        const val IDENTITY_ID = "00000000-0000-4000-8000-000000000004"
        const val CREDENTIAL_ID = "00000000-0000-4000-8000-000000000005"
        const val HOST_ID = "00000000-0000-4000-8000-000000000006"
        const val INVALID_HOST_ID = "00000000-0000-4000-8000-000000000007"
        const val MISSING_ID = "00000000-0000-4000-8000-000000000008"
        const val PASSWORD_SECRET_ID = "00000000-0000-4000-8000-000000000009"
        const val SECOND_SECRET_ID = "00000000-0000-4000-8000-000000000010"
        const val SNIPPET_ID = "00000000-0000-4000-8000-000000000011"
        const val SESSION_ID = "00000000-0000-4000-8000-000000000012"
        const val KNOWN_HOST_ID = "00000000-0000-4000-8000-000000000013"
        const val DUPLICATE_KNOWN_HOST_ID = "00000000-0000-4000-8000-000000000014"
        const val SECOND_KNOWN_HOST_ID = "00000000-0000-4000-8000-000000000015"
        const val THIRD_KNOWN_HOST_ID = "00000000-0000-4000-8000-000000000016"
        const val SECOND_SESSION_ID = "00000000-0000-4000-8000-000000000017"
        const val THIRD_SESSION_ID = "00000000-0000-4000-8000-000000000018"
        const val SECOND_HOST_ID = "00000000-0000-4000-8000-000000000019"
        const val THIRD_HOST_ID = "00000000-0000-4000-8000-000000000020"
        const val FOURTH_SESSION_ID = "00000000-0000-4000-8000-000000000021"
        const val FIFTH_SESSION_ID = "00000000-0000-4000-8000-000000000022"
    }
}

private class FirstCreationHmacProvider : RecentEndpointHmacProvider {
    private val key = SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "HmacSHA256")
    private var callCount = 0

    override fun hmacSha256(message: ByteArray): RecentEndpointHmac {
        val state = if (callCount++ == 0) {
            RecentEndpointIdentityKeyState.CREATED
        } else {
            RecentEndpointIdentityKeyState.EXISTING
        }
        val digest = Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal(message)
        }
        return RecentEndpointHmac(digest, state)
    }
}
