package com.yanjiyu.terminalspike.core.backup

import com.yanjiyu.terminalspike.core.data.db.BackupSnapshotRows
import com.yanjiyu.terminalspike.core.data.db.CustomTerminalThemeEntity
import com.yanjiyu.terminalspike.core.data.db.EncryptedSecretEntity
import com.yanjiyu.terminalspike.core.data.db.SshCredentialEntity
import com.yanjiyu.terminalspike.core.data.repository.toEntity
import com.yanjiyu.terminalspike.core.data.repository.toRows
import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.CredentialStore
import com.yanjiyu.terminalspike.core.security.credential.CredentialRecordDecryptor
import com.yanjiyu.terminalspike.core.security.credential.AesGcmCredentialStore
import com.yanjiyu.terminalspike.core.security.credential.EncryptedCredentialRecord
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomBackupSnapshotSourceTest {
    @Test
    fun customFontsRemainExcludedByDefaultAndLoadOnlyForExplicitExportOption() = runTest {
        val bytes = ByteArray(1_024) { index -> (index * 19).toByte() }
        val fontId = "custom_" + MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        val terminal = terminalProfile().copy(fontId = fontId).toEntity()
        val requestedIds = mutableListOf<Set<String>>()
        val source = RoomBackupSnapshotSource(
            readRows = { rows().copy(terminalProfiles = listOf(terminal)) },
            readSettings = { settings() },
            credentialDecryptor = RecordingCredentialStore(emptyMap()),
            customFonts = BackupCustomFontSource { ids ->
                requestedIds += ids
                listOf(BackupCustomFont.copyOf(fontId, "Portable Mono", bytes))
            },
            testOnly = Unit,
        )

        source.create(BackupMode.STANDARD).use { snapshot ->
            assertTrue(snapshot.customFonts.isEmpty())
        }
        source.create(BackupExportOptions(BackupMode.STANDARD, includeCustomFonts = true)).use { snapshot ->
            assertEquals(fontId, snapshot.customFonts.single().fontId)
        }

        assertEquals(listOf(setOf(fontId)), requestedIds)
    }

    @Test
    fun fullSnapshotCopiesSecretsThroughTypedScopedReferences() = runTest {
        val store = RecordingCredentialStore(
            mapOf(PASSWORD_SECRET_ID to "saved password".toByteArray()),
        )
        val source = source(
            rows = rows(
                credentials = listOf(
                    SshCredentialEntity(
                        id = CREDENTIAL_ID,
                        name = "Password",
                        kindCode = "password",
                        secretId = PASSWORD_SECRET_ID,
                        keyIdentityId = null,
                        createdAtEpochMillis = 1,
                        updatedAtEpochMillis = 2,
                    ),
                ),
            ),
            settings = settings(),
            store = store,
        )

        source.create(BackupMode.FULL).use { snapshot ->
            assertEquals(1, snapshot.credentials.size)
            snapshot.credentials.single().portableSecret!!.withBytes { secret ->
                assertArrayEquals("saved password".toByteArray(), secret)
            }
            assertEquals(PASSWORD_SECRET_ID, store.references.single().secretId.value)
            assertEquals(CREDENTIAL_ID, store.references.single().credentialId.value)
            assertEquals(2, snapshot.terminalProfiles.size + snapshot.keyboardProfiles.size)
            assertEquals("Ocean", snapshot.terminalThemes.single().name)
        }
        assertTrue(store.callbackBuffers.all { buffer -> buffer.all { it == 0.toByte() } })
    }

    @Test
    fun standardSnapshotNeverOpensDeviceBoundSecrets() = runTest {
        val store = RecordingCredentialStore(
            mapOf(PASSWORD_SECRET_ID to "saved password".toByteArray()),
        )
        val source = source(
            rows = rows(
                credentials = listOf(
                    SshCredentialEntity(
                        id = CREDENTIAL_ID,
                        name = "Password",
                        kindCode = "password",
                        secretId = PASSWORD_SECRET_ID,
                        keyIdentityId = null,
                        createdAtEpochMillis = 1,
                        updatedAtEpochMillis = 2,
                    ),
                ),
            ),
            settings = settings(),
            store = store,
        )

        source.create(BackupMode.STANDARD).use { snapshot ->
            assertEquals(null, snapshot.credentials.single().portableSecret)
        }
        assertTrue(store.references.isEmpty())
    }

    @Test
    fun captureRetriesCrossStoreMutationAndUsesStableSettings() = runTest {
        val stable = settings(accent = "violet")
        val sequence = ArrayDeque(
            listOf(
                settings(accent = "mint"),
                settings(accent = "amber"),
                stable,
                stable,
            ),
        )
        var rowReads = 0
        val source = RoomBackupSnapshotSource(
            readRows = { rowReads += 1; rows() },
            readSettings = { sequence.removeFirst() },
            credentialDecryptor = RecordingCredentialStore(emptyMap()),
            testOnly = Unit,
        )

        source.create(BackupMode.STANDARD).use { snapshot ->
            assertEquals("violet", snapshot.globalSettings.accentPreset)
        }
        assertEquals(2, rowReads)
    }

    @Test
    fun repeatedCrossStoreMutationFailsWithoutOpeningSecrets() = runTest {
        var settingRead = 0
        val store = RecordingCredentialStore(emptyMap())
        val source = RoomBackupSnapshotSource(
            readRows = { rows() },
            readSettings = {
                settingRead += 1
                settings(accent = if (settingRead % 2 == 0) "amber" else "mint")
            },
            credentialDecryptor = store,
            testOnly = Unit,
        )

        expectThrows<BackupSnapshotException.ConcurrentSettingsMutation> {
            source.create(BackupMode.FULL)
        }
        assertTrue(store.references.isEmpty())
    }

    @Test
    fun settingsMappingPreservesEverySecurityAndBackgroundChoice() {
        val mapped = settings(accent = "indigo").toBuilder()
            .setThemeMode(AppSettings.ThemeMode.THEME_MODE_DARK)
            .setDynamicColorEnabled(true)
            .setReconnectEnabled(true)
            .setBackgroundSessionsEnabled(true)
            .setDisconnectNotificationsEnabled(true)
            .setReconnectNotificationsEnabled(true)
            .setKeepCpuAwake(true)
            .setKeepScreenOnWhileTerminalVisible(true)
            .setAppLockMode(AppSettings.AppLockMode.APP_LOCK_MODE_ON_BACKGROUND)
            .setAppLockDelaySeconds(45)
            .setScreenshotBlockingEnabled(true)
            .setSensitiveClipboardClearSeconds(60)
            .setOsc52Policy(AppSettings.Osc52Policy.OSC52_POLICY_ASK)
            .setLastBackupMode("full")
            .setTmuxSessionSelectorDisabled(true)
            .build()
            .toBackupGlobalSettings()

        assertEquals(BackupThemeMode.DARK, mapped.themeMode)
        assertTrue(mapped.dynamicColorEnabled)
        assertTrue(mapped.reconnectEnabled)
        assertTrue(mapped.backgroundSessionsEnabled)
        assertTrue(mapped.disconnectNotificationsEnabled)
        assertTrue(mapped.reconnectNotificationsEnabled)
        assertTrue(mapped.keepCpuAwake)
        assertTrue(mapped.keepScreenOnWhileTerminalVisible)
        assertEquals(BackupAppLockMode.ON_BACKGROUND, mapped.appLockMode)
        assertEquals(45, mapped.appLockDelaySeconds)
        assertTrue(mapped.screenshotBlockingEnabled)
        assertEquals(60, mapped.sensitiveClipboardClearSeconds)
        assertEquals(BackupMode.FULL, mapped.lastBackupMode)
        assertTrue(mapped.tmuxSessionSelectorDisabled)
    }

    @Test
    fun clearedProtoStringsMapToAbsentPortableSettings() {
        val mapped = settings().toBuilder()
            .clearAccentPreset()
            .clearDefaultTerminalProfileId()
            .clearDefaultKeyboardProfileId()
            .clearLastBackupMode()
            .build()
            .toBackupGlobalSettings()

        assertEquals(null, mapped.accentPreset)
        assertEquals(null, mapped.defaultTerminalProfileId)
        assertEquals(null, mapped.defaultKeyboardProfileId)
        assertEquals(null, mapped.lastBackupMode)
    }

    private fun source(
        rows: BackupSnapshotRows,
        settings: AppSettings,
        store: RecordingCredentialStore,
    ) = RoomBackupSnapshotSource(
        readRows = { rows },
        readSettings = { settings },
        credentialDecryptor = store,
        testOnly = Unit,
    )

    private fun rows(
        credentials: List<SshCredentialEntity> = emptyList(),
    ): BackupSnapshotRows {
        val terminal = terminalProfile().toEntity()
        val keyboard = keyboardProfile().toRows()
        return BackupSnapshotRows(
            terminalProfiles = listOf(terminal),
            keyboardProfiles = listOf(keyboard.profile),
            keyboardKeys = keyboard.keys,
            keyIdentities = emptyList(),
            credentials = credentials,
            hosts = emptyList(),
            knownHosts = emptyList(),
            snippets = emptyList(),
            encryptedSecrets = credentials.mapNotNull { credential ->
                credential.secretId?.let(::encryptedSecret)
            },
            customTerminalThemes = listOf(customTheme()),
        )
    }

    private fun customTheme() = CustomTerminalThemeEntity(
        id = THEME_ID,
        name = "Ocean",
        foregroundArgb = 0xffeeeeee.toInt(),
        backgroundArgb = 0xff101820.toInt(),
        cursorArgb = 0xffffffff.toInt(),
        selectionArgb = 0xff304050.toInt(),
        ansi0Argb = 0xff000000.toInt(),
        ansi1Argb = 0xff000001.toInt(),
        ansi2Argb = 0xff000002.toInt(),
        ansi3Argb = 0xff000003.toInt(),
        ansi4Argb = 0xff000004.toInt(),
        ansi5Argb = 0xff000005.toInt(),
        ansi6Argb = 0xff000006.toInt(),
        ansi7Argb = 0xff000007.toInt(),
        ansi8Argb = 0xff000008.toInt(),
        ansi9Argb = 0xff000009.toInt(),
        ansi10Argb = 0xff00000a.toInt(),
        ansi11Argb = 0xff00000b.toInt(),
        ansi12Argb = 0xff00000c.toInt(),
        ansi13Argb = 0xff00000d.toInt(),
        ansi14Argb = 0xff00000e.toInt(),
        ansi15Argb = 0xff00000f.toInt(),
        boldUsesBrightColours = false,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun encryptedSecret(id: String) = EncryptedSecretEntity(
        id = id,
        kindCode = "password",
        envelopeVersion = AesGcmCredentialStore.ENVELOPE_VERSION,
        keyVersion = AesGcmCredentialStore.KEY_VERSION,
        nonce = ByteArray(12) { 1 },
        ciphertext = ByteArray(17) { 2 },
        stateCode = "ready",
        failureCode = null,
        legacyId = null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private fun settings(accent: String = "mint"): AppSettings = AppSettings.newBuilder()
        .setSchemaRevision(1)
        .setThemeMode(AppSettings.ThemeMode.THEME_MODE_SYSTEM)
        .setDynamicColorEnabled(false)
        .setAccentPreset(accent)
        .setDefaultTerminalProfileId(TERMINAL_PROFILE_ID)
        .setDefaultKeyboardProfileId(KEYBOARD_PROFILE_ID)
        .setKeepaliveIntervalSeconds(30)
        .setReconnectMaxAttempts(5)
        .setNotificationPrivacyEnabled(true)
        .setAppLockMode(AppSettings.AppLockMode.APP_LOCK_MODE_OFF)
        .setOsc52Policy(AppSettings.Osc52Policy.OSC52_POLICY_DISABLED)
        .setMultilinePasteConfirmationEnabled(true)
        .setLastBackupMode("standard")
        .build()

    private fun terminalProfile() = TerminalProfile(
        id = TERMINAL_PROFILE_ID,
        name = "Default terminal",
        themeId = "midnight",
        fontId = "system_monospace",
        fontSizeSp = 14f,
        lineHeightMultiplier = 1f,
        letterSpacingEm = 0f,
        cursorStyle = CursorStyle.BLOCK,
        scrollbackLines = 10_000,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private fun keyboardProfile() = KeyboardProfile(
        id = KEYBOARD_PROFILE_ID,
        name = "Default keyboard",
        orderedActions = listOf(KeyboardAction.ESCAPE, KeyboardAction.CONTROL),
        layout = KeyboardLayout.TWO_ROWS,
        modifierBehavior = ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK,
        hapticFeedbackEnabled = false,
        keyRepeatEnabled = true,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private class RecordingCredentialStore(
        values: Map<String, ByteArray>,
    ) : CredentialStore, CredentialRecordDecryptor {
        private val values = values.mapValues { it.value.copyOf() }
        val references = mutableListOf<CredentialSecretReference>()
        val callbackBuffers = mutableListOf<ByteArray>()

        override suspend fun encryptAndWipe(
            reference: CredentialSecretReference,
            secret: ByteArray,
        ): EncryptedCredentialRecord = error("not used")

        override suspend fun saveAndWipe(reference: CredentialSecretReference, secret: ByteArray) =
            error("not used")

        override suspend fun <T> withSecret(
            reference: CredentialSecretReference,
            use: suspend (ByteArray) -> T,
        ): T = error("Backup must decrypt the captured record, not reread live storage.")

        override suspend fun <T> withEncryptedRecord(
            reference: CredentialSecretReference,
            record: EncryptedCredentialRecord,
            use: suspend (ByteArray) -> T,
        ): T {
            assertEquals(reference.secretId.value, record.secretId)
            references += reference
            val buffer = requireNotNull(values[reference.secretId.value]).copyOf()
            callbackBuffers += buffer
            return try {
                use(buffer)
            } finally {
                buffer.fill(0)
            }
        }

        override suspend fun delete(secretId: SecretId): Boolean = error("not used")
    }

    private suspend inline fun <reified T : Throwable> expectThrows(noinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.name}, got ${error::class.java.name}", error)
        }
        throw AssertionError("Expected ${T::class.java.name}.")
    }

    private companion object {
        val TERMINAL_PROFILE_ID: String = UUID.fromString("10000000-0000-4000-8000-000000000001").toString()
        val KEYBOARD_PROFILE_ID: String = UUID.fromString("20000000-0000-4000-8000-000000000002").toString()
        val CREDENTIAL_ID: String = UUID.fromString("30000000-0000-4000-8000-000000000003").toString()
        val PASSWORD_SECRET_ID: String = UUID.fromString("40000000-0000-4000-8000-000000000004").toString()
        val THEME_ID: String = UUID.fromString("50000000-0000-4000-8000-000000000005").toString()
    }
}
