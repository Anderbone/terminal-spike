package com.yanjiyu.terminalspike.core.backup

import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.KnownHost
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupImportPlannerTest {
    @Test
    fun keepBothRewritesCompleteHostCredentialProfileAndSecretGraph() {
        val current = snapshot(BackupMode.STANDARD, includeHost = false, portableSecret = null)
        val incomingSecret = PortableBackupSecret.copyAndWipe("incoming password".toByteArray())
        val incoming = snapshot(BackupMode.FULL, includeHost = true, portableSecret = incomingSecret)
        val ids = deterministicIds()

        val plan = BackupImportPlanner(BackupUuidGenerator { ids.removeFirst() }).plan(
            current,
            incoming,
            BackupImportStrategy.KEEP_BOTH,
        )
        try {
            val credential = plan.snapshotToApply.credentials.single().metadata
            val host = plan.snapshotToApply.hostProfiles.single()
            val terminal = plan.snapshotToApply.terminalProfiles.single()
            val keyboard = plan.snapshotToApply.keyboardProfiles.single()
            val savedSecret = (credential.authentication as SshAuthentication.Password).secretReferenceId

            assertNotEquals(CREDENTIAL_ID, credential.id)
            assertEquals(credential.id, host.credentialId)
            assertEquals(terminal.id, host.terminalProfileId)
            assertEquals(keyboard.id, host.keyboardProfileId)
            assertEquals(terminal.id, plan.snapshotToApply.globalSettings.defaultTerminalProfileId)
            assertEquals(keyboard.id, plan.snapshotToApply.globalSettings.defaultKeyboardProfileId)
            assertNotEquals(PASSWORD_SECRET_ID, savedSecret)
            assertEquals(plan.secretIdRewrites[PASSWORD_SECRET_ID], savedSecret)
            assertTrue(plan.recordIdRewrites.keys.containsAll(listOf(CREDENTIAL_ID, TERMINAL_ID, KEYBOARD_ID)))
            assertFalse(plan.applyGlobalSettings)
            assertFalse(plan.requiresRecoverySnapshot)
            assertTrue(plan.externallyResolvedReferences.isEmpty())
        } finally {
            plan.close()
            current.close()
        }
        assertTrue(incomingSecret.isWiped)
    }

    @Test
    fun mergePreservesExistingConflictsAndWipesTheirUnappliedSecret() {
        val current = snapshot(BackupMode.STANDARD, includeHost = false, portableSecret = null)
        val skippedSecret = PortableBackupSecret.copyAndWipe("do not retain".toByteArray())
        val incoming = snapshot(BackupMode.FULL, includeHost = true, portableSecret = skippedSecret)

        val plan = BackupImportPlanner().plan(current, incoming, BackupImportStrategy.MERGE)
        try {
            assertTrue(plan.snapshotToApply.credentials.isEmpty())
            assertTrue(plan.snapshotToApply.terminalProfiles.isEmpty())
            assertTrue(plan.snapshotToApply.keyboardProfiles.isEmpty())
            assertEquals(CREDENTIAL_ID, plan.snapshotToApply.hostProfiles.single().credentialId)
            assertTrue(
                plan.skippedIncomingRecordIds.containsAll(
                    listOf(CREDENTIAL_ID, TERMINAL_ID, KEYBOARD_ID),
                ),
            )
            assertEquals(5, plan.externallyResolvedReferences.size)
            assertFalse(plan.applyGlobalSettings)
            assertFalse(plan.requiresRecoverySnapshot)
            assertTrue(skippedSecret.isWiped)
        } finally {
            plan.close()
            current.close()
        }
    }

    @Test
    fun replaceKeepsIncomingIdentifiersAndRequiresRecoverySnapshot() {
        val current = snapshot(BackupMode.STANDARD, includeHost = false, portableSecret = null)
        val secret = PortableBackupSecret.copyAndWipe("replace password".toByteArray())
        val incoming = snapshot(BackupMode.FULL, includeHost = true, portableSecret = secret)

        val plan = BackupImportPlanner().plan(
            current,
            incoming,
            BackupImportStrategy.REPLACE_CORRESPONDING,
        )
        try {
            assertEquals(CREDENTIAL_ID, plan.snapshotToApply.credentials.single().metadata.id)
            assertEquals(TERMINAL_ID, plan.snapshotToApply.globalSettings.defaultTerminalProfileId)
            assertTrue(plan.applyGlobalSettings)
            assertTrue(plan.requiresRecoverySnapshot)
            assertTrue(plan.recordIdRewrites.isEmpty())
            assertFalse(secret.isWiped)
        } finally {
            plan.close()
            current.close()
        }
        assertTrue(secret.isWiped)
    }

    @Test
    fun invalidGeneratedIdentifiersFailBoundedlyAndWipeIncomingSecrets() {
        val current = snapshot(BackupMode.STANDARD, includeHost = false, portableSecret = null)
        val secret = PortableBackupSecret.copyAndWipe("must wipe".toByteArray())
        val incoming = snapshot(BackupMode.FULL, includeHost = true, portableSecret = secret)

        expectThrows<BackupImportPlanException.ExhaustedIdentifiers> {
            BackupImportPlanner(BackupUuidGenerator { "not-a-uuid" }).plan(
                current,
                incoming,
                BackupImportStrategy.KEEP_BOTH,
            )
        }

        assertTrue(secret.isWiped)
        current.close()
    }

    @Test
    fun naturalKnownHostConflictsNeverViolateTheRoomTrustKeyConstraint() {
        val currentKnownHost = knownHost(KNOWN_HOST_ID, "AQ==", "S/USLzRFVMU73i67jNK349FgCtYxw4Wl18ziPHeFRZo")
        val incomingKnownHost = knownHost(
            INCOMING_KNOWN_HOST_ID,
            "Ag==",
            "28G0yQD/5I1XW12lxjgEASX2XbD+PiRJS3bqmGRX2YY",
        )

        listOf(
            BackupImportStrategy.MERGE,
            BackupImportStrategy.KEEP_BOTH,
            BackupImportStrategy.REPLACE_CORRESPONDING,
        ).forEach { strategy ->
            val current = trustSnapshot(currentKnownHost)
            val incoming = trustSnapshot(incomingKnownHost)
            val plan = BackupImportPlanner().plan(current, incoming, strategy)
            try {
                val conflict = plan.conflicts.single { conflict ->
                    conflict.recordId == INCOMING_KNOWN_HOST_ID
                }
                assertEquals(KNOWN_HOST_ID, conflict.existingRecordId)
                when (strategy) {
                    BackupImportStrategy.MERGE,
                    BackupImportStrategy.KEEP_BOTH -> {
                        assertTrue(plan.snapshotToApply.knownHosts.isEmpty())
                        assertTrue(INCOMING_KNOWN_HOST_ID in plan.skippedIncomingRecordIds)
                        assertTrue(plan.knownHostRecordIdsToDelete.isEmpty())
                    }
                    BackupImportStrategy.REPLACE_CORRESPONDING -> {
                        assertEquals(incomingKnownHost, plan.snapshotToApply.knownHosts.single())
                        assertEquals(setOf(KNOWN_HOST_ID), plan.knownHostRecordIdsToDelete)
                    }
                }
            } finally {
                plan.close()
                current.close()
            }
        }
    }

    @Test
    fun snapshotRejectsDuplicateNaturalKnownHostKeys() {
        expectThrows<IllegalArgumentException> {
            BackupPayloadSnapshot(
                mode = BackupMode.STANDARD,
                knownHosts = listOf(
                    knownHost(KNOWN_HOST_ID, "AQ==", "S/USLzRFVMU73i67jNK349FgCtYxw4Wl18ziPHeFRZo"),
                    knownHost(
                        INCOMING_KNOWN_HOST_ID,
                        "Ag==",
                        "28G0yQD/5I1XW12lxjgEASX2XbD+PiRJS3bqmGRX2YY",
                    ),
                ),
            )
        }
    }

    @Test
    fun crossKindRecordCollisionIsRejectedForEveryStrategy() {
        BackupImportStrategy.entries.forEach { strategy ->
            val current = BackupPayloadSnapshot(
                mode = BackupMode.STANDARD,
                hostProfiles = listOf(host().copy(credentialId = null, terminalProfileId = null, keyboardProfileId = null)),
            )
            val incoming = BackupPayloadSnapshot(
                mode = BackupMode.STANDARD,
                snippets = listOf(
                    Snippet(
                        id = HOST_ID,
                        name = "Collision",
                        command = "pwd",
                        tapAction = SnippetTapAction.INSERT,
                        appendEnter = false,
                        confirmMultilineExecution = true,
                        isFavorite = false,
                        createdAtEpochMillis = 1,
                        updatedAtEpochMillis = 2,
                    ),
                ),
            )

            val error = expectThrows<BackupImportPlanException.IncompatibleRecordKind> {
                BackupImportPlanner().plan(current, incoming, strategy)
            }
            assertEquals(BackupImportRecordKind.HOST, error.existingKind)
            assertEquals(BackupImportRecordKind.SNIPPET, error.incomingKind)
            current.close()
        }
    }

    @Test
    fun replaceRejectsReusingASecretForAnotherPurposeEvenWithTheSameOwner() {
        val current = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            credentials = listOf(
                BackupCredentialRecord(
                    SshCredential(
                        id = CREDENTIAL_ID,
                        displayName = "Password",
                        authentication = SshAuthentication.Password(PASSWORD_SECRET_ID),
                        createdAtEpochMillis = 1,
                        updatedAtEpochMillis = 2,
                    ),
                ),
            ),
        )
        val incoming = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            credentials = listOf(
                BackupCredentialRecord(
                    SshCredential(
                        id = CREDENTIAL_ID,
                        displayName = "Interactive",
                        authentication = SshAuthentication.KeyboardInteractive(PASSWORD_SECRET_ID),
                        createdAtEpochMillis = 1,
                        updatedAtEpochMillis = 3,
                    ),
                ),
            ),
        )

        expectThrows<BackupImportPlanException.IncompatibleSecretOwner> {
            BackupImportPlanner().plan(current, incoming, BackupImportStrategy.REPLACE_CORRESPONDING)
        }
        current.close()
    }

    @Test
    fun replaceRejectsRebindingAnExistingSecretToAnotherRecord() {
        val current = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            credentials = listOf(
                BackupCredentialRecord(
                    SshCredential(
                        id = CREDENTIAL_ID,
                        displayName = "Existing",
                        authentication = SshAuthentication.Password(PASSWORD_SECRET_ID),
                        createdAtEpochMillis = 1,
                        updatedAtEpochMillis = 2,
                    ),
                ),
            ),
        )
        val incoming = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            credentials = listOf(
                BackupCredentialRecord(
                    SshCredential(
                        id = OTHER_CREDENTIAL_ID,
                        displayName = "Incoming",
                        authentication = SshAuthentication.Password(PASSWORD_SECRET_ID),
                        createdAtEpochMillis = 1,
                        updatedAtEpochMillis = 3,
                    ),
                ),
            ),
        )

        val error = expectThrows<BackupImportPlanException.IncompatibleSecretOwner> {
            BackupImportPlanner().plan(current, incoming, BackupImportStrategy.REPLACE_CORRESPONDING)
        }
        assertEquals(CREDENTIAL_ID, error.existingOwnerId)
        assertEquals(OTHER_CREDENTIAL_ID, error.incomingOwnerId)
        current.close()
    }

    @Test
    fun customThemeRoundTripsWithItsProfileHostAndDefault() {
        val customThemeId = "80000000-0000-4000-8000-000000000008"
        val customProfileId = "81000000-0000-4000-8000-000000000008"
        val customHostId = "82000000-0000-4000-8000-000000000008"
        val current = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            terminalProfiles = listOf(terminal()),
            globalSettings = BackupGlobalSettings(
                accentPreset = "mint",
                defaultTerminalProfileId = TERMINAL_ID,
            ),
        )
        val incoming = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            hostProfiles = listOf(
                host().copy(
                    id = customHostId,
                    credentialId = null,
                    terminalProfileId = customProfileId,
                    keyboardProfileId = null,
                ),
            ),
            terminalProfiles = listOf(terminal().copy(id = customProfileId, themeId = customThemeId)),
            terminalThemes = listOf(
                BackupTerminalTheme(
                    id = customThemeId,
                    name = "Portable custom",
                    foregroundArgb = -1,
                    backgroundArgb = -16_777_216,
                    cursorArgb = -1,
                    selectionArgb = -8_388_608,
                    ansi16Argb = List(16) { -16_777_216 + it },
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 2,
                ),
            ),
            globalSettings = BackupGlobalSettings(
                accentPreset = "violet",
                defaultTerminalProfileId = customProfileId,
            ),
        )

        val plan = BackupImportPlanner().plan(
            current,
            incoming,
            BackupImportStrategy.REPLACE_CORRESPONDING,
        )
        try {
            assertEquals(customThemeId, plan.snapshotToApply.terminalThemes.single().id)
            assertEquals(customProfileId, plan.snapshotToApply.terminalProfiles.single().id)
            assertEquals(customHostId, plan.snapshotToApply.hostProfiles.single().id)
            assertEquals(customProfileId, plan.snapshotToApply.globalSettings.defaultTerminalProfileId)
            assertTrue(plan.skippedIncomingRecordIds.isEmpty())
            assertTrue(plan.incompatibleIncomingRecords.isEmpty())
            assertTrue(plan.externallyResolvedReferences.isEmpty())
        } finally {
            plan.close()
            current.close()
        }
    }

    @Test
    fun externalArchivesFallbackCustomFontsWithoutDroppingProfileHostOrDefault() {
        BackupMode.entries.forEach { mode ->
            BackupImportStrategy.entries.forEach { strategy ->
                val customProfileId = "81000000-0000-4000-8000-000000000008"
                val customHostId = "82000000-0000-4000-8000-000000000008"
                val current = BackupPayloadSnapshot(BackupMode.STANDARD)
                val incoming = BackupPayloadSnapshot(
                    mode = mode,
                    hostProfiles = listOf(
                        host().copy(
                            id = customHostId,
                            credentialId = null,
                            terminalProfileId = customProfileId,
                            keyboardProfileId = null,
                        ),
                    ),
                    terminalProfiles = listOf(
                        terminal().copy(
                            id = customProfileId,
                            fontId = "custom_0123456789abcdef",
                        ),
                    ),
                    globalSettings = BackupGlobalSettings(defaultTerminalProfileId = customProfileId),
                )

                val plan = BackupImportPlanner().plan(current, incoming, strategy)
                try {
                    val restoredProfile = plan.snapshotToApply.terminalProfiles.single()
                    val restoredHost = plan.snapshotToApply.hostProfiles.single()
                    assertEquals(customProfileId, restoredProfile.id)
                    assertEquals(
                        TerminalRendererProfile.SYSTEM_MONOSPACE_FONT_ID,
                        restoredProfile.fontId,
                    )
                    assertEquals(customProfileId, restoredHost.terminalProfileId)
                    assertEquals(customProfileId, plan.snapshotToApply.globalSettings.defaultTerminalProfileId)
                    assertTrue(plan.skippedIncomingRecordIds.isEmpty())
                    assertTrue(plan.incompatibleIncomingRecords.isEmpty())
                    assertTrue(plan.externallyResolvedReferences.isEmpty())
                } finally {
                    plan.close()
                    current.close()
                }
            }
        }
    }

    @Test
    fun explicitlyIncludedCustomFontPreservesProfileReferenceAndFontRecord() {
        val bytes = ByteArray(1_024) { index -> (index * 17).toByte() }
        val fontId = "custom_" + MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        val profile = terminal().copy(fontId = fontId)
        val incoming = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            terminalProfiles = listOf(profile),
            customFonts = listOf(BackupCustomFont.copyOf(fontId, "Portable Mono", bytes)),
        )

        val plan = BackupImportPlanner().plan(
            current = BackupPayloadSnapshot(BackupMode.STANDARD),
            incoming = incoming,
            strategy = BackupImportStrategy.REPLACE_CORRESPONDING,
        )

        assertEquals(fontId, plan.snapshotToApply.terminalProfiles.single().fontId)
        assertEquals(fontId, plan.snapshotToApply.customFonts.single().fontId)
        plan.close()
    }

    @Test
    fun internalRecoveryPreservesCustomProfileReferencesAndDependentHosts() {
        val customThemeId = "80000000-0000-4000-8000-000000000008"
        val customProfileId = "81000000-0000-4000-8000-000000000008"
        val customHostId = "82000000-0000-4000-8000-000000000008"
        val current = BackupPayloadSnapshot(BackupMode.STANDARD)
        val recovery = BackupPayloadSnapshot(
            mode = BackupMode.FULL,
            hostProfiles = listOf(
                host().copy(
                    id = customHostId,
                    credentialId = null,
                    terminalProfileId = customProfileId,
                    keyboardProfileId = null,
                ),
            ),
            terminalProfiles = listOf(
                terminal().copy(
                    id = customProfileId,
                    themeId = customThemeId,
                    fontId = "custom_0123456789abcdef",
                ),
            ),
            terminalThemes = listOf(
                BackupTerminalTheme(
                    id = customThemeId,
                    name = "Same-device custom",
                    foregroundArgb = -1,
                    backgroundArgb = -16_777_216,
                    cursorArgb = -1,
                    selectionArgb = -8_388_608,
                    ansi16Argb = List(16) { -16_777_216 + it },
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 2,
                ),
            ),
            globalSettings = BackupGlobalSettings(defaultTerminalProfileId = customProfileId),
        )

        val plan = BackupImportPlanner().planInternalRecovery(current, recovery)
        try {
            val restoredProfile = plan.snapshotToApply.terminalProfiles.single()
            assertEquals(customProfileId, restoredProfile.id)
            assertEquals("custom_0123456789abcdef", restoredProfile.fontId)
            assertEquals(customHostId, plan.snapshotToApply.hostProfiles.single().id)
            assertEquals(customThemeId, plan.snapshotToApply.terminalThemes.single().id)
            assertEquals(customProfileId, plan.snapshotToApply.globalSettings.defaultTerminalProfileId)
            assertTrue(plan.incompatibleIncomingRecords.isEmpty())
            assertTrue(plan.skippedIncomingRecordIds.isEmpty())
        } finally {
            plan.close()
            current.close()
        }
    }

    private fun snapshot(
        mode: BackupMode,
        includeHost: Boolean,
        portableSecret: PortableBackupSecret?,
    ): BackupPayloadSnapshot {
        val credential = SshCredential(
            id = CREDENTIAL_ID,
            displayName = "Saved login",
            authentication = SshAuthentication.Password(PASSWORD_SECRET_ID),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )
        return BackupPayloadSnapshot(
            mode = mode,
            hostProfiles = if (includeHost) listOf(host()) else emptyList(),
            credentials = listOf(BackupCredentialRecord(credential, portableSecret)),
            terminalProfiles = listOf(terminal()),
            keyboardProfiles = listOf(keyboard()),
            globalSettings = BackupGlobalSettings(
                accentPreset = "mint",
                defaultTerminalProfileId = TERMINAL_ID,
                defaultKeyboardProfileId = KEYBOARD_ID,
                lastBackupMode = mode,
            ),
        )
    }

    private fun host() = HostProfile(
        id = HOST_ID,
        displayName = "Production",
        hostname = "server.example",
        port = 22,
        username = "operator",
        protocol = ConnectionProtocol.SSH,
        credentialId = CREDENTIAL_ID,
        terminalProfileId = TERMINAL_ID,
        keyboardProfileId = KEYBOARD_ID,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun terminal() = TerminalProfile(
        id = TERMINAL_ID,
        name = "Terminal",
        themeId = "midnight",
        fontId = "system_monospace",
        fontSizeSp = 14f,
        lineHeightMultiplier = 1f,
        letterSpacingEm = 0f,
        cursorStyle = CursorStyle.BLOCK,
        scrollbackLines = 10_000,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun keyboard() = KeyboardProfile(
        id = KEYBOARD_ID,
        name = "Keyboard",
        orderedActions = listOf(KeyboardAction.ESCAPE),
        layout = KeyboardLayout.ONE_ROW,
        modifierBehavior = ModifierBehavior.ONE_SHOT,
        hapticFeedbackEnabled = false,
        keyRepeatEnabled = true,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun knownHost(id: String, publicKey: String, fingerprintSuffix: String) = KnownHost(
        id = id,
        host = "server.example",
        port = 22,
        keyAlgorithm = "ssh-ed25519",
        fingerprint = "SHA256:$fingerprintSuffix",
        publicHostKey = publicKey,
        firstSeenAtEpochMillis = 1,
        lastSeenAtEpochMillis = 2,
    )

    private fun trustSnapshot(knownHost: KnownHost) = BackupPayloadSnapshot(
        mode = BackupMode.STANDARD,
        knownHosts = listOf(knownHost),
    )

    private fun deterministicIds(): ArrayDeque<String> = ArrayDeque(
        (10..30).map { number ->
            UUID.fromString("90000000-0000-4000-8000-${number.toString().padStart(12, '0')}").toString()
        },
    )

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.name}, got ${error::class.java.name}", error)
        }
        throw AssertionError("Expected ${T::class.java.name}.")
    }

    private companion object {
        const val CREDENTIAL_ID = "10000000-0000-4000-8000-000000000001"
        const val PASSWORD_SECRET_ID = "20000000-0000-4000-8000-000000000002"
        const val TERMINAL_ID = "30000000-0000-4000-8000-000000000003"
        const val KEYBOARD_ID = "40000000-0000-4000-8000-000000000004"
        const val HOST_ID = "50000000-0000-4000-8000-000000000005"
        const val KNOWN_HOST_ID = "60000000-0000-4000-8000-000000000006"
        const val INCOMING_KNOWN_HOST_ID = "70000000-0000-4000-8000-000000000007"
        const val OTHER_CREDENTIAL_ID = "80000000-0000-4000-8000-000000000008"
    }
}

private fun ByteArray.hex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
