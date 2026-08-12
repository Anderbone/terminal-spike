package com.yanjiyu.terminalspike.core.data.repository

import com.yanjiyu.terminalspike.core.data.credential.ClearAllSavedCredentialsResult
import com.yanjiyu.terminalspike.core.data.credential.CredentialEpochClock
import com.yanjiyu.terminalspike.core.data.credential.SavedCredentialClearPreview
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.BellSettings
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.LinkBehavior
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.MoshPortRange
import com.yanjiyu.terminalspike.core.model.ReconnectPolicy
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.core.model.ScrollBehavior
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import com.yanjiyu.terminalspike.core.model.SshKeyIdentity
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.core.model.TouchScrollMode
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.settings.SavedHostConnectionCompatibility
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.SettingsLoadFailure
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalDataRepositoryTest {
    @Test
    fun clearAllSavedCredentialsUsesAuthorityAndRefreshesCatalogBeforeReturning() = runTest {
        val persistence = FakePersistence(catalogRecords())
        val repository = repository(persistence)

        assertEquals(
            SavedCredentialClearPreview(credentialCount = 1, keyIdentityCount = 1),
            repository.previewClearAllSavedCredentials(),
        )
        val outcome = repository.clearAllSavedCredentials()

        assertEquals(1, outcome.cleared.credentialMetadataDeleted)
        assertEquals(1, outcome.cleared.keyIdentitiesDeleted)
        assertTrue(persistence.records.credentials.isEmpty())
        assertTrue(persistence.records.identities.isEmpty())
        assertNull(persistence.records.hosts.single().credentialId)
        assertFalse(outcome.settings.profiles.single().hasSavedPassword)
        assertTrue("Successful clear must reload authoritative records.", persistence.readCount >= 2)
    }

    @Test
    fun catalogPreservesEveryConnectionFieldAndBothPresentationIdDirections() = runTest {
        val records = catalogRecords()

        val loaded = repository(FakePersistence(records)).loadCatalog()
        val catalog = requireNotNull(loaded.catalog)

        val host = catalog.hosts.single()
        assertEquals(records.hosts.single(), host.profile)
        assertEquals(HOST_ID, catalog.persistentHostId(host.presentationId))
        assertEquals(host.presentationId, catalog.hostPresentationId(HOST_ID))

        assertEquals(
            CatalogSshCredential(
                id = CREDENTIAL_ID,
                displayName = "Generated key login",
                authentication = SshAuthentication.PrivateKey(
                    keyIdentityId = IDENTITY_ID,
                    passphraseSecretReferenceId = SECRET_ID,
                ),
                savedSecretAvailability = CatalogSecretAvailability.UNAVAILABLE,
                createdAtEpochMillis = 11,
                updatedAtEpochMillis = 29,
            ),
            catalog.credentials.single(),
        )

        val sourceIdentity = records.identities.single()
        val identity = catalog.identities.single()
        assertEquals(sourceIdentity.id, identity.id)
        assertEquals(sourceIdentity.name, identity.name)
        assertEquals(sourceIdentity.algorithm, identity.algorithm)
        assertEquals(sourceIdentity.publicKeyFingerprint, identity.publicKeyFingerprint)
        assertEquals(sourceIdentity.publicKey, identity.publicKey)
        assertEquals(sourceIdentity.origin, identity.origin)
        assertEquals(sourceIdentity.isPassphraseProtected, identity.isPassphraseProtected)
        assertEquals(sourceIdentity.createdAtEpochMillis, identity.createdAtEpochMillis)
        assertEquals(sourceIdentity.updatedAtEpochMillis, identity.updatedAtEpochMillis)
        assertEquals(sourceIdentity.comment, identity.comment)
        assertEquals(CatalogSecretAvailability.UNAVAILABLE, identity.privateKeyAvailability)
        assertEquals(IDENTITY_ID, catalog.persistentIdentityId(identity.presentationId))
        assertEquals(identity.presentationId, catalog.identityPresentationId(IDENTITY_ID))

        val snippet = catalog.snippets.single()
        assertEquals(records.snippets.single(), snippet.snippet)
        assertEquals(SNIPPET_ID, catalog.persistentSnippetId(snippet.presentationId))
        assertEquals(snippet.presentationId, catalog.snippetPresentationId(SNIPPET_ID))

        assertEquals(records.terminalProfiles, catalog.terminalProfiles)
        assertEquals(records.keyboardProfiles, catalog.keyboardProfiles)
        assertEquals(TERMINAL_PROFILE_ID, catalog.defaultTerminalProfileId)
        assertEquals(KEYBOARD_PROFILE_ID, catalog.defaultKeyboardProfileId)
        assertEquals(records.migrationWarningCodes, catalog.migrationWarningCodes)
    }

    @Test
    fun catalogAndCompatibilityViewsComeFromOneCachedAuthoritativeSnapshot() = runTest {
        val persistence = FakePersistence(catalogRecords())
        var authorityCalls = 0
        val repository = repository(
            persistence = persistence,
            authority = { authorityCalls += 1 },
        )

        val first = repository.loadCatalog()
        val firstCatalog = requireNotNull(first.catalog)
        val compatibilityHost = first.compatibility.settings.profiles.single()
        val catalogHost = firstCatalog.hosts.single()
        assertEquals(catalogHost.profile.id, compatibilityHost.persistentId)
        assertEquals(catalogHost.profile.displayName, compatibilityHost.label)
        assertEquals(catalogHost.presentationId, compatibilityHost.id)
        assertEquals(1, persistence.readCount)

        persistence.records = persistence.records.copy(
            hosts = persistence.records.hosts.map { it.copy(displayName = "New database value") },
        )
        val second = repository.loadCatalog()

        assertEquals(first, second)
        assertEquals(1, persistence.readCount)
        assertEquals(2, authorityCalls)
    }

    @Test
    fun newerSharedGenerationReloadsSnapshotAndTombstonesRemovedUuid() = runTest {
        val gate = readyAuthorityGate()
        val persistence = FakePersistence(populatedRecords(withPassword = false))
        val repository = repository(persistence = persistence, gate = gate)
        val staleProfile = repository.load().settings.profiles.single()
        val replacementId = "10000000-0000-4000-8000-000000000011"
        val replacement = persistence.records.hosts.single().copy(
            id = replacementId,
            displayName = "Imported replacement",
        )

        gate.withMutation {
            persistence.records = persistence.records.copy(hosts = listOf(replacement))
            assertEquals(1L, markCommitted())
        }

        val reloaded = repository.load().settings.profiles.single()

        assertEquals("Imported replacement", reloaded.label)
        assertEquals(replacementId, reloaded.persistentId)
        assertTrue(reloaded.id > staleProfile.id)
        assertEquals(2, persistence.readCount)
        assertTrue(
            repository.runCatching {
                saveProfile(staleProfile.copy(label = "Must not resurrect"))
            }.exceptionOrNull() is IllegalStateException,
        )
        assertEquals(listOf(replacementId), persistence.records.hosts.map(HostProfile::id))
    }

    @Test
    fun catalogReadWaitsForSharedMutationAndReloadsItsGeneration() = runTest {
        val gate = readyAuthorityGate()
        val persistence = FakePersistence(populatedRecords(withPassword = false))
        val repository = repository(persistence = persistence, gate = gate)
        repository.load()
        val mutationEntered = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        val mutation = async {
            gate.withMutation {
                mutationEntered.complete(Unit)
                releaseMutation.await()
                persistence.records = persistence.records.copy(
                    hosts = persistence.records.hosts.map {
                        it.copy(displayName = "Committed while read waited")
                    },
                )
                markCommitted()
            }
        }
        mutationEntered.await()
        val read = async { repository.load() }

        runCurrent()
        assertFalse(read.isCompleted)
        assertEquals(1, persistence.readCount)

        releaseMutation.complete(Unit)
        mutation.await()

        assertEquals(
            "Committed while read waited",
            read.await().settings.profiles.single().label,
        )
        assertEquals(2, persistence.readCount)
    }

    @Test
    fun catalogLoadCannotReadPersistenceBeforeTheAuthorityGate() = runTest {
        val persistence = FakePersistence(catalogRecords())
        val expected = ExpectedFailure()
        val repository = repository(
            persistence = persistence,
            authority = { throw expected },
        )

        val actual = repository.runCatching { loadCatalog() }.exceptionOrNull()

        assertEquals(expected, actual)
        assertEquals(0, persistence.readCount)
    }

    @Test
    fun catalogDistinguishesUnavailableAvailableAndPromptOnlySecretMetadata() = runTest {
        val promptCredential = SshCredential(
            id = "10000000-0000-4000-8000-000000000021",
            displayName = "Prompt only",
            authentication = SshAuthentication.Password(),
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        )
        val interactiveCredential = SshCredential(
            id = "10000000-0000-4000-8000-000000000022",
            displayName = "Reusable response",
            authentication = SshAuthentication.KeyboardInteractive(AVAILABLE_SECRET_ID),
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        )
        val records = catalogRecords().let { records ->
            records.copy(
                credentials = records.credentials + promptCredential + interactiveCredential,
                unavailableSecretIds = setOf(SECRET_ID, PRIVATE_SECRET_ID),
            )
        }

        val catalog = requireNotNull(repository(FakePersistence(records)).loadCatalog().catalog)
        val credentialByName = catalog.credentials.associateBy(CatalogSshCredential::displayName)

        assertEquals(
            CatalogSecretAvailability.UNAVAILABLE,
            credentialByName.getValue("Generated key login").savedSecretAvailability,
        )
        assertEquals(
            CatalogSecretAvailability.NOT_CONFIGURED,
            credentialByName.getValue("Prompt only").savedSecretAvailability,
        )
        assertEquals(
            CatalogSecretAvailability.AVAILABLE,
            credentialByName.getValue("Reusable response").savedSecretAvailability,
        )
        assertEquals(CatalogSecretAvailability.UNAVAILABLE, catalog.identities.single().privateKeyAvailability)
        assertEquals(2, catalog.unavailableSecretCount)
    }

    @Test
    fun catalogDtosAndCredentialMetadataExposeNoSecretBufferTypes() {
        val catalogTypes = listOf(
            TerminalDataCatalog::class.java,
            CatalogHostProfile::class.java,
            CatalogSshCredential::class.java,
            CatalogSshKeyIdentity::class.java,
            CatalogSnippet::class.java,
            SshCredential::class.java,
            SshAuthentication.Password::class.java,
            SshAuthentication.PrivateKey::class.java,
            SshAuthentication.KeyboardInteractive::class.java,
        )

        val fields = catalogTypes.flatMap { type -> type.declaredFields.toList() }
        assertTrue(fields.none { it.type == ByteArray::class.java || it.type == CharArray::class.java })
        assertTrue(
            catalogTypes.flatMap { type -> type.declaredMethods.toList() }.none {
                it.returnType == ByteArray::class.java || it.returnType == CharArray::class.java
            },
        )
    }

    @Test
    fun projectionNeverDisguisesMoshPrivateKeyOrKeyboardInteractiveAsPasswordSsh() = runTest {
        val base = populatedRecords()
        val baseHost = base.hosts.single()
        val privateCredential = SshCredential(
            id = "10000000-0000-4000-8000-000000000021",
            displayName = "Private key login",
            authentication = SshAuthentication.PrivateKey(IDENTITY_ID),
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        )
        val interactiveCredential = SshCredential(
            id = "10000000-0000-4000-8000-000000000022",
            displayName = "Interactive login",
            authentication = SshAuthentication.KeyboardInteractive(),
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        )
        val records = base.copy(
            hosts = listOf(
                baseHost.copy(
                    id = "10000000-0000-4000-8000-000000000011",
                    displayName = "Mosh",
                    protocol = ConnectionProtocol.MOSH,
                ),
                baseHost.copy(
                    id = "10000000-0000-4000-8000-000000000012",
                    displayName = "Private key",
                    credentialId = privateCredential.id,
                ),
                baseHost.copy(
                    id = "10000000-0000-4000-8000-000000000013",
                    displayName = "Interactive",
                    credentialId = interactiveCredential.id,
                ),
                baseHost.copy(
                    id = "10000000-0000-4000-8000-000000000014",
                    displayName = "Options",
                    startupCommand = "printf ready",
                ),
            ),
            credentials = base.credentials + privateCredential + interactiveCredential,
        )

        val profiles = repository(FakePersistence(records)).load().settings.profiles.associateBy { it.label }

        assertEquals(
            SavedHostConnectionCompatibility.MOSH_PASSWORD,
            profiles.getValue("Mosh").connectionCompatibility,
        )
        assertEquals(
            SavedHostConnectionCompatibility.PRIVATE_KEY_REQUIRES_FULL_UI,
            profiles.getValue("Private key").connectionCompatibility,
        )
        assertEquals(
            SavedHostConnectionCompatibility.KEYBOARD_INTERACTIVE_UNAVAILABLE,
            profiles.getValue("Interactive").connectionCompatibility,
        )
        assertEquals(
            SavedHostConnectionCompatibility.PROFILE_OPTIONS_REQUIRE_FULL_UI,
            profiles.getValue("Options").connectionCompatibility,
        )
        assertTrue(profiles.getValue("Mosh").canConnectFromCompatibilityUi)
        assertTrue(
            profiles.filterKeys { it != "Mosh" }.values.none { it.canConnectFromCompatibilityUi },
        )

        val customKeyboardRecords = populatedRecords().let { current ->
            current.copy(
                defaultKeyboardProfile = current.defaultKeyboardProfile.copy(
                    layout = KeyboardLayout.TWO_ROWS,
                    modifierBehavior = ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK,
                    inputMode = TerminalInputMode.TEXT,
                ),
            )
        }
        val customKeyboardSettings = repository(FakePersistence(customKeyboardRecords))
            .load().settings
        val customKeyboardProfile = customKeyboardSettings.profiles.single()
        assertEquals(
            SavedHostConnectionCompatibility.PROFILE_OPTIONS_REQUIRE_FULL_UI,
            customKeyboardProfile.connectionCompatibility,
        )
        assertTrue(!customKeyboardSettings.keyboardRuntimeCompatible)
        assertTrue(customKeyboardSettings.extraKeys.isEmpty())
    }

    @Test
    fun coldRebuildIsDeterministicCollisionFreeAndNeverReusesRetiredIds() = runTest {
        val records = populatedRecords()
        val first = repository(FakePersistence(records)).load().settings
        val second = repository(FakePersistence(records)).load().settings

        assertEquals(first, second)
        val allIds = first.profiles.map { it.id } + first.identities.map { it.id } +
            first.snippets.map { it.id }
        assertEquals(allIds.size, allIds.distinct().size)

        val registry = EphemeralTerminalIdRegistry()
        registry.rebuild(
            listOf(
                TerminalRecordKind.HOST to HOST_ID,
                TerminalRecordKind.IDENTITY to IDENTITY_ID,
            ),
        )
        val hostPresentationId = registry.presentationId(TerminalRecordKind.HOST, HOST_ID)
        assertThrows(IllegalStateException::class.java) {
            registry.resolveOrReserve(TerminalRecordKind.SNIPPET, hostPresentationId)
        }
        registry.retire(TerminalRecordKind.HOST, hostPresentationId)
        assertThrows(IllegalStateException::class.java) {
            registry.resolveOrReserve(TerminalRecordKind.HOST, hostPresentationId)
        }
        assertThrows(IllegalStateException::class.java) {
            registry.resolveOrReserve(TerminalRecordKind.SNIPPET, hostPresentationId)
        }
    }

    @Test
    fun acknowledgedProfileWriteKeepsRoomUuidAndRebuildsForeignKeyDefaults() = runTest {
        val persistence = FakePersistence(populatedRecords())
        var authorityCalls = 0
        val repository = repository(
            persistence = persistence,
            authority = { authorityCalls += 1 },
        )
        val loaded = repository.load().settings
        val profile = loaded.profiles.single()

        val saved = repository.saveProfile(
            profile.copy(
                label = "Renamed",
                host = "renamed.example",
            ),
        )

        assertEquals("Renamed", saved.profiles.single().label)
        assertEquals(HOST_ID, persistence.lastHostWrite?.id)
        assertEquals(TERMINAL_PROFILE_ID, persistence.lastHostWrite?.terminalProfileId)
        assertEquals(KEYBOARD_PROFILE_ID, persistence.lastHostWrite?.keyboardProfileId)
        assertTrue(persistence.readCount >= 2)
        assertEquals(2, authorityCalls)
    }

    @Test
    fun existingNullProfileOverridesRemainNullAcrossMetadataAndPasswordWrites() = runTest {
        val initial = populatedRecords(withPassword = false).let { records ->
            records.copy(
                hosts = records.hosts.map {
                    it.copy(terminalProfileId = null, keyboardProfileId = null)
                },
            )
        }
        val persistence = FakePersistence(initial)
        val repository = repository(persistence)
        var profile = repository.load().settings.profiles.single()

        profile = repository.saveProfile(profile.copy(label = "Null overrides")).profiles.single()
        assertEquals(null, persistence.records.hosts.single().terminalProfileId)
        assertEquals(null, persistence.records.hosts.single().keyboardProfileId)

        repository.saveProfileWithPassword(profile, byteArrayOf(1, 2, 3))
        assertEquals(null, persistence.records.hosts.single().terminalProfileId)
        assertEquals(null, persistence.records.hosts.single().keyboardProfileId)
    }

    @Test
    fun editingCompatibilityProfileNeverDetachesNonPasswordAuthentication() = runTest {
        listOf(
            SshAuthentication.PrivateKey(IDENTITY_ID),
            SshAuthentication.KeyboardInteractive(),
        ).forEach { authentication ->
            val credential = SshCredential(
                id = CREDENTIAL_ID,
                displayName = "Server authentication",
                authentication = authentication,
                createdAtEpochMillis = 10,
                updatedAtEpochMillis = 20,
            )
            val initial = populatedRecords(withPassword = false).let { records ->
                records.copy(
                    credentials = listOf(credential),
                    hosts = records.hosts.map { host -> host.copy(credentialId = CREDENTIAL_ID) },
                )
            }
            val persistence = FakePersistence(initial)
            val repository = repository(persistence)
            val profile = repository.load().settings.profiles.single()
            assertTrue(!profile.hasSavedPassword)

            repository.saveProfile(profile.copy(label = "Renamed authenticated host"))

            assertEquals(CREDENTIAL_ID, persistence.lastHostWrite?.credentialId)
        }
    }

    @Test
    fun failedMetadataWriteIsNotAcknowledgedAndReservedUuidSurvivesRetry() = runTest {
        val persistence = FakePersistence(emptyRecords())
        val repository = repository(persistence)
        repository.load()
        val candidate = SavedSshProfile(
            id = 1,
            label = "Server",
            host = "server.example",
            port = 22,
            username = "alice",
        )
        persistence.hostWriteFailure = ExpectedFailure()

        assertTrue(repository.runCatching { saveProfile(candidate) }.exceptionOrNull() is ExpectedFailure)
        val firstAttemptUuid = persistence.lastAttemptedHostId
        assertTrue(persistence.records.hosts.isEmpty())

        persistence.hostWriteFailure = null
        val saved = repository.saveProfile(candidate)
        assertEquals(firstAttemptUuid, persistence.lastAttemptedHostId)
        assertEquals(firstAttemptUuid, persistence.records.hosts.single().id)
        assertEquals(candidate.copy(persistentId = firstAttemptUuid), saved.profiles.single())
    }

    @Test
    fun repositoryMutationAdvancesOnlyAfterPersistenceReportsSuccess() = runTest {
        val gate = readyAuthorityGate()
        val persistence = FakePersistence(populatedRecords(withPassword = false))
        val repository = repository(persistence = persistence, gate = gate)
        val profile = repository.load().settings.profiles.single()
        persistence.hostWriteFailure = ExpectedFailure()

        assertTrue(
            repository.runCatching {
                saveProfile(profile.copy(label = "Rejected rename"))
            }.exceptionOrNull() is ExpectedFailure,
        )
        assertEquals(0L, gate.awaitReady())
        assertEquals("Server", repository.load().settings.profiles.single().label)

        persistence.hostWriteFailure = null
        val committed = repository.saveProfile(profile.copy(label = "Committed rename"))

        assertEquals(1L, gate.awaitReady())
        assertEquals("Committed rename", committed.profiles.single().label)
    }

    @Test
    fun cancellationWhileWaitingForMutationGateDoesNotPersistOrAdvance() = runTest {
        val gate = readyAuthorityGate()
        val persistence = FakePersistence(populatedRecords(withPassword = false))
        val repository = repository(persistence = persistence, gate = gate)
        val profile = repository.load().settings.profiles.single()
        val blockerEntered = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        val blocker = async {
            gate.withMutation {
                blockerEntered.complete(Unit)
                releaseBlocker.await()
            }
        }
        blockerEntered.await()
        val cancelled = launch {
            repository.saveProfile(profile.copy(label = "Cancelled rename"))
        }

        runCurrent()
        cancelled.cancelAndJoin()
        releaseBlocker.complete(Unit)
        blocker.await()

        assertEquals(0L, gate.awaitReady())
        assertEquals("Server", persistence.records.hosts.single().displayName)
        assertEquals("Server", repository.load().settings.profiles.single().label)
    }

    @Test
    fun credentialFailureWipesInputAndDoesNotPublishRelationshipBeforeSuccess() = runTest {
        val persistence = FakePersistence(populatedRecords(withPassword = false))
        val repository = repository(persistence)
        val profileId = repository.load().settings.profiles.single().id
        val failedSecret = byteArrayOf(1, 2, 3)
        persistence.passwordFailure = ExpectedFailure()

        assertTrue(
            repository.runCatching { savePassword(profileId, failedSecret) }.exceptionOrNull() is
                ExpectedFailure,
        )
        assertArrayEquals(byteArrayOf(0, 0, 0), failedSecret)
        assertTrue(persistence.records.credentials.isEmpty())
        assertEquals(null, persistence.records.hosts.single().credentialId)

        persistence.passwordFailure = null
        val successfulSecret = byteArrayOf(4, 5, 6)
        val saved = repository.savePassword(profileId, successfulSecret)
        val storedHost = persistence.records.hosts.single()
        val storedCredential = persistence.records.credentials.single()
        assertArrayEquals(byteArrayOf(0, 0, 0), successfulSecret)
        assertEquals(storedCredential.id, storedHost.credentialId)
        assertEquals(storedHost.credentialId, persistence.lastExpectedCredentialAfterWrite)
        assertTrue(saved.profiles.single().hasSavedPassword)
    }

    @Test
    fun profileAndPasswordCommitAsOneMutationAndWipePlaintext() = runTest {
        val persistence = FakePersistence(emptyRecords())
        val repository = repository(persistence)
        repository.load()
        val profile = SavedSshProfile(
            id = 1,
            label = "Atomic server",
            host = "atomic.example",
            port = 22,
            username = "alice",
        )
        val failedPassword = byteArrayOf(1, 2, 3)
        persistence.passwordFailure = ExpectedFailure()

        assertTrue(
            repository.runCatching {
                saveProfileWithPassword(profile, failedPassword)
            }.exceptionOrNull() is ExpectedFailure,
        )
        assertArrayEquals(byteArrayOf(0, 0, 0), failedPassword)
        assertTrue(persistence.records.hosts.isEmpty())
        assertTrue(persistence.records.credentials.isEmpty())

        persistence.passwordFailure = null
        val password = byteArrayOf(4, 5, 6)
        val committed = repository.saveProfileWithPassword(profile, password)
        assertArrayEquals(byteArrayOf(0, 0, 0), password)
        assertTrue(committed.profiles.single().hasSavedPassword)
        assertEquals(
            persistence.records.credentials.single().id,
            persistence.records.hosts.single().credentialId,
        )
    }

    @Test
    fun newMoshPasswordProfilePreservesProtocolAndBootstrapOptions() = runTest {
        val persistence = FakePersistence(emptyRecords())
        val repository = repository(persistence)
        repository.load()
        val profile = SavedSshProfile(
            id = 1,
            label = "Roaming shell",
            host = "mosh.example",
            port = 22,
            username = "alice",
            protocol = ConnectionProtocol.MOSH,
            moshPortRange = MoshPortRange(60_000, 60_010),
            moshServerCommand = "mosh-server",
        )

        val committed = repository.saveProfileWithPassword(profile, byteArrayOf(4, 5, 6))

        val stored = persistence.records.hosts.single()
        assertEquals(ConnectionProtocol.MOSH, stored.protocol)
        assertEquals(MoshPortRange(60_000, 60_010), stored.moshPortRange)
        assertEquals("mosh-server", stored.moshServerCommand)
        assertEquals(
            SavedHostConnectionCompatibility.MOSH_PASSWORD,
            committed.profiles.single().connectionCompatibility,
        )
        assertEquals(MoshPortRange(60_000, 60_010), committed.profiles.single().moshPortRange)
    }

    @Test
    fun postCommitRefreshFailuresInvalidateOldSnapshotAndRecoverFromRoomTruth() = runTest {
        val persistence = FakePersistence(populatedRecords(withPassword = false))
        val repository = repository(persistence)
        var loaded = repository.load().settings

        persistence.nextReadFailure = ExpectedFailure()
        loaded = repository.saveProfile(
            loaded.profiles.single().copy(label = "Committed rename"),
        )
        assertEquals("Committed rename", loaded.profiles.single().label)

        val password = byteArrayOf(4, 5, 6)
        persistence.nextReadFailure = ExpectedFailure()
        loaded = repository.savePassword(loaded.profiles.single().id, password)
        assertArrayEquals(byteArrayOf(0, 0, 0), password)
        assertTrue(loaded.profiles.single().hasSavedPassword)

        persistence.nextReadFailure = ExpectedFailure()
        loaded = repository.deleteProfile(loaded.profiles.single().id)
        assertTrue(loaded.profiles.isEmpty())

        persistence.nextReadFailure = ExpectedFailure()
        loaded = repository.deleteIdentity(loaded.identities.single().id)
        assertTrue(loaded.identities.isEmpty())

        persistence.nextReadFailure = ExpectedFailure()
        loaded = repository.deleteSnippet(loaded.snippets.single().id)
        assertTrue(loaded.snippets.isEmpty())
    }

    @Test
    fun cancellationAfterCommittedDeleteReconcilesRoomTruthAndRetiresUuid() = runTest {
        val gate = readyAuthorityGate()
        val persistence = FakePersistence(populatedRecords(withPassword = false))
        val repository = repository(persistence = persistence, gate = gate)
        val staleProfile = repository.load().settings.profiles.single()
        lateinit var delete: Deferred<UserSettings>
        persistence.deleteHostPostCommitAction = {
            delete.cancel(CancellationException("cancelled after commit"))
        }
        delete = async(start = CoroutineStart.LAZY) {
            repository.deleteProfile(staleProfile.id)
        }

        delete.start()
        val failure = runCatching { delete.await() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1L, gate.awaitReady())
        assertTrue(repository.load().settings.profiles.isEmpty())
        assertTrue(
            repository.runCatching { saveProfile(staleProfile.copy(label = "Must not resurrect")) }
                .exceptionOrNull() is IllegalStateException,
        )
        assertTrue(persistence.records.hosts.isEmpty())

        val freshId = repository.reserveHostPresentationId()
        assertTrue(freshId > staleProfile.id)
        repository.saveProfile(
            staleProfile.copy(id = freshId, label = "Fresh host after recreation"),
        )
        assertEquals("Fresh host after recreation", persistence.records.hosts.single().displayName)
    }

    @Test
    fun failedReservationCannotCollideWithLaterCrossKindAllocation() = runTest {
        val persistence = FakePersistence(emptyRecords())
        val repository = repository(persistence)
        repository.load()
        val reservedHostId = repository.reserveHostPresentationId()
        persistence.hostWriteFailure = ExpectedFailure()
        val failedHost = SavedSshProfile(
            id = reservedHostId,
            label = "Failed host",
            host = "failed.example",
            port = 22,
            username = "alice",
        )
        assertTrue(
            repository.runCatching { saveProfile(failedHost) }.exceptionOrNull() is ExpectedFailure,
        )

        val snippetId = repository.reserveSnippetPresentationId()
        assertTrue(snippetId > reservedHostId)
        persistence.hostWriteFailure = null
        repository.saveSnippet(
            CommandSnippet(
                id = snippetId,
                label = "Fresh snippet",
                command = "uptime",
                appendEnter = true,
            ),
        )
        assertEquals("Fresh snippet", persistence.records.snippets.single().name)
        assertTrue(persistence.records.hosts.isEmpty())
    }

    @Test
    fun authoritativeReadFailurePreservesRoomTruthAndRetryRecoversWithoutLegacyDiscard() = runTest {
        val persistence = FakePersistence(populatedRecords())
        persistence.nextReadFailure = ExpectedFailure()
        var discards = 0
        val repository = repository(
            persistence = persistence,
            discard = { discards += 1 },
        )

        val unavailable = repository.load()

        assertEquals(SettingsLoadFailure.APP_DATA_UNAVAILABLE, unavailable.failure)
        assertTrue(unavailable.settings.profiles.isEmpty())
        assertEquals(0, discards)
        assertTrue(
            repository.runCatching { resetAfterRecoveryConfirmation() }.exceptionOrNull()
                is IllegalStateException,
        )

        val recovered = repository.load()
        assertEquals(null, recovered.failure)
        assertEquals("Server", recovered.settings.profiles.single().label)
        assertEquals(0, discards)
    }

    @Test
    fun unavailableMigratedSecretsAreNeverPresentedAsUsableAndSurfaceRecoveryWarning() = runTest {
        val persistence = FakePersistence(
            populatedRecords().copy(
                unavailableSecretIds = setOf(SECRET_ID, PRIVATE_SECRET_ID),
                migrationWarningCodes = listOf("password_key_unavailable", "private_key_corrupt"),
            ),
        )

        val loaded = repository(persistence).load()

        assertEquals(null, loaded.failure)
        assertTrue(loaded.warning.orEmpty().contains("2 migrated credentials are unavailable"))
        assertTrue(!loaded.settings.profiles.single().hasSavedPassword)
        assertTrue(!loaded.settings.identities.single().isAvailable)
        assertTrue(persistence.records.hosts.single().credentialId != null)
        assertEquals(1, persistence.records.identities.size)
    }

    @Test
    fun metadataEditPreservesUnavailablePasswordUntilExplicitRecoveryAction() = runTest {
        val persistence = FakePersistence(
            populatedRecords().copy(
                unavailableSecretIds = setOf(SECRET_ID),
                migrationWarningCodes = listOf("password_key_unavailable"),
            ),
        )
        val repository = repository(persistence)
        val loaded = repository.load()
        val profile = loaded.settings.profiles.single()
        assertTrue(!profile.hasSavedPassword)

        val renamed = repository.saveProfile(profile.copy(label = "Renamed unavailable host"))

        assertTrue(!renamed.profiles.single().hasSavedPassword)
        assertEquals(CREDENTIAL_ID, persistence.records.hosts.single().credentialId)
        assertEquals(CREDENTIAL_ID, persistence.records.credentials.single().id)
        assertTrue(SECRET_ID in persistence.records.unavailableSecretIds)
    }

    @Test
    fun reimportRepairsUnavailableIdentityInPlaceAndRejectsDifferentKey() = runTest {
        val unavailable = populatedRecords().copy(
            unavailableSecretIds = setOf(PRIVATE_SECRET_ID),
            migrationWarningCodes = listOf("private_key_key_unavailable"),
        )
        val persistence = FakePersistence(unavailable)
        val repository = repository(persistence)
        val identity = repository.load().settings.identities.single()
        assertTrue(!identity.isAvailable)
        val replacement = byteArrayOf(7, 8, 9)

        val repaired = repository.importIdentity(identity.id, identity.label, replacement)

        assertArrayEquals(byteArrayOf(0, 0, 0), replacement)
        assertTrue(repaired.identities.single().isAvailable)
        assertEquals(IDENTITY_ID, persistence.records.identities.single().id)
        assertEquals(
            PRIVATE_SECRET_ID,
            persistence.records.identities.single().privateKeySecretReferenceId,
        )
        assertTrue(PRIVATE_SECRET_ID !in persistence.records.unavailableSecretIds)

        val mismatchRecords = unavailable.copy(
            identities = unavailable.identities.map {
                it.copy(publicKeyFingerprint = "SHA256:" + "B".repeat(43))
            },
        )
        val mismatchPersistence = FakePersistence(mismatchRecords)
        val mismatchRepository = repository(mismatchPersistence)
        val mismatchIdentity = mismatchRepository.load().settings.identities.single()
        val wrongKey = byteArrayOf(1, 2, 3)
        val failure = mismatchRepository.runCatching {
            importIdentity(mismatchIdentity.id, mismatchIdentity.label, wrongKey)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertArrayEquals(byteArrayOf(0, 0, 0), wrongKey)
        assertEquals(mismatchRecords, mismatchPersistence.records)
        assertEquals(0, mismatchPersistence.identityImportCount)
    }

    @Test
    fun sameIdReimportPreservesGeneratedOriginWithPromptOnlyDependentCredential() = runTest {
        val promptOnlyKeyCredential = SshCredential(
            id = CREDENTIAL_ID,
            displayName = "Generated key login",
            authentication = SshAuthentication.PrivateKey(IDENTITY_ID),
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        )
        val initial = populatedRecords(withPassword = false).let { records ->
            records.copy(
                credentials = listOf(promptOnlyKeyCredential),
                identities = records.identities.map { identity ->
                    identity.copy(origin = SshKeyOrigin.GENERATED, publicKey = PUBLIC_KEY)
                },
            )
        }
        val persistence = FakePersistence(initial)
        val repository = repository(persistence)
        val identity = repository.load().settings.identities.single()
        val replacement = byteArrayOf(7, 8, 9)

        repository.importIdentity(identity.id, "Renamed generated key", replacement)

        assertArrayEquals(byteArrayOf(0, 0, 0), replacement)
        assertEquals(1, persistence.identityImportCount)
        val stored = persistence.records.identities.single()
        assertEquals(SshKeyOrigin.GENERATED, stored.origin)
        assertEquals(PRIVATE_SECRET_ID, stored.privateKeySecretReferenceId)
        assertEquals("Renamed generated key", stored.name)
    }

    @Test
    fun sameIdReimportWithSavedDependentPassphraseIsRejectedBeforeInspectionOrMutation() = runTest {
        val savedPassphraseCredential = SshCredential(
            id = CREDENTIAL_ID,
            displayName = "Key login with saved passphrase",
            authentication = SshAuthentication.PrivateKey(IDENTITY_ID, SECRET_ID),
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        )
        val initial = populatedRecords(withPassword = false).copy(
            credentials = listOf(savedPassphraseCredential),
        )
        val persistence = FakePersistence(initial)
        var inspectionCount = 0
        val repository = repository(
            persistence = persistence,
            privateKeyInspector = PrivateKeyMetadataInspector {
                inspectionCount += 1
                matchingPrivateKeyMetadata()
            },
        )
        val identity = repository.load().settings.identities.single()
        val replacement = byteArrayOf(4, 5, 6)

        val failure = repository.runCatching {
            importIdentity(identity.id, identity.label, replacement)
        }.exceptionOrNull()

        assertTrue(failure is IdentityReimportBlockedBySavedPassphraseException)
        assertArrayEquals(byteArrayOf(0, 0, 0), replacement)
        assertEquals(0, inspectionCount)
        assertEquals(0, persistence.identityImportCount)
        assertEquals(initial, persistence.records)
    }

    @Test
    fun metadataEditPreservesPromptOnlyPasswordCredentialForSameEndpoint() = runTest {
        val promptCredential = SshCredential(
            id = CREDENTIAL_ID,
            displayName = "Prompt each time",
            authentication = SshAuthentication.Password(),
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 20,
        )
        val persistence = FakePersistence(
            populatedRecords(withPassword = false).let { records ->
                records.copy(
                    credentials = listOf(promptCredential),
                    hosts = records.hosts.map { it.copy(credentialId = CREDENTIAL_ID) },
                )
            },
        )
        val repository = repository(persistence)
        val profile = repository.load().settings.profiles.single()
        assertTrue(!profile.hasSavedPassword)

        repository.saveProfile(profile.copy(label = "Renamed prompt host"))

        assertEquals(CREDENTIAL_ID, persistence.records.hosts.single().credentialId)
        assertEquals(SshAuthentication.Password(), persistence.records.credentials.single().authentication)
    }

    @Test
    fun compatibilityKeyboardSavePreservesRoomOnlyActionsAndHiddenOnlyProfile() = runTest {
        val mixedPersistence = FakePersistence(
            emptyRecords().copy(
                defaultKeyboardProfile = emptyRecords().defaultKeyboardProfile.copy(
                    orderedActions = listOf(
                        KeyboardAction.ESCAPE,
                        KeyboardAction.TMUX_PREFIX,
                        KeyboardAction.PASTE,
                        KeyboardAction.CONTROL,
                    ),
                ),
            ),
        )
        val mixedRepository = repository(mixedPersistence)
        val visible = mixedRepository.load().settings.extraKeys
        mixedRepository.saveExtraKeys(visible)
        assertEquals(
            listOf(
                KeyboardAction.ESCAPE,
                KeyboardAction.TMUX_PREFIX,
                KeyboardAction.PASTE,
                KeyboardAction.CONTROL,
            ),
            mixedPersistence.records.defaultKeyboardProfile.orderedActions,
        )

        val hiddenPersistence = FakePersistence(
            emptyRecords().copy(
                defaultKeyboardProfile = emptyRecords().defaultKeyboardProfile.copy(
                    orderedActions = listOf(KeyboardAction.TMUX_PREFIX),
                ),
            ),
        )
        val hiddenRepository = repository(hiddenPersistence)
        val visibleHiddenOnlyKeys = hiddenRepository.load().settings.extraKeys
        assertTrue(visibleHiddenOnlyKeys.isEmpty())
        hiddenRepository.saveExtraKeys(visibleHiddenOnlyKeys)
        assertEquals(
            listOf(KeyboardAction.TMUX_PREFIX),
            hiddenPersistence.records.defaultKeyboardProfile.orderedActions,
        )

        val reset = hiddenRepository.saveExtraKeys(TerminalExtraKey.DEFAULT_ORDER)
        assertEquals(KeyboardAction.TMUX_PREFIX, hiddenPersistence.records.defaultKeyboardProfile.orderedActions.first())
        assertEquals(
            TerminalExtraKey.DEFAULT_ORDER.size + 1,
            hiddenPersistence.records.defaultKeyboardProfile.orderedActions.size,
        )
        assertEquals(TerminalExtraKey.DEFAULT_ORDER, reset.extraKeys)
    }

    @Test
    fun untouchedShippedKeyboardDeckIsPresentedAsCurrentDefaultWithoutChangingCustomOrder() = runTest {
        val shippedActions = listOf(
            KeyboardAction.ESCAPE,
            KeyboardAction.SLASH,
            KeyboardAction.AT_SIGN,
            KeyboardAction.DOLLAR,
            KeyboardAction.HOME,
            KeyboardAction.ARROW_UP,
            KeyboardAction.END,
            KeyboardAction.PAGE_UP,
            KeyboardAction.CTRL_B,
            KeyboardAction.TAB,
            KeyboardAction.CONTROL,
            KeyboardAction.CTRL_C,
            KeyboardAction.CTRL_W,
            KeyboardAction.ARROW_LEFT,
            KeyboardAction.ARROW_DOWN,
            KeyboardAction.ARROW_RIGHT,
            KeyboardAction.HIDE_KEYBOARD,
        )
        val shippedPersistence = FakePersistence(
            emptyRecords().copy(
                defaultKeyboardProfile = emptyRecords().defaultKeyboardProfile.copy(
                    orderedActions = shippedActions,
                ),
            ),
        )

        val presentedDefault = repository(shippedPersistence).load().settings.extraKeys

        assertEquals(TerminalExtraKey.DEFAULT_ORDER, presentedDefault)
        assertEquals(shippedActions, shippedPersistence.records.defaultKeyboardProfile.orderedActions)

        val pagedShippedActions = listOf(
            KeyboardAction.ESCAPE,
            KeyboardAction.CONTROL,
            KeyboardAction.ALT,
            KeyboardAction.TAB,
            KeyboardAction.CTRL_C,
            KeyboardAction.CTRL_W,
            KeyboardAction.CTRL_D,
            KeyboardAction.CTRL_L,
            KeyboardAction.CTRL_R,
            KeyboardAction.CTRL_U,
            KeyboardAction.CTRL_A,
            KeyboardAction.CTRL_E,
            KeyboardAction.HOME,
            KeyboardAction.ARROW_UP,
            KeyboardAction.END,
            KeyboardAction.PAGE_UP,
            KeyboardAction.ARROW_LEFT,
            KeyboardAction.ARROW_DOWN,
            KeyboardAction.ARROW_RIGHT,
            KeyboardAction.CTRL_B,
            KeyboardAction.SLASH,
            KeyboardAction.AT_SIGN,
            KeyboardAction.HIDE_KEYBOARD,
        )
        val pagedPersistence = FakePersistence(
            emptyRecords().copy(
                defaultKeyboardProfile = emptyRecords().defaultKeyboardProfile.copy(
                    orderedActions = pagedShippedActions,
                ),
            ),
        )

        assertEquals(
            TerminalExtraKey.DEFAULT_ORDER,
            repository(pagedPersistence).load().settings.extraKeys,
        )
        assertEquals(
            pagedShippedActions,
            pagedPersistence.records.defaultKeyboardProfile.orderedActions,
        )

        val customActions = listOf(
            KeyboardAction.CTRL_W,
            KeyboardAction.ESCAPE,
            KeyboardAction.CTRL_C,
            KeyboardAction.ALT,
        )
        val customPersistence = FakePersistence(
            emptyRecords().copy(
                defaultKeyboardProfile = emptyRecords().defaultKeyboardProfile.copy(
                    orderedActions = customActions,
                ),
            ),
        )

        assertEquals(
            listOf(
                TerminalExtraKey.CTRL_W,
                TerminalExtraKey.ESC,
                TerminalExtraKey.CTRL_C,
                TerminalExtraKey.ALT,
            ),
            repository(customPersistence).load().settings.extraKeys,
        )
    }

    @Test
    fun editingImmediateSnippetToMultilineEnablesMandatoryConfirmation() = runTest {
        val original = populatedRecords().snippets.single().copy(
            command = "printf one",
            confirmMultilineExecution = false,
        )
        val persistence = FakePersistence(populatedRecords().copy(snippets = listOf(original)))
        val repository = repository(persistence)
        val snippet = repository.load().settings.snippets.single()
        assertTrue(!snippet.confirmMultilineExecution)

        repository.saveSnippet(snippet.copy(command = "printf one\nprintf two"))

        assertTrue(persistence.records.snippets.single().confirmMultilineExecution)

        val insertSnippet = original.copy(
            tapAction = SnippetTapAction.INSERT,
            command = "printf one\nprintf two",
            confirmMultilineExecution = false,
        )
        val insertPersistence = FakePersistence(
            populatedRecords().copy(snippets = listOf(insertSnippet)),
        )
        val insertRepository = repository(insertPersistence)
        val presentedInsert = insertRepository.load().settings.snippets.single()
        assertTrue(!presentedInsert.sendsImmediately)
        insertRepository.saveSnippet(presentedInsert.copy(label = "Renamed insert snippet"))
        assertTrue(!insertPersistence.records.snippets.single().confirmMultilineExecution)
    }

    @Test
    fun blockedRecoveryRequiresExplicitDiscardAndNeverFallsBackAfterAuthority() = runTest {
        val gate = readyAuthorityGate()
        val persistence = FakePersistence(emptyRecords())
        var blocked = true
        var discards = 0
        val repository = repository(
            persistence = persistence,
            authority = {
                if (blocked) {
                    throw TerminalDataCutoverBlockedException(
                        SettingsLoadFailure.CORRUPT_OR_UNSUPPORTED,
                        "settings_corrupt",
                    )
                }
            },
            discard = {
                discards += 1
                blocked = false
            },
            gate = gate,
        )

        val failed = repository.load()
        assertEquals(SettingsLoadFailure.CORRUPT_OR_UNSUPPORTED, failed.failure)
        assertEquals(0, persistence.readCount)

        val recovered = repository.resetAfterRecoveryConfirmation()
        assertEquals(1, discards)
        assertEquals(null, recovered.failure)
        assertEquals(1, persistence.readCount)
        assertEquals(1L, gate.awaitReady())

        repository.load()
        assertEquals(1, persistence.readCount)
        assertEquals(1, discards)
    }

    @Test
    fun uuidHostSavePersistsEveryEditorFieldAndWipesReplacementPassword() = runTest {
        val persistence = FakePersistence(emptyRecords())
        val repository = repository(persistence)
        repository.loadCatalog()
        val password = byteArrayOf(1, 2, 3, 4)
        val profile = HostProfile(
            id = HOST_ID,
            displayName = "Roaming production",
            hostname = "prod.example",
            port = 2222,
            username = "alice",
            protocol = ConnectionProtocol.MOSH,
            credentialId = null,
            terminalProfileId = TERMINAL_PROFILE_ID,
            keyboardProfileId = KEYBOARD_PROFILE_ID,
            isFavorite = true,
            group = "Work",
            tag = "prod",
            startupCommand = "tmux attach || tmux new",
            keepaliveIntervalSeconds = 45,
            reconnectPolicy = ReconnectPolicy.AUTOMATIC,
            moshPortRange = MoshPortRange(60_000, 60_010),
            moshServerCommand = "/usr/local/bin/mosh-server",
            createdAtEpochMillis = 90,
            updatedAtEpochMillis = 90,
        )

        repository.saveHostProfile(profile, HostAuthenticationUpdate.SavePassword(password))

        assertArrayEquals(byteArrayOf(0, 0, 0, 0), password)
        val stored = persistence.records.hosts.single()
        assertEquals(profile.copy(credentialId = stored.credentialId, updatedAtEpochMillis = 100), stored)
        assertTrue(
            persistence.records.credentials.single().authentication is SshAuthentication.Password,
        )
    }

    @Test
    fun uuidCrudUsesPersistentIdsForKeyMetadataAndSnippets() = runTest {
        val persistence = FakePersistence(populatedRecords(withPassword = false))
        val repository = repository(persistence)
        repository.loadCatalog()
        val renamed = repository.renameIdentity(IDENTITY_ID, "Production key", "rotated")
        assertEquals("Production key", renamed.identities.single().label)
        assertEquals("rotated", persistence.records.identities.single().comment)

        val snippet = Snippet(
            id = "10000000-0000-4000-8000-000000000016",
            name = "Health check",
            group = "Ops",
            command = "uptime",
            tapAction = SnippetTapAction.INSERT,
            appendEnter = false,
            confirmMultilineExecution = true,
            isFavorite = true,
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 100,
        )
        repository.saveSnippet(snippet)
        assertEquals(snippet, persistence.records.snippets.single { it.id == snippet.id })

        repository.deleteSnippet(snippet.id)
        assertTrue(persistence.records.snippets.none { it.id == snippet.id })
        repository.deleteIdentity(IDENTITY_ID)
        assertTrue(persistence.records.identities.isEmpty())
    }

    @Test
    fun everyOperationCrossesAuthorityAndSecretCopiesUseUuidRelationships() = runTest {
        val persistence = FakePersistence(populatedRecords())
        var authorityCalls = 0
        val repository = repository(
            persistence = persistence,
            authority = { authorityCalls += 1 },
        )
        val loaded = repository.load().settings
        val profile = loaded.profiles.single()
        val identity = loaded.identities.single()

        val password = repository.copyPassword(profile.id)
        val privateKey = repository.copyPrivateKey(identity.id)

        assertArrayEquals(FakePersistence.PASSWORD, password)
        assertArrayEquals(FakePersistence.PRIVATE_KEY, privateKey)
        assertEquals(HOST_ID, persistence.lastPasswordReadHostId)
        assertEquals(IDENTITY_ID, persistence.lastPrivateKeyReadIdentityId)
        assertEquals(3, authorityCalls)
    }

    private suspend fun repository(
        persistence: FakePersistence,
        authority: suspend () -> Unit = {},
        discard: suspend () -> Unit = {},
        privateKeyInspector: PrivateKeyMetadataInspector = PrivateKeyMetadataInspector {
            matchingPrivateKeyMetadata()
        },
        gate: AuthoritativeDataGate? = null,
    ): TerminalDataRepository {
        val readyGate = gate ?: readyAuthorityGate()
        return TerminalDataRepository(
            persistence = persistence,
            authorityGate = readyGate,
            requireAuthority = authority,
            discardBlockedLegacySettings = discard,
            privateKeyInspector = privateKeyInspector,
            clock = CredentialEpochClock { 100 },
        )
    }

    private suspend fun readyAuthorityGate(): AuthoritativeDataGate =
        AuthoritativeDataGate().also { candidate ->
            candidate.resolveStartup(
                hasPendingRecovery = { false },
                recoverPending = { error("Recovery must not run without a marker.") },
            )
        }

    private fun matchingPrivateKeyMetadata() = ImportedPrivateKeyMetadata(
        algorithm = "ssh-ed25519",
        fingerprintSha256 = FINGERPRINT,
        openSshPublicKey = PUBLIC_KEY,
        isPassphraseProtected = false,
    )

    private class FakePersistence(initial: TerminalDataRecords) : TerminalDataPersistence {
        var records = initial
        var readCount = 0
        var hostWriteFailure: RuntimeException? = null
        var passwordFailure: RuntimeException? = null
        var nextReadFailure: RuntimeException? = null
        var deleteHostPostCommitAction: (() -> Unit)? = null
        var lastHostWrite: HostProfile? = null
        var lastAttemptedHostId: String? = null
        var lastExpectedCredentialAfterWrite: String? = null
        var lastPasswordReadHostId: String? = null
        var lastPrivateKeyReadIdentityId: String? = null
        var identityImportCount = 0

        override suspend fun readRecords(): TerminalDataRecords {
            nextReadFailure?.let { failure ->
                nextReadFailure = null
                throw failure
            }
            readCount += 1
            return records
        }

        override suspend fun upsertHost(profile: HostProfile, retainPassword: Boolean) {
            lastAttemptedHostId = profile.id
            hostWriteFailure?.let { throw it }
            lastHostWrite = profile
            records = records.copy(
                hosts = records.hosts.filterNot { it.id == profile.id } + profile,
            )
        }

        override suspend fun upsertHostWithCredentialMetadata(
            profile: HostProfile,
            expectedCredentialId: String?,
            credential: SshCredential?,
        ) {
            val existing = records.hosts.firstOrNull { it.id == profile.id }
            check(existing?.credentialId == expectedCredentialId)
            check(profile.credentialId == credential?.id)
            records = records.copy(
                hosts = records.hosts.filterNot { it.id == profile.id } + profile,
                credentials = records.credentials
                    .filterNot { it.id == credential?.id }
                    .let { current -> if (credential == null) current else current + credential },
            )
        }

        override suspend fun upsertHostWithPassword(
            profile: HostProfile,
            expectedCredentialId: String?,
            credentialId: String,
            secretId: String,
            password: ByteArray,
        ) {
            try {
                passwordFailure?.let { throw it }
                val existing = records.hosts.firstOrNull { it.id == profile.id }
                check(existing?.credentialId == expectedCredentialId)
                val credential = SshCredential(
                    id = credentialId,
                    displayName = profile.displayName,
                    authentication = SshAuthentication.Password(secretId),
                    createdAtEpochMillis = 10,
                    updatedAtEpochMillis = 20,
                )
                records = records.copy(
                    hosts = records.hosts.filterNot { it.id == profile.id } +
                        profile.copy(credentialId = credentialId),
                    credentials = records.credentials.filterNot { it.id == credentialId } + credential,
                )
            } finally {
                password.fill(0)
            }
        }

        override suspend fun deleteHostAndUnreferencedPassword(hostId: String): Boolean {
            val host = records.hosts.firstOrNull { it.id == hostId } ?: return false
            val remainingHosts = records.hosts.filterNot { it.id == hostId }
            val credentialId = host.credentialId
            val referenced = remainingHosts.any { it.credentialId == credentialId }
            records = records.copy(
                hosts = remainingHosts,
                credentials = if (credentialId != null && !referenced) {
                    records.credentials.filterNot { it.id == credentialId }
                } else {
                    records.credentials
                },
            )
            deleteHostPostCommitAction?.let { action ->
                deleteHostPostCommitAction = null
                action()
            }
            return true
        }

        override suspend fun savePassword(
            hostId: String,
            expectedCredentialId: String?,
            credentialId: String,
            secretId: String,
            password: ByteArray,
        ) {
            passwordFailure?.let { throw it }
            val host = records.hosts.single { it.id == hostId }
            check(host.credentialId == expectedCredentialId)
            val credential = SshCredential(
                id = credentialId,
                displayName = host.displayName,
                authentication = SshAuthentication.Password(secretId),
                createdAtEpochMillis = 10,
                updatedAtEpochMillis = 20,
            )
            records = records.copy(
                hosts = records.hosts.map {
                    if (it.id == hostId) it.copy(credentialId = credentialId) else it
                },
                credentials = records.credentials.filterNot { it.id == credentialId } + credential,
            )
            lastExpectedCredentialAfterWrite = records.hosts.single { it.id == hostId }.credentialId
        }

        override suspend fun clearPassword(hostId: String): Boolean {
            val host = records.hosts.firstOrNull { it.id == hostId } ?: return false
            records = records.copy(
                hosts = records.hosts.map {
                    if (it.id == hostId) it.copy(credentialId = null) else it
                },
                credentials = records.credentials.filterNot { it.id == host.credentialId },
            )
            return true
        }

        override suspend fun importIdentity(identity: SshKeyIdentity, privateKey: ByteArray) {
            identityImportCount += 1
            records = records.copy(
                identities = records.identities.filterNot { it.id == identity.id } + identity,
                unavailableSecretIds = records.unavailableSecretIds -
                    identity.privateKeySecretReferenceId,
            )
        }

        override suspend fun updateIdentityMetadata(identity: SshKeyIdentity): Boolean {
            if (records.identities.none { it.id == identity.id }) return false
            records = records.copy(
                identities = records.identities.map { existing ->
                    if (existing.id == identity.id) identity else existing
                },
            )
            return true
        }

        override suspend fun deleteIdentity(identityId: String): Boolean {
            val updated = records.identities.filterNot { it.id == identityId }
            if (updated.size == records.identities.size) return false
            records = records.copy(identities = updated)
            return true
        }

        override suspend fun upsertSnippet(snippet: Snippet) {
            records = records.copy(
                snippets = records.snippets.filterNot { it.id == snippet.id } + snippet,
            )
        }

        override suspend fun deleteSnippet(snippetId: String): Boolean {
            val updated = records.snippets.filterNot { it.id == snippetId }
            if (updated.size == records.snippets.size) return false
            records = records.copy(snippets = updated)
            return true
        }

        override suspend fun replaceDefaultKeyboardActions(actions: List<KeyboardAction>) {
            records = records.copy(
                defaultKeyboardProfile = records.defaultKeyboardProfile.copy(orderedActions = actions),
            )
        }

        override suspend fun copyPassword(hostId: String): ByteArray {
            lastPasswordReadHostId = hostId
            return PASSWORD.copyOf()
        }

        override suspend fun copyPrivateKey(identityId: String): ByteArray {
            lastPrivateKeyReadIdentityId = identityId
            return PRIVATE_KEY.copyOf()
        }

        override suspend fun copyCredentialSecret(credentialId: String): ByteArray =
            PASSWORD.copyOf()

        override suspend fun previewClearAllSavedCredentials() = SavedCredentialClearPreview(
            credentialCount = records.credentials.size,
            keyIdentityCount = records.identities.size,
        )

        override suspend fun clearAllSavedCredentials(): ClearAllSavedCredentialsResult {
            val credentialCount = records.credentials.size
            val identityCount = records.identities.size
            val detached = records.hosts.count { it.credentialId != null }
            val secretCount = buildSet {
                records.credentials.forEach { credential ->
                    when (val authentication = credential.authentication) {
                        is SshAuthentication.Password -> authentication.secretReferenceId?.let(::add)
                        is SshAuthentication.PrivateKey ->
                            authentication.passphraseSecretReferenceId?.let(::add)
                        is SshAuthentication.KeyboardInteractive ->
                            authentication.reusableResponseSecretReferenceId?.let(::add)
                    }
                }
                records.identities.mapTo(this) { it.privateKeySecretReferenceId }
            }.size
            records = records.copy(
                hosts = records.hosts.map { it.copy(credentialId = null) },
                credentials = emptyList(),
                identities = emptyList(),
                unavailableSecretIds = emptySet(),
            )
            return ClearAllSavedCredentialsResult(
                hostReferencesDetached = detached,
                credentialMetadataDeleted = credentialCount,
                keyIdentitiesDeleted = identityCount,
                secretEnvelopesDeleted = secretCount,
            )
        }

        companion object {
            val PASSWORD = byteArrayOf(7, 8, 9)
            val PRIVATE_KEY = byteArrayOf(10, 11, 12)
        }
    }

    private class ExpectedFailure : RuntimeException()

    companion object {
        const val HOST_ID = "10000000-0000-4000-8000-000000000001"
        const val CREDENTIAL_ID = "10000000-0000-4000-8000-000000000002"
        const val SECRET_ID = "10000000-0000-4000-8000-000000000003"
        const val IDENTITY_ID = "10000000-0000-4000-8000-000000000004"
        const val PRIVATE_SECRET_ID = "10000000-0000-4000-8000-000000000005"
        const val SNIPPET_ID = "10000000-0000-4000-8000-000000000006"
        const val TERMINAL_PROFILE_ID = "10000000-0000-4000-8000-000000000007"
        const val KEYBOARD_PROFILE_ID = "10000000-0000-4000-8000-000000000008"
        const val AVAILABLE_SECRET_ID = "10000000-0000-4000-8000-000000000009"
        const val FINGERPRINT = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5"
    }
}

private fun catalogRecords(): TerminalDataRecords {
    val terminalProfile = TerminalProfile(
        id = TerminalDataRepositoryTest.TERMINAL_PROFILE_ID,
        name = "Nord development",
        themeId = "nord",
        fontId = "jetbrains_mono",
        fontSizeSp = 14.5f,
        lineHeightMultiplier = 1.25f,
        letterSpacingEm = 0.05f,
        cursorStyle = CursorStyle.BEAM,
        cursorBlinkEnabled = false,
        scrollbackLines = 3_210,
        bell = BellSettings(
            visualBellEnabled = true,
            vibrationBellEnabled = true,
            audibleBellEnabled = false,
        ),
        scroll = ScrollBehavior(
            touchMode = TouchScrollMode.REMOTE_MOUSE,
            twoFingerLocalScrollOverride = false,
            jumpToBottomOnKeyboardInput = false,
            keepViewportPositionOnOutput = false,
        ),
        links = LinkBehavior(
            detectPlainTextUrls = false,
            osc8HyperlinksEnabled = false,
            remoteClipboardMode = RemoteClipboardMode.DISABLED,
        ),
        termValue = "xterm-direct",
        preserveAlternateScreenHistory = false,
        createdAtEpochMillis = 11,
        updatedAtEpochMillis = 29,
    )
    val keyboardProfile = KeyboardProfile(
        id = TerminalDataRepositoryTest.KEYBOARD_PROFILE_ID,
        name = "Tmux text",
        orderedActions = listOf(
            KeyboardAction.TMUX_PREFIX,
            KeyboardAction.PASTE,
            KeyboardAction.ESCAPE,
        ),
        layout = KeyboardLayout.TWO_ROWS,
        modifierBehavior = ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK,
        hapticFeedbackEnabled = true,
        keyRepeatEnabled = false,
        inputMode = TerminalInputMode.TEXT,
        tmuxPrefix = "C-a",
        createdAtEpochMillis = 11,
        updatedAtEpochMillis = 29,
    )
    val identity = SshKeyIdentity(
        id = TerminalDataRepositoryTest.IDENTITY_ID,
        name = "Generated workstation key",
        algorithm = "ssh-ed25519",
        publicKeyFingerprint = TerminalDataRepositoryTest.FINGERPRINT,
        publicKey = TerminalDataRepositoryTest.PUBLIC_KEY,
        privateKeySecretReferenceId = TerminalDataRepositoryTest.PRIVATE_SECRET_ID,
        origin = SshKeyOrigin.GENERATED,
        isPassphraseProtected = true,
        createdAtEpochMillis = 11,
        updatedAtEpochMillis = 29,
        comment = "Created on this device",
    )
    val credential = SshCredential(
        id = TerminalDataRepositoryTest.CREDENTIAL_ID,
        displayName = "Generated key login",
        authentication = SshAuthentication.PrivateKey(
            keyIdentityId = identity.id,
            passphraseSecretReferenceId = TerminalDataRepositoryTest.SECRET_ID,
        ),
        createdAtEpochMillis = 11,
        updatedAtEpochMillis = 29,
    )
    val host = HostProfile(
        id = TerminalDataRepositoryTest.HOST_ID,
        displayName = "Production shell",
        hostname = "terminal.example",
        port = 2222,
        username = "operator",
        protocol = ConnectionProtocol.MOSH,
        credentialId = credential.id,
        terminalProfileId = terminalProfile.id,
        keyboardProfileId = keyboardProfile.id,
        isFavorite = true,
        group = "Production",
        tag = "eu-west",
        startupCommand = "tmux new-session -A -s ops",
        keepaliveIntervalSeconds = 45,
        reconnectPolicy = ReconnectPolicy.AUTOMATIC,
        moshPortRange = MoshPortRange(first = 60_001, last = 60_010),
        moshServerCommand = "mosh-server new -s",
        createdAtEpochMillis = 11,
        updatedAtEpochMillis = 29,
    )
    val snippet = Snippet(
        id = TerminalDataRepositoryTest.SNIPPET_ID,
        name = "Inspect services",
        group = "Operations",
        command = "systemctl --failed\njournalctl -p err -n 20",
        tapAction = SnippetTapAction.INSERT,
        appendEnter = true,
        confirmMultilineExecution = false,
        isFavorite = true,
        createdAtEpochMillis = 11,
        updatedAtEpochMillis = 29,
    )
    return TerminalDataRecords(
        hosts = listOf(host),
        credentials = listOf(credential),
        identities = listOf(identity),
        snippets = listOf(snippet),
        defaultTerminalProfileId = terminalProfile.id,
        defaultKeyboardProfile = keyboardProfile,
        terminalProfiles = listOf(terminalProfile),
        keyboardProfiles = listOf(keyboardProfile),
        unavailableSecretIds = setOf(
            TerminalDataRepositoryTest.SECRET_ID,
            TerminalDataRepositoryTest.PRIVATE_SECRET_ID,
        ),
        migrationWarningCodes = listOf("credential_recovery_required"),
    )
}

private fun populatedRecords(withPassword: Boolean = true): TerminalDataRecords {
    val credential = SshCredential(
        id = TerminalDataRepositoryTest.CREDENTIAL_ID,
        displayName = "Server",
        authentication = SshAuthentication.Password(TerminalDataRepositoryTest.SECRET_ID),
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    )
    return emptyRecords().copy(
        hosts = listOf(
            HostProfile(
                id = TerminalDataRepositoryTest.HOST_ID,
                displayName = "Server",
                hostname = "server.example",
                port = 22,
                username = "alice",
                protocol = ConnectionProtocol.SSH,
                credentialId = credential.id.takeIf { withPassword },
                terminalProfileId = TerminalDataRepositoryTest.TERMINAL_PROFILE_ID,
                keyboardProfileId = TerminalDataRepositoryTest.KEYBOARD_PROFILE_ID,
                createdAtEpochMillis = 10,
                updatedAtEpochMillis = 20,
            ),
        ),
        credentials = listOf(credential).takeIf { withPassword }.orEmpty(),
        identities = listOf(
            SshKeyIdentity(
                id = TerminalDataRepositoryTest.IDENTITY_ID,
                name = "Key",
                algorithm = "ssh-ed25519",
                publicKeyFingerprint = TerminalDataRepositoryTest.FINGERPRINT,
                publicKey = null,
                privateKeySecretReferenceId = TerminalDataRepositoryTest.PRIVATE_SECRET_ID,
                origin = SshKeyOrigin.IMPORTED,
                isPassphraseProtected = false,
                createdAtEpochMillis = 10,
                updatedAtEpochMillis = 20,
            ),
        ),
        snippets = listOf(
            Snippet(
                id = TerminalDataRepositoryTest.SNIPPET_ID,
                name = "List",
                command = "ls",
                tapAction = SnippetTapAction.SEND_IMMEDIATELY,
                appendEnter = true,
                createdAtEpochMillis = 10,
                updatedAtEpochMillis = 20,
            ),
        ),
    )
}

private fun emptyRecords(): TerminalDataRecords = TerminalDataRecords(
    hosts = emptyList(),
    credentials = emptyList(),
    identities = emptyList(),
    snippets = emptyList(),
    defaultTerminalProfileId = TerminalDataRepositoryTest.TERMINAL_PROFILE_ID,
    defaultKeyboardProfile = KeyboardProfile(
        id = TerminalDataRepositoryTest.KEYBOARD_PROFILE_ID,
        name = "Default",
        orderedActions = listOf(KeyboardAction.ESCAPE, KeyboardAction.CONTROL),
        layout = KeyboardLayout.ONE_ROW,
        modifierBehavior = ModifierBehavior.ONE_SHOT,
        hapticFeedbackEnabled = false,
        keyRepeatEnabled = true,
        inputMode = TerminalInputMode.RAW,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    ),
)
