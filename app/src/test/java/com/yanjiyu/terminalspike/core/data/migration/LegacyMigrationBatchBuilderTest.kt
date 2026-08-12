package com.yanjiyu.terminalspike.core.data.migration

import com.yanjiyu.terminalspike.core.data.db.EncryptedSecretEntity
import com.yanjiyu.terminalspike.core.data.credential.toStoredEncryptedCredentialRecord
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.EncryptedCredentialRecord
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.settings.SavedSshIdentity
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMigrationBatchBuilderTest {
    @Test
    fun sourceWithoutDataTerminalsHaveCanonicalShapeAndSettingsDefaults() {
        val absentSettings = LegacyMigrationBatchBuilder.buildDefaultUserSettingsTerminal(
            stateCode = LegacyMigrationBatchBuilder.STATE_ABSENT,
            migratedAtEpochMillis = TIMESTAMP,
        )
        val discardedKnownHosts = LegacyMigrationBatchBuilder.buildEmptyKnownHostsTerminal(
            stateCode = LegacyMigrationBatchBuilder.STATE_DISCARDED_AFTER_RECOVERY,
            migratedAtEpochMillis = TIMESTAMP,
        )

        assertEquals(LegacyIds.defaultTerminalProfile, absentSettings.terminalProfiles.single().id)
        assertEquals(LegacyIds.defaultKeyboardProfile, absentSettings.keyboardProfiles.single().id)
        assertEquals(2, absentSettings.keyboardProfiles.single().rowCount)
        assertEquals(18, absentSettings.keyboardKeys.size)
        assertEquals(LegacyMigrationBatchBuilder.STATE_ABSENT, absentSettings.completion.stateCode)
        assertNull(absentSettings.completion.sourceDigestSha256)
        assertNull(absentSettings.completion.sourceVersion)
        assertTrue(absentSettings.completion.isAuthoritativeTerminal())
        assertTrue(discardedKnownHosts.knownHosts.isEmpty())
        assertEquals(
            LegacyMigrationBatchBuilder.STATE_DISCARDED_AFTER_RECOVERY,
            discardedKnownHosts.completion.stateCode,
        )
        assertTrue(discardedKnownHosts.completion.isAuthoritativeTerminal())
    }

    @Test
    fun sourceWithoutDataTerminalBuilderRejectsAnImportCompletionState() {
        assertThrows(IllegalArgumentException::class.java) {
            LegacyMigrationBatchBuilder.buildEmptyKnownHostsTerminal(
                stateCode = LegacyMigrationBatchBuilder.STATE_COMPLETE,
                migratedAtEpochMillis = TIMESTAMP,
            )
        }
    }

    @Test
    fun versionOneSeedsDefaultsAndMapsHostsAndSnippetsWithoutCredentials() {
        val settings = UserSettings(
            profiles = listOf(profile(id = 8, savedPassword = false)),
            snippets = listOf(
                CommandSnippet(
                    id = 4,
                    label = "Deploy",
                    command = "printf 'one\\n'\nprintf 'two\\n'",
                    appendEnter = true,
                ),
            ),
            extraKeys = listOf(TerminalExtraKey.ESC),
        )

        val batch = build(version = 1, settings = settings)

        assertEquals(listOf(LegacyIds.defaultTerminalProfile), batch.terminalProfiles.map { it.id })
        assertEquals(listOf(LegacyIds.defaultKeyboardProfile), batch.keyboardProfiles.map { it.id })
        assertEquals(1, batch.keyboardProfiles.single().rowCount)
        assertEquals(LegacyIds.defaultTerminalProfile, batch.hosts.single().terminalProfileId)
        assertEquals(LegacyIds.defaultKeyboardProfile, batch.hosts.single().keyboardProfileId)
        assertNull(batch.hosts.single().credentialId)
        assertTrue(batch.credentials.isEmpty())
        assertTrue(batch.secrets.isEmpty())
        assertTrue(batch.keyIdentities.isEmpty())
        assertEquals(SnippetTapAction.SEND_IMMEDIATELY.wireCode, batch.snippets.single().actionCode)
        assertTrue(batch.snippets.single().appendEnter)
        assertTrue(batch.snippets.single().confirmMultiline)
        assertEquals(1, batch.completion.sourceVersion)
        assertEquals(LegacyMigrationBatchBuilder.STATE_COMPLETE, batch.completion.stateCode)
        assertEquals("", batch.completion.warningCodes)
    }

    @Test
    fun versionTwoMapsIdentityMetadataAndOnlyAcceptsSuppliedReadyCiphertext() {
        val identity = identity(id = 19)
        val inputRecord = readyRecord(
            id = LegacyIds.privateKeySecret(identity.id),
            kind = CredentialSecretKind.PRIVATE_KEY,
            seed = 19,
        )
        val batch = build(
            version = 2,
            settings = UserSettings(
                profiles = listOf(profile(id = 2, savedPassword = false)),
                identities = listOf(identity),
                extraKeys = listOf(TerminalExtraKey.TAB),
            ),
            secretInputs = LegacySecretMigrationInputs(
                privateKeys = listOf(
                    LegacyPrivateKeySecretMigration(
                        legacyIdentityId = identity.id,
                        result = LegacySecretMigrationResult.Ready(inputRecord),
                    ),
                ),
            ),
        )

        val mappedIdentity = batch.keyIdentities.single()
        val mappedSecret = batch.secrets.single()
        assertEquals(LegacyIds.keyIdentity(identity.id), mappedIdentity.id)
        assertEquals(LegacyIds.privateKeySecret(identity.id), mappedIdentity.privateSecretId)
        assertNull(mappedIdentity.publicKey)
        assertTrue(mappedIdentity.isPassphraseProtected)
        assertEquals(LegacyMigrationBatchBuilder.SECRET_STATE_READY, mappedSecret.stateCode)
        assertEquals(CredentialSecretKind.PRIVATE_KEY.wireCode, mappedSecret.kindCode)
        assertNull(mappedSecret.legacyId)
        assertArrayEquals(inputRecord.copyNonce(), mappedSecret.nonce)
        assertArrayEquals(inputRecord.copyCiphertext(), mappedSecret.ciphertext)
        val reopenedRecord = mappedSecret.toStoredEncryptedCredentialRecord().encrypted
        assertArrayEquals(inputRecord.copyNonce(), reopenedRecord.copyNonce())
        assertArrayEquals(inputRecord.copyCiphertext(), reopenedRecord.copyCiphertext())
        assertEquals(2, batch.completion.sourceVersion)
        assertEquals(LegacyMigrationBatchBuilder.STATE_COMPLETE, batch.completion.stateCode)
    }

    @Test
    fun versionThreeRetainsUnavailablePasswordReferenceAndWarning() {
        val profile = profile(id = 31, savedPassword = true)
        val batch = build(
            version = 3,
            settings = UserSettings(
                profiles = listOf(profile),
                extraKeys = listOf(TerminalExtraKey.ENTER),
            ),
            secretInputs = LegacySecretMigrationInputs(
                passwords = listOf(
                    LegacyPasswordSecretMigration(
                        legacyProfileId = profile.id,
                        result = LegacySecretMigrationResult.Unavailable(
                            LegacySecretUnavailableReason.KEY_UNAVAILABLE,
                        ),
                    ),
                ),
            ),
        )

        val secret = batch.secrets.single()
        val credential = batch.credentials.single()
        val host = batch.hosts.single()
        assertEquals(LegacyIds.passwordSecret(profile.id), secret.id)
        assertEquals(LegacyMigrationBatchBuilder.SECRET_STATE_LEGACY_UNAVAILABLE, secret.stateCode)
        assertEquals("key_unavailable", secret.failureCode)
        assertNull(secret.nonce)
        assertNull(secret.ciphertext)
        assertEquals(secret.id, credential.secretId)
        assertEquals(credential.id, host.credentialId)
        assertEquals(LegacyMigrationBatchBuilder.STATE_COMPLETE_WITH_WARNINGS, batch.completion.stateCode)
        assertEquals(
            "password_secret_unavailable:31:key_unavailable",
            batch.completion.warningCodes,
        )
    }

    @Test
    fun sameNumericIdUsesSeparateIdsForEveryLegacyType() {
        val legacyId = 7L
        val batch = build(
            version = 3,
            settings = UserSettings(
                profiles = listOf(profile(id = legacyId, savedPassword = true)),
                snippets = listOf(
                    CommandSnippet(legacyId, "Same ID", "date", appendEnter = false),
                ),
                extraKeys = listOf(TerminalExtraKey.ESC),
                identities = listOf(identity(id = legacyId)),
            ),
            secretInputs = LegacySecretMigrationInputs(
                passwords = listOf(
                    LegacyPasswordSecretMigration(
                        legacyId,
                        LegacySecretMigrationResult.Ready(
                            readyRecord(
                                LegacyIds.passwordSecret(legacyId),
                                CredentialSecretKind.PASSWORD,
                                seed = 1,
                            ),
                        ),
                    ),
                ),
                privateKeys = listOf(
                    LegacyPrivateKeySecretMigration(
                        legacyId,
                        LegacySecretMigrationResult.Ready(
                            readyRecord(
                                LegacyIds.privateKeySecret(legacyId),
                                CredentialSecretKind.PRIVATE_KEY,
                                seed = 2,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val ids = listOf(
            batch.hosts.single().id,
            batch.snippets.single().id,
            batch.keyIdentities.single().id,
            batch.credentials.single().id,
            batch.secrets[0].id,
            batch.secrets[1].id,
        )
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun extraKeyMappingPreservesOrderUsingDomainWireCodes() {
        val keys = listOf(
            TerminalExtraKey.DOLLAR,
            TerminalExtraKey.ESC,
            TerminalExtraKey.CTRL_B,
            TerminalExtraKey.UP,
            TerminalExtraKey.DASH,
            TerminalExtraKey.CTRL_Z,
        )

        val batch = build(
            version = 1,
            settings = UserSettings(extraKeys = keys),
        )

        assertEquals(keys.indices.toList(), batch.keyboardKeys.map { it.position })
        assertEquals(
            listOf(
                "dollar",
                "escape",
                "ctrl_b",
                "arrow_up",
                "hyphen",
                "ctrl_z",
            ),
            batch.keyboardKeys.map { it.actionCode },
        )
    }

    @Test
    fun everyLegacyExtraKeyMapsToAUniqueDecodableDomainAction() {
        val batch = build(
            version = 1,
            settings = UserSettings(extraKeys = TerminalExtraKey.entries),
        )

        assertEquals(TerminalExtraKey.entries.indices.toList(), batch.keyboardKeys.map { it.position })
        assertEquals(batch.keyboardKeys.size, batch.keyboardKeys.map { it.actionCode }.distinct().size)
        batch.keyboardKeys.forEach { key -> KeyboardAction.fromWireCode(key.actionCode) }
    }

    @Test
    fun exactHistoricalDefaultsUpgradeButCustomizedLegacyDecksKeepOneRow() {
        listOf(
            TerminalExtraKey.LEGACY_DEFAULT_ORDER,
            TerminalExtraKey.PAGED_DEFAULT_ORDER,
            TerminalExtraKey.DEFAULT_ORDER,
        ).forEach { shippedOrder ->
            val shipped = build(
                version = 3,
                settings = UserSettings(extraKeys = shippedOrder),
            )

            assertEquals(2, shipped.keyboardProfiles.single().rowCount)
            assertEquals(18, shipped.keyboardKeys.size)
            assertEquals(
                TerminalExtraKey.DEFAULT_ORDER.size,
                shipped.keyboardKeys.map { it.actionCode }.distinct().size,
            )
        }

        val customized = build(
            version = 3,
            settings = UserSettings(extraKeys = TerminalExtraKey.DEFAULT_ORDER.reversed()),
        )
        val currentDefaultCodes = build(
            version = 3,
            settings = UserSettings(extraKeys = TerminalExtraKey.DEFAULT_ORDER),
        ).keyboardKeys.map { it.actionCode }

        assertEquals(1, customized.keyboardProfiles.single().rowCount)
        assertEquals(
            currentDefaultCodes.reversed(),
            customized.keyboardKeys.map { it.actionCode },
        )
    }

    @Test
    fun knownHostsBuildIndependentlyWithUnknownLegacyTimestampsAndStableWarnings() {
        val publicKey = byteArrayOf(9, 8, 7)
        val parsed = LegacyKnownHostsParser.parse(
            """
                Z.example ssh-ed25519 ${publicKey.base64()}
                malformed
                [bad.example]:70000 ssh-ed25519 ${publicKey.base64()}
            """.trimIndent(),
        )

        val batch = LegacyMigrationBatchBuilder.buildKnownHosts(
            sourceDigestSha256 = KNOWN_HOSTS_DIGEST,
            parsed = parsed,
            migratedAtEpochMillis = TIMESTAMP,
        )

        assertTrue(batch.terminalProfiles.isEmpty())
        assertTrue(batch.hosts.isEmpty())
        assertEquals("z.example", batch.knownHosts.single().host)
        assertNull(batch.knownHosts.single().firstSeenAtEpochMillis)
        assertNull(batch.knownHosts.single().lastSeenAtEpochMillis)
        assertArrayEquals(publicKey, batch.knownHosts.single().publicKey)
        assertEquals(LegacyMigrationBatchBuilder.KNOWN_HOSTS_SOURCE, batch.completion.sourceCode)
        assertEquals(KNOWN_HOSTS_DIGEST, batch.completion.sourceDigestSha256)
        assertNull(batch.completion.sourceVersion)
        assertEquals(LegacyMigrationBatchBuilder.STATE_COMPLETE_WITH_WARNINGS, batch.completion.stateCode)
        assertEquals(
            "known_host_invalid_port:line_3;known_host_malformed_line:line_2",
            batch.completion.warningCodes,
        )
    }

    @Test
    fun legacyValuesThatCannotCrossTargetMappersRejectTheWholeSettingsAggregate() {
        val invalidHost = build(
            version = 1,
            settings = UserSettings(
                profiles = listOf(profile(1, false).copy(host = "bad_.example")),
                extraKeys = listOf(TerminalExtraKey.ESC),
            ),
        )
        val blankSnippet = build(
            version = 1,
            settings = UserSettings(
                snippets = listOf(CommandSnippet(2, "Blank", "   ", false)),
                extraKeys = listOf(TerminalExtraKey.ESC),
            ),
        )
        val invalidIdentityAlgorithm = build(
            version = 2,
            settings = UserSettings(
                identities = listOf(identity(3).copy(keyType = "ssh/ed25519")),
                extraKeys = listOf(TerminalExtraKey.ESC),
            ),
            secretInputs = LegacySecretMigrationInputs(
                privateKeys = listOf(
                    LegacyPrivateKeySecretMigration(
                        legacyIdentityId = 3,
                        result = LegacySecretMigrationResult.Unavailable(
                            LegacySecretUnavailableReason.MISSING,
                        ),
                    ),
                ),
            ),
        )

        listOf(invalidHost, blankSnippet, invalidIdentityAlgorithm).forEach { batch ->
            assertThrows(IllegalArgumentException::class.java) {
                batch.requireUserSettingsTargetCompatibility()
            }
        }
    }

    @Test
    fun rowOrderAndContentAreDeterministicAcrossSourceAndInputOrdering() {
        val firstSettings = deterministicSettings(reverse = false)
        val secondSettings = deterministicSettings(reverse = true)
        val firstInputs = deterministicSecretInputs(reverse = false)
        val secondInputs = deterministicSecretInputs(reverse = true)

        val first = build(version = 3, settings = firstSettings, secretInputs = firstInputs)
        val second = build(version = 3, settings = secondSettings, secretInputs = secondInputs)

        assertEquals(first.copy(secrets = emptyList()), second.copy(secrets = emptyList()))
        assertEquals(first.secrets.map { it.stableProjection() }, second.secrets.map { it.stableProjection() })
        first.secrets.zip(second.secrets).forEach { (left, right) ->
            assertArrayEquals(left.nonce, right.nonce)
            assertArrayEquals(left.ciphertext, right.ciphertext)
        }
        assertEquals(
            listOf(LegacyIds.hostProfile(10), LegacyIds.hostProfile(30)),
            first.hosts.map { it.id },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingSecretInputCannotInventAReadyOrUnavailableRow() {
        build(
            version = 3,
            settings = UserSettings(
                profiles = listOf(profile(id = 4, savedPassword = true)),
                extraKeys = listOf(TerminalExtraKey.ESC),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun readyCiphertextMustUseDeterministicOwnerIdAndKind() {
        build(
            version = 3,
            settings = UserSettings(
                profiles = listOf(profile(id = 4, savedPassword = true)),
                extraKeys = listOf(TerminalExtraKey.ESC),
            ),
            secretInputs = LegacySecretMigrationInputs(
                passwords = listOf(
                    LegacyPasswordSecretMigration(
                        legacyProfileId = 4,
                        result = LegacySecretMigrationResult.Ready(
                            readyRecord(
                                id = LegacyIds.privateKeySecret(4),
                                kind = CredentialSecretKind.PRIVATE_KEY,
                                seed = 4,
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun build(
        version: Int,
        settings: UserSettings,
        secretInputs: LegacySecretMigrationInputs = LegacySecretMigrationInputs(),
    ) = LegacyMigrationBatchBuilder.buildUserSettings(
        source = LegacyUserSettingsReadResult.Loaded(
            settings = settings,
            sourceVersion = version,
            sourceDigestSha256 = SETTINGS_DIGEST,
        ),
        secretInputs = secretInputs,
        migratedAtEpochMillis = TIMESTAMP,
    )

    private fun profile(id: Long, savedPassword: Boolean) = SavedSshProfile(
        id = id,
        label = "Host $id",
        host = "host$id.example",
        port = 22,
        username = "user$id",
        hasSavedPassword = savedPassword,
    )

    private fun identity(id: Long) = SavedSshIdentity(
        id = id,
        label = "Key $id",
        keyType = "ssh-ed25519",
        fingerprint = "identity-$id".toByteArray().sha256Fingerprint(),
        passphraseRequired = true,
    )

    private fun ByteArray.sha256Fingerprint(): String = "SHA256:" +
        Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(this),
        )

    private fun readyRecord(
        id: String,
        kind: CredentialSecretKind,
        seed: Int,
    ) = EncryptedCredentialRecord(
        secretId = id,
        kindCode = kind.wireCode,
        envelopeVersion = 2,
        keyVersion = 2,
        nonce = ByteArray(12) { (seed + it).toByte() },
        ciphertext = ByteArray(24) { (seed * 2 + it).toByte() },
    )

    private fun deterministicSettings(reverse: Boolean): UserSettings {
        val profiles = listOf(profile(30, true), profile(10, true))
        val snippets = listOf(
            CommandSnippet(9, "Nine", "nine", false),
            CommandSnippet(3, "Three", "three", true),
        )
        val identities = listOf(identity(20), identity(5))
        return UserSettings(
            profiles = if (reverse) profiles.reversed() else profiles,
            snippets = if (reverse) snippets.reversed() else snippets,
            extraKeys = listOf(TerminalExtraKey.ESC, TerminalExtraKey.DOLLAR),
            identities = if (reverse) identities.reversed() else identities,
        )
    }

    private fun deterministicSecretInputs(reverse: Boolean): LegacySecretMigrationInputs {
        val passwords = listOf(30L, 10L).map { id ->
            LegacyPasswordSecretMigration(
                id,
                LegacySecretMigrationResult.Ready(
                    readyRecord(LegacyIds.passwordSecret(id), CredentialSecretKind.PASSWORD, id.toInt()),
                ),
            )
        }
        val identities = listOf(20L, 5L).map { id ->
            LegacyPrivateKeySecretMigration(
                id,
                LegacySecretMigrationResult.Ready(
                    readyRecord(LegacyIds.privateKeySecret(id), CredentialSecretKind.PRIVATE_KEY, id.toInt()),
                ),
            )
        }
        return LegacySecretMigrationInputs(
            passwords = if (reverse) passwords.reversed() else passwords,
            privateKeys = if (reverse) identities.reversed() else identities,
        )
    }

    private fun EncryptedSecretEntity.stableProjection(): List<Any?> = listOf(
        id,
        kindCode,
        envelopeVersion,
        keyVersion,
        stateCode,
        failureCode,
        legacyId,
        createdAtEpochMillis,
        updatedAtEpochMillis,
    )

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

    private companion object {
        const val TIMESTAMP = 1_725_000_000_000L
        val SETTINGS_DIGEST = "ab".repeat(32)
        val KNOWN_HOSTS_DIGEST = "cd".repeat(32)
    }
}
