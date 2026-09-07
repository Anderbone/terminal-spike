package com.yanjiyu.terminalspike.core.data.repository

import androidx.datastore.core.DataStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.core.data.credential.CredentialEpochClock
import com.yanjiyu.terminalspike.core.data.credential.EncryptedSecretState
import com.yanjiyu.terminalspike.core.data.credential.PersistedCredentialException
import com.yanjiyu.terminalspike.core.data.credential.RoomCredentialAggregateStore
import com.yanjiyu.terminalspike.core.data.credential.RoomCredentialCiphertextStore
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.db.EncryptedSecretEntity
import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsRepository
import com.yanjiyu.terminalspike.core.model.BellSettings
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.CursorStyle
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.LinkBehavior
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.RemoteClipboardMode
import com.yanjiyu.terminalspike.core.model.ScrollBehavior
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.core.model.TouchScrollMode
import com.yanjiyu.terminalspike.core.security.credential.AesGcmCredentialStore
import com.yanjiyu.terminalspike.core.security.credential.CredentialKeyProvider
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
class RoomTerminalDataPersistenceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AppDatabase
    private lateinit var settings: AppSettingsRepository

    @Before
    fun createDatabase() = runBlocking {
        context.deleteDatabase(DATABASE_NAME)
        database = openDatabase()
        settings = AppSettingsRepository(
            InMemoryDataStore(
                AppSettings.newBuilder()
                    .setSchemaRevision(1)
                    .setThemeMode(AppSettings.ThemeMode.THEME_MODE_SYSTEM)
                    .setAccentPreset("mint")
                    .setDefaultTerminalProfileId(TERMINAL_PROFILE_ID)
                    .setDefaultKeyboardProfileId(KEYBOARD_PROFILE_ID)
                    .setKeepaliveIntervalSeconds(30)
                    .setReconnectMaxAttempts(5)
                    .setNotificationPrivacyEnabled(true)
                    .setAppLockMode(AppSettings.AppLockMode.APP_LOCK_MODE_OFF)
                    .setOsc52Policy(AppSettings.Osc52Policy.OSC52_POLICY_DISABLED)
                    .setMultilinePasteConfirmationEnabled(true)
                    .setLastBackupMode("standard")
                    .build(),
            ),
        )
        insertDefaultsAndHost(database)
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized && database.isOpen) database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun hostMetadataFailureRollsBackCiphertextCredentialAndRelationship() = runBlocking {
        val persistence = persistence(database) {
            throw ExpectedMetadataFailure()
        }
        val password = "must roll back".toByteArray()

        val failure = runCatching {
            persistence.savePassword(
                hostId = HOST_ID,
                expectedCredentialId = null,
                credentialId = CREDENTIAL_ID,
                secretId = SECRET_ID,
                password = password,
            )
        }.exceptionOrNull()

        assertTrue(failure is ExpectedMetadataFailure)
        assertTrue(password.all { it == 0.toByte() })
        assertNull(database.credentialRecordDao().findSecretById(SECRET_ID))
        assertNull(database.credentialRecordDao().findCredentialById(CREDENTIAL_ID))
        assertNull(database.hostProfileDao().findById(HOST_ID)?.credentialId)
    }

    @Test
    fun acknowledgedPasswordRelationshipAndSecretSurviveDatabaseReopen() = runBlocking {
        val password = "reopen secret".toByteArray()
        persistence(database).savePassword(
            hostId = HOST_ID,
            expectedCredentialId = null,
            credentialId = CREDENTIAL_ID,
            secretId = SECRET_ID,
            password = password,
        )
        assertTrue(password.all { it == 0.toByte() })

        database.close()
        database = openDatabase()
        val reopened = persistence(database)

        assertEquals(CREDENTIAL_ID, database.hostProfileDao().findById(HOST_ID)?.credentialId)
        assertEquals(
            SECRET_ID,
            database.credentialRecordDao().findCredentialById(CREDENTIAL_ID)?.secretId,
        )
        assertNotNull(database.credentialRecordDao().findSecretById(SECRET_ID))
        assertArrayEquals("reopen secret".toByteArray(), reopened.copyPassword(HOST_ID))
    }

    @Test
    fun terminalAndKeyboardSettingsSurviveDatabaseReopen() = runBlocking {
        val terminal = requireNotNull(TerminalProfileRepository(database.terminalProfileDao()).get(TERMINAL_PROFILE_ID))
        val keyboard = requireNotNull(KeyboardProfileRepository(database.keyboardProfileDao()).get(KEYBOARD_PROFILE_ID))
        assertTrue(
            TerminalProfileRepository(database.terminalProfileDao()).update(
                terminal.copy(fontSizeSp = 19f),
            ),
        )
        assertTrue(
            KeyboardProfileRepository(database.keyboardProfileDao()).update(
                keyboard.copy(modifierBehavior = ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK),
            ),
        )

        database.close()
        database = openDatabase()

        assertEquals(
            19f,
            TerminalProfileRepository(database.terminalProfileDao()).get(TERMINAL_PROFILE_ID)?.fontSizeSp,
        )
        assertEquals(
            ModifierBehavior.ONE_SHOT_WITH_DOUBLE_TAP_LOCK,
            KeyboardProfileRepository(database.keyboardProfileDao()).get(KEYBOARD_PROFILE_ID)
                ?.modifierBehavior,
        )
    }

    @Test
    fun unavailableLegacyPasswordRemainsReferencedButIsProjectedAsUnavailable() = runBlocking {
        database.credentialRecordDao().insertSecret(
            EncryptedSecretEntity(
                id = SECRET_ID,
                kindCode = CredentialSecretKind.PASSWORD.wireCode,
                envelopeVersion = 0,
                keyVersion = 0,
                nonce = null,
                ciphertext = null,
                stateCode = EncryptedSecretState.LEGACY_UNAVAILABLE.wireCode,
                failureCode = "key_unavailable",
                legacyId = "7",
                createdAtEpochMillis = NOW,
                updatedAtEpochMillis = NOW,
            ),
        )
        database.credentialRecordDao().insertCredential(
            SshCredential(
                id = CREDENTIAL_ID,
                displayName = "Preserved password",
                authentication = SshAuthentication.Password(SECRET_ID),
                createdAtEpochMillis = NOW,
                updatedAtEpochMillis = NOW,
            ).toEntity(),
        )
        val hosts = HostProfileRepository(database.hostProfileDao())
        assertTrue(hosts.update(requireNotNull(hosts.get(HOST_ID)).copy(credentialId = CREDENTIAL_ID)))

        val records = persistence(database).readRecords()

        assertTrue(SECRET_ID in records.unavailableSecretIds)
        assertEquals(CREDENTIAL_ID, records.hosts.single().credentialId)
        assertTrue(
            runCatching { persistence(database).copyPassword(HOST_ID) }.exceptionOrNull()
                is PersistedCredentialException.LegacyUnavailable,
        )
    }

    @Test
    fun readRecordsReturnsEverySelectableProfileAndConfiguredDefaultsFromTheSameLists() = runBlocking {
        val records = persistence(database).readRecords()

        assertEquals(
            listOf(TERMINAL_PROFILE_ID, SECOND_TERMINAL_PROFILE_ID),
            records.terminalProfiles.map(TerminalProfile::id),
        )
        assertEquals(
            listOf(KEYBOARD_PROFILE_ID, SECOND_KEYBOARD_PROFILE_ID),
            records.keyboardProfiles.map(KeyboardProfile::id),
        )
        assertEquals(TERMINAL_PROFILE_ID, records.defaultTerminalProfileId)
        assertTrue(
            records.terminalProfiles.any { profile ->
                profile.id == records.defaultTerminalProfileId
            },
        )
        assertEquals(KEYBOARD_PROFILE_ID, records.defaultKeyboardProfile.id)
        assertEquals(
            records.defaultKeyboardProfile,
            records.keyboardProfiles.single { profile ->
                profile.id == records.defaultKeyboardProfile.id
            },
        )
    }

    @Test
    fun clearedDefaultPointersResolveToExistingProfilesForCompatibilityProjection() = runBlocking {
        settings.update { builder ->
            builder.clearDefaultTerminalProfileId()
            builder.clearDefaultKeyboardProfileId()
        }

        val records = persistence(database).readRecords()

        assertEquals(TERMINAL_PROFILE_ID, records.defaultTerminalProfileId)
        assertEquals(KEYBOARD_PROFILE_ID, records.defaultKeyboardProfile.id)
    }

    private fun openDatabase(): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        DATABASE_NAME,
    ).build()

    private fun persistence(
        target: AppDatabase,
        beforeHostWrite: suspend () -> Unit = {},
    ): RoomTerminalDataPersistence {
        val crypto = AesGcmCredentialStore(
            RoomCredentialCiphertextStore(target, CLOCK),
            FixedKeyProvider,
        )
        val aggregate = RoomCredentialAggregateStore(target, crypto, CLOCK)
        return RoomTerminalDataPersistence(
            database = target,
            appSettings = settings,
            hosts = HostProfileRepository(target.hostProfileDao()),
            credentials = SshCredentialRepository(target.sshCredentialDao(), aggregate),
            identities = SshKeyIdentityRepository(target.sshKeyIdentityDao(), aggregate),
            terminalProfiles = TerminalProfileRepository(target.terminalProfileDao()),
            keyboardProfiles = KeyboardProfileRepository(target.keyboardProfileDao()),
            snippets = SnippetRepository(target.snippetDao()),
            credentialMutations = aggregate,
            credentialStore = crypto,
            clock = CLOCK,
            beforeHostCredentialMetadataWrite = beforeHostWrite,
        )
    }

    private suspend fun insertDefaultsAndHost(target: AppDatabase) {
        val defaultTerminalProfile = TerminalProfile(
            id = TERMINAL_PROFILE_ID,
            name = "Default",
            themeId = "current",
            fontId = "system_monospace",
            fontSizeSp = 14f,
            lineHeightMultiplier = 1f,
            letterSpacingEm = 0f,
            cursorStyle = CursorStyle.BLOCK,
            scrollbackLines = 20_000,
            bell = BellSettings(),
            scroll = ScrollBehavior(touchMode = TouchScrollMode.AUTO),
            links = LinkBehavior(remoteClipboardMode = RemoteClipboardMode.DISABLED),
            createdAtEpochMillis = NOW,
            updatedAtEpochMillis = NOW,
        )
        TerminalProfileRepository(target.terminalProfileDao()).run {
            insert(defaultTerminalProfile)
            insert(
                defaultTerminalProfile.copy(
                    id = SECOND_TERMINAL_PROFILE_ID,
                    name = "Large text",
                    fontSizeSp = 18f,
                ),
            )
        }
        val defaultKeyboardProfile = KeyboardProfile(
            id = KEYBOARD_PROFILE_ID,
            name = "Default",
            orderedActions = listOf(KeyboardAction.ESCAPE, KeyboardAction.CONTROL),
            layout = KeyboardLayout.ONE_ROW,
            modifierBehavior = ModifierBehavior.ONE_SHOT,
            hapticFeedbackEnabled = false,
            keyRepeatEnabled = true,
            inputMode = TerminalInputMode.RAW,
            createdAtEpochMillis = NOW,
            updatedAtEpochMillis = NOW,
        )
        KeyboardProfileRepository(target.keyboardProfileDao()).run {
            insert(defaultKeyboardProfile)
            insert(
                defaultKeyboardProfile.copy(
                    id = SECOND_KEYBOARD_PROFILE_ID,
                    name = "Vim",
                    orderedActions = listOf(KeyboardAction.ESCAPE, KeyboardAction.TAB),
                ),
            )
        }
        HostProfileRepository(target.hostProfileDao()).insert(
            HostProfile(
                id = HOST_ID,
                displayName = "Server",
                hostname = "server.example",
                port = 22,
                username = "alice",
                protocol = ConnectionProtocol.SSH,
                credentialId = null,
                terminalProfileId = TERMINAL_PROFILE_ID,
                keyboardProfileId = KEYBOARD_PROFILE_ID,
                createdAtEpochMillis = NOW,
                updatedAtEpochMillis = NOW,
            ),
        )
    }

    private class InMemoryDataStore<T>(initial: T) : DataStore<T> {
        private val state = MutableStateFlow(initial)
        private val updateLock = Any()
        override val data: Flow<T> = state

        override suspend fun updateData(transform: suspend (t: T) -> T): T {
            val current = AtomicReference(state.value)
            val updated = transform(current.get())
            synchronized(updateLock) { state.value = updated }
            return updated
        }
    }

    private object FixedKeyProvider : CredentialKeyProvider {
        private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        override fun getOrCreateEncryptionKey(keyVersion: Int): SecretKey = key
        override fun getExistingDecryptionKey(keyVersion: Int): SecretKey = key
    }

    private class ExpectedMetadataFailure : RuntimeException()

    private companion object {
        const val DATABASE_NAME = "terminal-data-persistence-test.db"
        const val HOST_ID = "60000000-0000-4000-8000-000000000001"
        const val CREDENTIAL_ID = "60000000-0000-4000-8000-000000000002"
        const val SECRET_ID = "60000000-0000-4000-8000-000000000003"
        const val TERMINAL_PROFILE_ID = "60000000-0000-4000-8000-000000000004"
        const val KEYBOARD_PROFILE_ID = "60000000-0000-4000-8000-000000000005"
        const val SECOND_TERMINAL_PROFILE_ID = "60000000-0000-4000-8000-000000000006"
        const val SECOND_KEYBOARD_PROFILE_ID = "60000000-0000-4000-8000-000000000007"
        const val NOW = 100L
        val CLOCK = CredentialEpochClock { NOW }
    }
}
