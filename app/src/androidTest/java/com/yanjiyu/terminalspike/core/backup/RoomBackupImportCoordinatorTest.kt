package com.yanjiyu.terminalspike.core.backup

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteConstraintException
import androidx.datastore.core.DataStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.db.BackupImportRows
import com.yanjiyu.terminalspike.core.data.db.BackupImportExactDeletes
import com.yanjiyu.terminalspike.core.data.db.EncryptedSecretEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileEntity
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileKeyEntity
import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeDataGate
import com.yanjiyu.terminalspike.core.data.repository.KeyboardProfileRepository
import com.yanjiyu.terminalspike.core.data.repository.SettingsMutationCoordinator
import com.yanjiyu.terminalspike.core.data.repository.toEntity
import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsRepository
import com.yanjiyu.terminalspike.core.model.KnownHost
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.CredentialStore
import com.yanjiyu.terminalspike.core.security.credential.EncryptedCredentialRecord
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import java.io.File
import java.io.IOException
import java.security.KeyStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
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
class RoomBackupImportCoordinatorTest {
    private lateinit var database: AppDatabase
    private lateinit var recoveryDirectory: File
    private lateinit var recoveryContext: Context

    @Before
    fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        recoveryDirectory = File.createTempFile("backup-recovery-test-", "", context.cacheDir)
        check(recoveryDirectory.delete() && recoveryDirectory.mkdir())
        recoveryContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = recoveryDirectory
        }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun closeDatabase() {
        try {
            if (::database.isInitialized) database.close()
        } finally {
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TEST_RECOVERY_ALIAS)
            }
            if (::recoveryDirectory.isInitialized) recoveryDirectory.deleteRecursively()
        }
    }

    @Test
    fun dataStoreFailureRollsBackTheEntireRoomImportImmediately() = runBlocking {
        val dataStore = MutableSettingsDataStore(settings(), failUpdates = true)
        val markers = RecordingMarkers()
        val coordinator = coordinator(dataStore, markers)
        val incoming = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            snippets = listOf(snippet()),
            globalSettings = BackupGlobalSettings(accentPreset = "violet"),
        )
        val prepared = coordinator.prepare(incoming, BackupImportStrategy.REPLACE_CORRESPONDING)

        expectThrows<IOException> { coordinator.apply(prepared) }

        assertNull(database.snippetDao().findById(SNIPPET_ID))
        assertEquals("mint", dataStore.value.accentPreset)
        assertFalse(markers.pending)
    }

    @Test
    fun queuedSettingsWriteCannotInterfereWithFailedImportRollback() = runBlocking {
        val authority = readyAuthority()
        val replacing = CompletableDeferred<Unit>()
        val failReplacement = CompletableDeferred<Unit>()
        val dataStore = MutableSettingsDataStore(settings(), beforeCommit = { updated ->
            if (updated.accentPreset == "violet") {
                replacing.complete(Unit)
                failReplacement.await()
                throw IOException("Injected replacement failure")
            }
        })
        val markers = RecordingMarkers()
        val imports = coordinator(dataStore, markers, authority)
        val settingsRepository = AppSettingsRepository(dataStore)
        val settingsMutations = SettingsMutationCoordinator(authority)
        val prepared = imports.prepare(
            BackupPayloadSnapshot(
                mode = BackupMode.STANDARD,
                snippets = listOf(snippet()),
                globalSettings = BackupGlobalSettings(accentPreset = "violet"),
            ),
            BackupImportStrategy.REPLACE_CORRESPONDING,
        )
        val restore = async { runCatching { imports.apply(prepared) } }
        withTimeout(10_000) { replacing.await() }
        val edit = async(start = CoroutineStart.UNDISPATCHED) {
            settingsMutations.mutate {
                write { settingsRepository.update { it.setAccentPreset("rose") } }
            }
        }
        val overlappedImport = edit.isCompleted
        // Always release the importer before assertions so a failed regression cannot hang Room.
        failReplacement.complete(Unit)
        val failure = restore.await().exceptionOrNull()
        withTimeout(10_000) { edit.await() }

        assertFalse("A settings edit must wait for import and rollback", overlappedImport)
        assertTrue(failure is IOException)
        assertEquals("rose", dataStore.value.accentPreset)
        assertNull(database.snippetDao().findById(SNIPPET_ID))
        assertFalse(markers.pending)
        assertEquals(1L, authority.awaitReady())
    }

    @Test
    fun replaceDeletesNaturalKnownHostConflictInsideTheSameRoomTransaction() = runBlocking {
        database.knownHostDao().insert(existingKnownHost().toEntity())
        val dataStore = MutableSettingsDataStore(settings())
        val coordinator = coordinator(dataStore, RecordingMarkers())
        val incoming = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            knownHosts = listOf(incomingKnownHost()),
            globalSettings = BackupGlobalSettings(accentPreset = "mint"),
        )

        coordinator.apply(
            coordinator.prepare(incoming, BackupImportStrategy.REPLACE_CORRESPONDING),
        )

        val rows = database.backupSnapshotDao().readSnapshot().knownHosts
        assertFalse(rows.any { it.id == EXISTING_KNOWN_HOST_ID })
        val restored = rows.singleOrNull { it.id == INCOMING_KNOWN_HOST_ID }
        assertNotNull(restored)
        assertEquals("Ag==", restored?.let { java.util.Base64.getEncoder().encodeToString(it.publicKey) })
    }

    @Test
    fun customTerminalThemeIsPersistedByPortableImport() = runBlocking {
        val coordinator = coordinator(MutableSettingsDataStore(settings()), RecordingMarkers())
        val incoming = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            terminalThemes = listOf(customTheme()),
        )

        coordinator.apply(coordinator.prepare(incoming, BackupImportStrategy.MERGE))

        val restored = requireNotNull(database.customTerminalThemeDao().findById(CUSTOM_THEME_ID))
        assertEquals("Ocean", restored.name)
        assertFalse(restored.boldUsesBrightColours)
        assertEquals(0xff101820.toInt(), restored.backgroundArgb)
    }

    @Test
    fun mutationAfterPreviewIsDetectedWithoutOverwritingEitherSide() = runBlocking {
        val coordinator = coordinator(MutableSettingsDataStore(settings()), RecordingMarkers())
        val incoming = BackupPayloadSnapshot(
            mode = BackupMode.STANDARD,
            snippets = listOf(snippet()),
        )
        val prepared = coordinator.prepare(incoming, BackupImportStrategy.MERGE)
        val concurrent = snippet().copy(
            id = CONCURRENT_SNIPPET_ID,
            name = "Concurrent",
            updatedAtEpochMillis = 3,
        )
        database.snippetDao().insert(concurrent.toEntity())

        expectThrows<BackupImportException.StalePreview> { coordinator.apply(prepared) }

        assertNull(database.snippetDao().findById(SNIPPET_ID))
        assertNotNull(database.snippetDao().findById(CONCURRENT_SNIPPET_ID))
    }

    @Test
    fun lazyKeyboardOwnershipInitializationSafelyInvalidatesPreviewWithoutAdvancingGeneration() = runBlocking {
        val dao = database.keyboardProfileDao()
        // Seed legacy rows directly: repository insert already establishes default ownership.
        val legacyProfile = KeyboardProfileEntity(
            id = DEFAULT_KEYBOARD_ID,
            name = "Legacy default",
            rowCount = 1,
            modifierPolicyCode = "one_shot",
            hapticEnabled = true,
            keyRepeatEnabled = true,
            inputModeCode = "raw",
            tmuxPrefix = "C-b",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )
        val originalKeys = KeyboardAction.DEFAULT_ORDER.mapIndexed { index, action ->
            KeyboardProfileKeyEntity(DEFAULT_KEYBOARD_ID, index, action.wireCode)
        }
        dao.insertWithKeys(legacyProfile, originalKeys)
        val existingSnippet = snippet().copy(id = CONCURRENT_SNIPPET_ID, name = "Existing")
        database.snippetDao().insert(existingSnippet.toEntity())
        val authority = readyAuthority()
        val originalAuthorityState = authority.state.value
        val dataStore = MutableSettingsDataStore(settings())
        val originalSettings = dataStore.value
        val markers = RecordingMarkers()
        val imports = coordinator(dataStore, markers, authority)
        val prepared = imports.prepare(
            BackupPayloadSnapshot(mode = BackupMode.STANDARD, snippets = listOf(snippet())),
            BackupImportStrategy.MERGE,
        )
        try {
            assertNull(dao.findById(DEFAULT_KEYBOARD_ID)?.defaultLayoutState)
            val inherited = requireNotNull(KeyboardProfileRepository(dao).get(DEFAULT_KEYBOARD_ID))
            assertNotNull(inherited.defaultLayoutState)
            assertEquals(
                legacyProfile.copy(defaultLayoutState = inherited.defaultLayoutState),
                dao.findById(DEFAULT_KEYBOARD_ID),
            )
            assertEquals(originalKeys, dao.findKeys(DEFAULT_KEYBOARD_ID))
            assertEquals(originalAuthorityState, authority.state.value)
            val rowsAfterOwnershipInitialization = database.backupSnapshotDao().readSnapshot()

            // A read changed provenance, not business data. The exact-row revision still fails
            // closed, even though this lazy initialization does not publish a new generation.
            expectThrows<BackupImportException.StalePreview> { imports.apply(prepared) }

            assertEquals(rowsAfterOwnershipInitialization, database.backupSnapshotDao().readSnapshot())
            assertEquals(existingSnippet.toEntity(), database.snippetDao().findById(CONCURRENT_SNIPPET_ID))
            assertNull(database.snippetDao().findById(SNIPPET_ID))
            assertEquals(originalSettings, dataStore.value)
            assertEquals(originalAuthorityState, authority.state.value)
            assertFalse(markers.pending)
        } finally {
            prepared.close()
        }
    }

    @Test
    fun replaceClearsAbsentOptionalSettingsInDataStore() = runBlocking {
        val dataStore = MutableSettingsDataStore(settings())
        val coordinator = coordinator(dataStore, RecordingMarkers())

        coordinator.apply(
            coordinator.prepare(
                BackupPayloadSnapshot(
                    mode = BackupMode.STANDARD,
                    globalSettings = BackupGlobalSettings(),
                ),
                BackupImportStrategy.REPLACE_CORRESPONDING,
            ),
        )

        assertOptionalSettingsCleared(dataStore.value)
    }

    @Test
    fun unsupportedGlobalRecordKeepsSettingsWhileCompatibleRecordsCommit() = runBlocking {
        val original = settings().toBuilder()
            .setAppLockMode(AppSettings.AppLockMode.APP_LOCK_MODE_IMMEDIATE)
            .setScreenshotBlockingEnabled(true)
            .build()
        val dataStore = MutableSettingsDataStore(original)
        val header = BackupHeaderCodec.decode(BackupHeaderCodec.encode(
            BackupEnvelopeMetadata(BackupMode.FULL, 1, BackupAppVersion(1, "test"), 1, BackupCompatibility(26, 37, 0)),
            BackupEnvelopeFormat.MIN_KDF_ITERATIONS,
            ByteArray(BackupEnvelopeFormat.KDF_SALT_BYTES), ByteArray(BackupEnvelopeFormat.GCM_NONCE_BYTES),
        ))
        // The wire codec's omission behavior is exercised separately with encrypted JVM archives.
        val incoming = PreparedBackupImport(BackupArchiveReadResult(header, BackupPayloadReadResult(
            BackupPayloadSnapshot(mode = BackupMode.FULL, snippets = listOf(snippet())),
            BackupPayloadCompatibilityReport(
                incompatibleRecords = listOf(IncompatibleBackupRecord(
                    BackupPayloadWire.COLLECTION_GLOBAL_SETTINGS, BackupPayloadWire.RECORD_PRIMARY,
                    BackupPayloadFormat.GLOBAL_SETTINGS_RECORD_ID, BackupIncompatibleReason.NEWER_RECORD_SCHEMA,
                )),
                globalSettingsAvailable = false,
            ),
        )))
        incoming.use {
            val coordinator = coordinator(dataStore, RecordingMarkers())
            coordinator.prepare(incoming, BackupImportStrategy.REPLACE_CORRESPONDING).use { prepared ->
                assertEquals(1, prepared.preview.incompatibleRecords)
                val result = coordinator.apply(prepared)
                assertEquals(1, result.incompatibleRecords)
                assertNotNull(database.snippetDao().findById(SNIPPET_ID))
                assertEquals(original, dataStore.value)
            }
        }
    }

    @Test
    fun pendingRecoveryDeletesNewerRoomRowsAndClearsAbsentOptionalSettings() = runBlocking {
        database.snippetDao().insert(snippet().toEntity())
        val dataStore = MutableSettingsDataStore(settings())
        val markers = RecordingMarkers(
            recovery = BackupPayloadSnapshot(
                mode = BackupMode.FULL,
                globalSettings = BackupGlobalSettings(),
            ),
        ).apply { pending = true }
        val coordinator = coordinator(dataStore, markers)

        val result = requireNotNull(coordinator.recoverPending())

        assertTrue(result.recoveryMarkerRemoved)
        assertFalse(markers.pending)
        assertNull(database.snippetDao().findById(SNIPPET_ID))
        assertOptionalSettingsCleared(dataStore.value)
    }

    @Test
    fun pendingRecoveryRestoresCustomThemeAndDeletesThemesCreatedAfterSnapshot() = runBlocking {
        database.customTerminalThemeDao().insert(
            customTheme(name = "Changed after snapshot").toCustomTerminalTheme().toEntity(),
        )
        database.customTerminalThemeDao().insert(
            customTheme(id = NEWER_CUSTOM_THEME_ID, name = "Created after snapshot")
                .toCustomTerminalTheme()
                .toEntity(),
        )
        val markers = RecordingMarkers(
            recovery = BackupPayloadSnapshot(
                mode = BackupMode.FULL,
                terminalThemes = listOf(customTheme()),
            ),
        ).apply { pending = true }
        val coordinator = coordinator(MutableSettingsDataStore(settings()), markers)

        val result = requireNotNull(coordinator.recoverPending())

        assertEquals(1, result.terminalThemesApplied)
        val restored = requireNotNull(database.customTerminalThemeDao().findById(CUSTOM_THEME_ID))
        assertEquals("Ocean", restored.name)
        assertFalse(restored.boldUsesBrightColours)
        assertNull(database.customTerminalThemeDao().findById(NEWER_CUSTOM_THEME_ID))
        assertFalse(markers.pending)
    }

    @Test
    fun persistentRecoveryMarkerIsDeviceEncryptedAndRoundTripsWithoutPlaintextOnDisk() = runBlocking {
        val context = recoveryContext
        val store = AndroidBackupRecoveryMarkerStore(context, keyAlias = TEST_RECOVERY_ALIAS)
        store.clear()
        val portable = PortableBackupSecret.copyAndWipe("recovery-only-secret".encodeToByteArray())
        val snapshot = recoverySnapshot(portable)

        store.writeAndWipe(snapshot)

        expectThrows<BackupRecoveryMarkerException.AlreadyPending> {
            store.writeAndWipe(BackupPayloadSnapshot(BackupMode.FULL))
        }

        val bytes = context.noBackupFilesDir.resolve(
            "backup-recovery/pending-replace.tsp-recovery",
        ).readBytes()
        assertFalse(bytes.containsSubsequence("recovery-only-secret".encodeToByteArray()))
        assertFalse(bytes.containsSubsequence("Recovery password".encodeToByteArray()))
        assertTrue(portable.isWiped)
        store.read().use { restored ->
            val secret = requireNotNull(restored).credentials.single().portableSecret
            secret!!.withBytes { plaintext ->
                assertEquals("recovery-only-secret", plaintext.toString(Charsets.UTF_8))
            }
        }
        store.clear()
        assertFalse(store.hasPendingRecovery())
    }

    @Test
    fun pendingRecoveryDetectionIncludesAtomicFileBackupAfterInterruptedWrite() = runBlocking {
        val context = recoveryContext
        val store = AndroidBackupRecoveryMarkerStore(context, keyAlias = TEST_RECOVERY_ALIAS)
        store.clear()
        val base = context.noBackupFilesDir.resolve(
            "backup-recovery/pending-replace.tsp-recovery",
        )
        val backup = base.resolveSibling("${base.name}.bak")
        try {
            backup.parentFile?.mkdirs()
            backup.writeBytes(byteArrayOf(1))

            assertTrue(store.hasPendingRecovery())
        } finally {
            backup.delete()
            store.clear()
        }
    }

    @Test
    fun cancelledMarkerWriterWipesItsSnapshotWhileWaitingForAnotherOperation() = runBlocking {
        val mutex = Mutex(locked = true)
        val store = AndroidBackupRecoveryMarkerStore(
            recoveryContext, BackupPayloadCodec(), TEST_RECOVERY_ALIAS, mutex,
        )
        val portable = PortableBackupSecret.copyAndWipe("cancelled-recovery-secret".encodeToByteArray())
        val snapshot = recoverySnapshot(portable)
        val writing = launch(start = CoroutineStart.UNDISPATCHED) { store.writeAndWipe(snapshot) }
        assertFalse(writing.isCompleted)

        writing.cancelAndJoin()

        assertTrue(portable.isWiped)
        assertFalse(store.hasPendingRecovery())
    }

    @Test
    fun largeRecoveryDeletesRemoveOnlyTheRequestedRows() = runBlocking {
        database.knownHostDao().insert(existingKnownHost().toEntity())
        val retained = incomingKnownHost().copy(host = "retained.example")
        database.knownHostDao().insert(retained.toEntity())

        database.backupImportDao().applyRows(BackupImportRows(
            exactRecoveryDeletes = BackupImportExactDeletes(knownHostIds = largeRecoveryDeleteIds()),
        ))

        assertTrue(database.knownHostDao().findForEndpoint("server.example", 22).isEmpty())
        assertEquals(retained.id, database.knownHostDao().findForEndpoint("retained.example", 22).single().id)
    }

    @Test
    fun largeRecoveryDeletesRollBackWhenALaterWriteFails() = runBlocking {
        database.knownHostDao().insert(existingKnownHost().toEntity())
        val retained = incomingKnownHost().copy(host = "retained.example")
        database.knownHostDao().insert(retained.toEntity())
        val third = retained.copy(id = CONCURRENT_SNIPPET_ID, host = "third.example")
        database.knownHostDao().insert(third.toEntity())
        val conflicting = third.copy(host = retained.host)

        expectThrows<SQLiteConstraintException> {
            database.backupImportDao().applyRows(BackupImportRows(
                exactRecoveryDeletes = BackupImportExactDeletes(knownHostIds = largeRecoveryDeleteIds()),
                knownHosts = listOf(conflicting.toEntity()),
            ))
        }

        assertEquals(EXISTING_KNOWN_HOST_ID, database.knownHostDao().findForEndpoint("server.example", 22).single().id)
        assertEquals(retained.id, database.knownHostDao().findForEndpoint("retained.example", 22).single().id)
        assertEquals(third.id, database.knownHostDao().findForEndpoint("third.example", 22).single().id)
    }

    @Test
    fun batchedSecretCleanupKeepsReferencedSecretsAndDeletesOnlyOrphans() = runBlocking {
        val exactOrphan = "a1000000-0000-4000-8000-000000000001"
        val possibleOrphan = "a1000000-0000-4000-8000-000000000002"
        val records = database.credentialRecordDao()
        records.insertCredentialWithSecret(
            unavailableSecret(RECOVERY_SECRET_ID),
            SshCredential(
                RECOVERY_CREDENTIAL_ID, "Retained credential",
                SshAuthentication.Password(RECOVERY_SECRET_ID), 1, 1,
            ).toEntity(),
        )
        records.insertSecret(unavailableSecret(exactOrphan))
        records.insertSecret(unavailableSecret(possibleOrphan))
        val absentIds = (0 until 2_000).mapTo(mutableSetOf()) {
            "d0000000-0000-4000-8000-" + it.toString(16).padStart(12, '0')
        }

        database.backupImportDao().applyRows(BackupImportRows(
            exactRecoveryDeletes = BackupImportExactDeletes(
                secretIds = absentIds + RECOVERY_SECRET_ID + exactOrphan,
            ),
            possiblyOrphanedSecretIds = absentIds + RECOVERY_SECRET_ID + possibleOrphan,
        ))

        assertNotNull(records.findSecretById(RECOVERY_SECRET_ID))
        assertNull(records.findSecretById(exactOrphan))
        assertNull(records.findSecretById(possibleOrphan))
    }

    private fun unavailableSecret(id: String) = EncryptedSecretEntity(
        id = id, kindCode = "password", envelopeVersion = 0, keyVersion = 0,
        nonce = null, ciphertext = null, stateCode = "legacy_unavailable",
        failureCode = "backup_secret_not_exported", legacyId = "test:$id",
        createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
    )

    private fun largeRecoveryDeleteIds(): Set<String> = buildSet {
        add(EXISTING_KNOWN_HOST_ID)
        repeat(40_000) { add("f0000000-0000-4000-8000-" + it.toString(16).padStart(12, '0')) }
    }

    private fun recoverySnapshot(portable: PortableBackupSecret) = BackupPayloadSnapshot(
        mode = BackupMode.FULL,
        credentials = listOf(
            BackupCredentialRecord(
                metadata = SshCredential(
                    id = RECOVERY_CREDENTIAL_ID,
                    displayName = "Recovery password",
                    authentication = SshAuthentication.Password(RECOVERY_SECRET_ID),
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 2,
                ),
                portableSecret = portable,
            ),
        ),
    )

    private suspend fun coordinator(
        dataStore: MutableSettingsDataStore,
        markers: RecordingMarkers,
        authority: AuthoritativeDataGate? = null,
    ) = BackupImportCoordinator(
        database = database,
        settings = AppSettingsRepository(dataStore),
        credentials = UnusedCredentialStore,
        recoverySnapshots = BackupSnapshotProvider { BackupPayloadSnapshot(BackupMode.FULL) },
        recoveryMarkers = markers,
        authority = authority ?: readyAuthority(),
    )

    private suspend fun readyAuthority() = AuthoritativeDataGate().also { authority ->
        authority.resolveStartup({ false }, { error("No recovery expected") })
    }

    private fun settings() = AppSettings.newBuilder()
        .setSchemaRevision(1)
        .setThemeMode(AppSettings.ThemeMode.THEME_MODE_SYSTEM)
        .setAccentPreset("mint")
        .setDefaultTerminalProfileId(DEFAULT_TERMINAL_ID)
        .setDefaultKeyboardProfileId(DEFAULT_KEYBOARD_ID)
        .setKeepaliveIntervalSeconds(30)
        .setReconnectMaxAttempts(5)
        .setNotificationPrivacyEnabled(true)
        .setAppLockMode(AppSettings.AppLockMode.APP_LOCK_MODE_OFF)
        .setOsc52Policy(AppSettings.Osc52Policy.OSC52_POLICY_DISABLED)
        .setMultilinePasteConfirmationEnabled(true)
        .setLastBackupMode("standard")
        .build()

    private fun assertOptionalSettingsCleared(actual: AppSettings) {
        assertEquals("", actual.accentPreset)
        assertEquals("", actual.defaultTerminalProfileId)
        assertEquals("", actual.defaultKeyboardProfileId)
        assertEquals("", actual.lastBackupMode)
    }

    private fun snippet() = Snippet(
        id = SNIPPET_ID,
        name = "Working directory",
        command = "pwd",
        tapAction = SnippetTapAction.INSERT,
        appendEnter = false,
        confirmMultilineExecution = true,
        isFavorite = true,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun existingKnownHost() = KnownHost(
        id = EXISTING_KNOWN_HOST_ID,
        host = "server.example",
        port = 22,
        keyAlgorithm = "ssh-ed25519",
        fingerprint = "SHA256:S/USLzRFVMU73i67jNK349FgCtYxw4Wl18ziPHeFRZo",
        publicHostKey = "AQ==",
        firstSeenAtEpochMillis = 1,
        lastSeenAtEpochMillis = 2,
    )

    private fun incomingKnownHost() = KnownHost(
        id = INCOMING_KNOWN_HOST_ID,
        host = "server.example",
        port = 22,
        keyAlgorithm = "ssh-ed25519",
        fingerprint = "SHA256:28G0yQD/5I1XW12lxjgEASX2XbD+PiRJS3bqmGRX2YY",
        publicHostKey = "Ag==",
        firstSeenAtEpochMillis = 3,
        lastSeenAtEpochMillis = 4,
    )

    private fun customTheme(
        id: String = CUSTOM_THEME_ID,
        name: String = "Ocean",
    ) = BackupTerminalTheme(
        id = id,
        name = name,
        foregroundArgb = 0xffeeeeee.toInt(),
        backgroundArgb = 0xff101820.toInt(),
        cursorArgb = 0xffffffff.toInt(),
        selectionArgb = 0xff304050.toInt(),
        ansi16Argb = List(16) { index -> 0xff000000.toInt() or index },
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
        boldUsesBrightColours = false,
    )

    private class MutableSettingsDataStore(
        initial: AppSettings,
        private val failUpdates: Boolean = false,
        private val beforeCommit: suspend (AppSettings) -> Unit = {},
    ) : DataStore<AppSettings> {
        private val mutable = MutableStateFlow(initial)
        val value: AppSettings
            get() = mutable.value

        override val data: Flow<AppSettings> = mutable

        override suspend fun updateData(transform: suspend (t: AppSettings) -> AppSettings): AppSettings {
            val updated = transform(mutable.value)
            beforeCommit(updated)
            if (failUpdates) throw IOException("Injected DataStore write failure")
            mutable.value = updated
            return updated
        }
    }

    private class RecordingMarkers(
        private val recovery: BackupPayloadSnapshot? = null,
    ) : BackupRecoveryMarkerStore {
        var pending = false

        override suspend fun writeAndWipe(snapshot: BackupPayloadSnapshot) {
            try {
                if (pending) throw BackupRecoveryMarkerException.AlreadyPending()
                pending = true
            } finally {
                snapshot.close()
            }
        }

        override suspend fun read(): BackupPayloadSnapshot? = recovery

        override suspend fun clear() {
            pending = false
        }

        override fun hasPendingRecovery(): Boolean = pending
    }

    private object UnusedCredentialStore : CredentialStore {
        override suspend fun encryptAndWipe(
            reference: CredentialSecretReference,
            secret: ByteArray,
        ): EncryptedCredentialRecord = error("not used")

        override suspend fun saveAndWipe(reference: CredentialSecretReference, secret: ByteArray) =
            error("not used")

        override suspend fun <T> withSecret(
            reference: CredentialSecretReference,
            use: suspend (ByteArray) -> T,
        ): T = error("not used")

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

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) return false
        return (0..size - candidate.size).any { start ->
            candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
        }
    }

    private companion object {
        const val SNIPPET_ID = "10000000-0000-4000-8000-000000000001"
        const val EXISTING_KNOWN_HOST_ID = "20000000-0000-4000-8000-000000000002"
        const val INCOMING_KNOWN_HOST_ID = "30000000-0000-4000-8000-000000000003"
        const val DEFAULT_TERMINAL_ID = "40000000-0000-4000-8000-000000000004"
        const val DEFAULT_KEYBOARD_ID = "50000000-0000-4000-8000-000000000005"
        const val CONCURRENT_SNIPPET_ID = "60000000-0000-4000-8000-000000000006"
        const val RECOVERY_CREDENTIAL_ID = "70000000-0000-4000-8000-000000000007"
        const val RECOVERY_SECRET_ID = "80000000-0000-4000-8000-000000000008"
        const val CUSTOM_THEME_ID = "90000000-0000-4000-8000-000000000009"
        const val NEWER_CUSTOM_THEME_ID = "a0000000-0000-4000-8000-00000000000a"
        const val TEST_RECOVERY_ALIAS = "terminal_spike_backup_recovery_instrumentation_test"
    }
}
