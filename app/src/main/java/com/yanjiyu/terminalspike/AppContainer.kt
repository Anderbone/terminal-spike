package com.yanjiyu.terminalspike

import android.content.Context
import androidx.room.Room
import com.yanjiyu.terminalspike.core.backup.BackupArchiveCodec
import com.yanjiyu.terminalspike.core.backup.AndroidBackupRecoveryMarkerStore
import com.yanjiyu.terminalspike.core.backup.BackupImportCoordinator
import com.yanjiyu.terminalspike.core.backup.BackupImportResult
import com.yanjiyu.terminalspike.core.backup.EncryptedFileBackupStagingFactory
import com.yanjiyu.terminalspike.core.backup.RoomBackupSnapshotSource
import com.yanjiyu.terminalspike.core.backup.BackupTransferCoordinator
import com.yanjiyu.terminalspike.core.data.credential.RoomCredentialAggregateStore
import com.yanjiyu.terminalspike.core.data.credential.RoomCredentialCiphertextStore
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.migration.LegacyMigrationSourceOutcome
import com.yanjiyu.terminalspike.core.data.migration.LegacyStartupMigrationCoordinator
import com.yanjiyu.terminalspike.core.data.migration.LegacyStartupMigrationResult
import com.yanjiyu.terminalspike.core.data.repository.AuthoritativeDataGate
import com.yanjiyu.terminalspike.core.data.repository.HostProfileRepository
import com.yanjiyu.terminalspike.core.data.repository.CustomTerminalThemeRepository
import com.yanjiyu.terminalspike.core.data.repository.KeyboardProfileRepository
import com.yanjiyu.terminalspike.core.data.repository.KnownHostRepository
import com.yanjiyu.terminalspike.core.data.repository.ProfileDefaultsCoordinator
import com.yanjiyu.terminalspike.core.data.repository.RecentEndpointIdentityPersistence
import com.yanjiyu.terminalspike.core.data.repository.RecentSessionRepository
import com.yanjiyu.terminalspike.core.data.repository.JschPrivateKeyMetadataInspector
import com.yanjiyu.terminalspike.core.data.repository.RoomTerminalDataPersistence
import com.yanjiyu.terminalspike.core.data.repository.SnippetRepository
import com.yanjiyu.terminalspike.core.data.repository.SshCredentialRepository
import com.yanjiyu.terminalspike.core.data.repository.SshKeyIdentityRepository
import com.yanjiyu.terminalspike.core.data.repository.TerminalDataCutoverBlockedException
import com.yanjiyu.terminalspike.core.data.repository.TerminalDataRepository
import com.yanjiyu.terminalspike.core.data.repository.TerminalProfileRepository
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsRepository
import com.yanjiyu.terminalspike.core.security.AppLogger
import com.yanjiyu.terminalspike.core.security.AndroidKeystoreRecentEndpointHmacProvider
import com.yanjiyu.terminalspike.core.security.RecentEndpointIdentityProvider
import com.yanjiyu.terminalspike.core.security.credential.AesGcmCredentialStore
import com.yanjiyu.terminalspike.core.security.credential.AndroidKeystoreCredentialKeyProvider
import com.yanjiyu.terminalspike.connection.KnownHostAuthorityGate
import com.yanjiyu.terminalspike.connection.AndroidNetworkAvailability
import com.yanjiyu.terminalspike.connection.KnownHostManager
import com.yanjiyu.terminalspike.connection.JschSshConnection
import com.yanjiyu.terminalspike.connection.MoshBootstrapExecutor
import com.yanjiyu.terminalspike.connection.MoshConnection
import com.yanjiyu.terminalspike.connection.RecentSessionWriter
import com.yanjiyu.terminalspike.connection.RemoteSessionConnectionFactory
import com.yanjiyu.terminalspike.connection.RemoteSessionConnectionRequest
import com.yanjiyu.terminalspike.connection.RoomKnownHostTrustStore
import com.yanjiyu.terminalspike.connection.SshSessionRepository
import com.yanjiyu.terminalspike.connection.mosh.AndroidMoshExtensionClient
import com.yanjiyu.terminalspike.connection.mosh.MoshExtensionClient
import com.yanjiyu.terminalspike.settings.SettingsLoadFailure
import com.yanjiyu.terminalspike.settings.CustomTerminalFontStore
import com.yanjiyu.terminalspike.terminal.view.TerminalClipboardClearScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Small application-owned dependency container; every disk-backed store is a process singleton. */
class AppContainer internal constructor(
    context: Context,
    private val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val applicationContext = context.applicationContext
    private val startupResolutionMonitor = Any()
    private var startupResolution: Job? = null
    private val recentEndpointBackfillMonitor = Any()
    private var recentEndpointBackfill: Job? = null

    /** One process-wide authority boundary shared by startup recovery and every catalog writer. */
    internal val authoritativeData: AuthoritativeDataGate = AuthoritativeDataGate()

    /** One process-wide device-encrypted marker store for Replace import and startup recovery. */
    internal val backupRecoveryMarkers: AndroidBackupRecoveryMarkerStore by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        AndroidBackupRecoveryMarkerStore(applicationContext)
    }

    val logger: AppLogger by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppLogger() }

    /** Process-long optional-extension boundary; construction performs no bind or network work. */
    val moshExtension: MoshExtensionClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidMoshExtensionClient(applicationContext)
    }

    val database: AppDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME,
        ).build()
    }

    val settings: AppSettingsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppSettingsRepository.create(
            context = applicationContext,
            scope = applicationScope,
        )
    }

    /** One process-owned exact-token clipboard timer; process death cancels rather than guessing. */
    internal val terminalClipboardClearScheduler: TerminalClipboardClearScheduler by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        TerminalClipboardClearScheduler(applicationScope).also { scheduler ->
            applicationScope.launch {
                settings.settings.collect { current ->
                    scheduler.updateDelaySeconds(current.sensitiveClipboardClearSeconds)
                }
            }
        }
    }

    /** Process-singleton private imported-font storage and backup transaction journal. */
    internal val customTerminalFonts: CustomTerminalFontStore by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        CustomTerminalFontStore(applicationContext)
    }

    /** Consistent Room/DataStore snapshot with scoped optional credential/font export. */
    internal val backupSnapshots: RoomBackupSnapshotSource by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        RoomBackupSnapshotSource(
            database = database,
            settingsRepository = settings,
            credentialDecryptor = credentialStore,
            customFonts = { fontIds -> customTerminalFonts.readForBackup(fontIds) },
        )
    }

    /** Streaming portable archive boundary; import staging is private, encrypted, and ephemeral. */
    internal val backupArchives: BackupArchiveCodec by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BackupArchiveCodec(
            EncryptedFileBackupStagingFactory(
                applicationContext.noBackupFilesDir.resolve("backup-staging"),
            ),
        )
    }

    internal val backupTransfers: BackupTransferCoordinator by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        BackupTransferCoordinator(backupSnapshots, backupArchives)
    }

    internal val backupImports: BackupImportCoordinator by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        BackupImportCoordinator(
            database = database,
            settings = settings,
            credentials = credentialStore,
            recoverySnapshots = backupSnapshots,
            recoveryMarkers = backupRecoveryMarkers,
            authority = authoritativeData,
            customFonts = customTerminalFonts,
        )
    }

    /**
     * Starts one application-scope startup-resolution attempt. Concurrent callers share the active
     * attempt; a completed failure may be retried through this same boundary.
     */
    internal fun resolveStartup(): Job = synchronized(startupResolutionMonitor) {
        startupResolution?.takeIf { it.isActive } ?: applicationScope.launch {
            authoritativeData.resolveStartup(
                hasPendingRecovery = backupRecoveryMarkers::hasPendingRecovery,
                recoverPending = {
                    val result = backupImports.recoverPendingForStartup()
                    result.completedStartupRecovery(
                        markerStillPending = backupRecoveryMarkers.hasPendingRecovery(),
                    )
                },
            )
        }.also { startupResolution = it }
    }

    private val credentialKeys by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidKeystoreCredentialKeyProvider()
    }

    private val credentialCiphertexts by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomCredentialCiphertextStore(database)
    }

    /** Process-singleton crypto boundary backed by the singleton Room database. */
    val credentialStore: AesGcmCredentialStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AesGcmCredentialStore(credentialCiphertexts, credentialKeys)
    }

    /** Transactional ciphertext plus credential-metadata repository surface. */
    val credentialRepository: RoomCredentialAggregateStore by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        RoomCredentialAggregateStore(database, credentialStore)
    }

    val sshCredentials: SshCredentialRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SshCredentialRepository(database.sshCredentialDao(), credentialRepository)
    }

    val sshKeyIdentities: SshKeyIdentityRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SshKeyIdentityRepository(database.sshKeyIdentityDao(), credentialRepository)
    }

    val hostProfiles: HostProfileRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HostProfileRepository(database.hostProfileDao())
    }

    val terminalProfiles: TerminalProfileRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TerminalProfileRepository(database.terminalProfileDao())
    }

    val customTerminalThemes: CustomTerminalThemeRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        CustomTerminalThemeRepository(database.customTerminalThemeDao())
    }

    val keyboardProfiles: KeyboardProfileRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KeyboardProfileRepository(database.keyboardProfileDao())
    }

    /** The only supported boundary for selecting or deleting default terminal/keyboard profiles. */
    val profileDefaults: ProfileDefaultsCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProfileDefaultsCoordinator(
            settings = settings,
            terminalProfiles = database.terminalProfileDao(),
            keyboardProfiles = database.keyboardProfileDao(),
        )
    }

    val knownHosts: KnownHostRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KnownHostRepository(database.knownHostDao())
    }

    val snippets: SnippetRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SnippetRepository(database.snippetDao())
    }

    val recentSessions: RecentSessionRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RecentSessionRepository(database.recentSessionDao())
    }

    /** Device-local pseudonymous identity boundary used only for Recent de-duplication. */
    private val recentEndpointIdentityProvider: RecentEndpointIdentityProvider by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        RecentEndpointIdentityProvider(AndroidKeystoreRecentEndpointHmacProvider())
    }

    /** Authority-gated Room backfill and HMAC generation replacement coordinator. */
    private val recentEndpointIdentityPersistence: RecentEndpointIdentityPersistence by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        RecentEndpointIdentityPersistence(
            dao = database.recentSessionDao(),
            identityProvider = recentEndpointIdentityProvider,
            authority = authoritativeData,
        )
    }

    /** Gated compatibility bridge used during the UUID-backed Room production cutover. */
    internal val terminalDataRepository: TerminalDataRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        TerminalDataRepository(
            persistence = RoomTerminalDataPersistence(
                database = database,
                appSettings = settings,
                hosts = hostProfiles,
                credentials = sshCredentials,
                identities = sshKeyIdentities,
                terminalProfiles = terminalProfiles,
                customTerminalThemes = customTerminalThemes,
                keyboardProfiles = keyboardProfiles,
                snippets = snippets,
                credentialMutations = credentialRepository,
                credentialStore = credentialStore,
            ),
            requireAuthority = {
                val result = startLegacyMigrationForCutover().await()
                val blocked = listOf(result.userSettings, result.knownHosts)
                    .filter { it.outcome == LegacyMigrationSourceOutcome.BLOCKED }
                if (blocked.isNotEmpty()) {
                    val settingsKeyUnavailable = result.userSettings.errorCode ==
                        "settings_key_unavailable"
                    throw TerminalDataCutoverBlockedException(
                        recoveryFailure = if (settingsKeyUnavailable) {
                            SettingsLoadFailure.KEY_UNAVAILABLE
                        } else {
                            SettingsLoadFailure.CORRUPT_OR_UNSUPPORTED
                        },
                        errorCode = blocked.joinToString(separator = ",") { outcome ->
                            "${outcome.sourceCode}:${outcome.errorCode ?: "blocked"}"
                        },
                    )
                }
            },
            discardBlockedLegacySettings = {
                val current = startLegacyMigrationForCutover().await()
                var discardedSource = false
                if (current.userSettings.outcome == LegacyMigrationSourceOutcome.BLOCKED) {
                    val discarded = legacyMigrationCoordinator.discardUserSettingsAfterRecovery()
                    check(discarded.outcome != LegacyMigrationSourceOutcome.BLOCKED) {
                        "Legacy settings recovery could not become authoritative."
                    }
                    discardedSource = true
                }
                if (current.knownHosts.outcome == LegacyMigrationSourceOutcome.BLOCKED) {
                    val discarded = legacyMigrationCoordinator.discardKnownHostsAfterRecovery()
                    check(discarded.outcome != LegacyMigrationSourceOutcome.BLOCKED) {
                        "Legacy known-host recovery could not become authoritative."
                    }
                    discardedSource = true
                }
                check(discardedSource) { "No blocked legacy source was available to discard." }
            },
            privateKeyInspector = JschPrivateKeyMetadataInspector,
            authorityGate = authoritativeData,
        )
    }

    /** Process-singleton Room trust manager. It never reads the retained legacy file directly. */
    val knownHostManager: KnownHostManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KnownHostManager(
            RoomKnownHostTrustStore(
                repository = knownHosts,
                authorityGate = KnownHostAuthorityGate {
                    val result = startLegacyMigrationForCutover().await()
                    check(result.isCutoverReady()) {
                        "Legacy migration did not reach an authoritative all-source Room state."
                    }
                },
            ),
        )
    }

    /** Application-owned live SSH/Mosh state; no transport is scoped to a ViewModel lifecycle. */
    internal val sshSessionRepository: SshSessionRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        SshSessionRepository(
            applicationScope = applicationScope,
            foregroundStarter = AndroidSessionForegroundStarter(applicationContext),
            networkAvailability = AndroidNetworkAvailability(applicationContext),
            connectionFactory = RemoteSessionConnectionFactory { request ->
                when (request) {
                    is RemoteSessionConnectionRequest.Ssh -> JschSshConnection(
                        knownHostManager = knownHostManager,
                        config = request.sshConfig,
                    )
                    is RemoteSessionConnectionRequest.Mosh -> MoshConnection(
                        bootstrapExecutor = MoshBootstrapExecutor(knownHostManager),
                        extensionClient = moshExtension,
                        bootstrapRequest = request.bootstrapRequest,
                    )
                }
            },
            recentSessionWriter = RecentSessionWriter { session -> recentSessions.upsert(session) },
            recentEndpointIdentityProvider = recentEndpointIdentityProvider,
            recentEndpointIdentityPersistence = recentEndpointIdentityPersistence,
            onRecentSessionWriteFailure = { error ->
                logger.warning(
                    com.yanjiyu.terminalspike.core.security.AppLogEvent.SETTINGS_WRITE_FAILED,
                    error,
                ) { "Recent-session metadata could not be persisted." }
            },
        )
    }

    internal val legacyMigrationCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LegacyStartupMigrationCoordinator.create(
            context = applicationContext,
            database = database,
            credentialKeys = credentialKeys,
        )
    }

    private val legacyMigrationGate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LegacyCutoverMigrationGate(applicationScope) {
            legacyMigrationCoordinator.migrate()
        }
    }

    /**
     * Starts exactly one migration on the application I/O scope. The Room-consumer cutover owner
     * must explicitly call and await this before switching reads or writes away from legacy stores.
     * It is intentionally not launched during application startup while legacy UI remains active.
     */
    internal fun startLegacyMigrationForCutover(): Deferred<LegacyStartupMigrationResult> =
        legacyMigrationGate.start().also(::scheduleRecentEndpointBackfill)

    /**
     * Runs the privacy-token backfill only after Room has become the authoritative catalog.
     * A hard batch bound keeps startup work finite; a later catalog access resumes any remainder.
     */
    private fun scheduleRecentEndpointBackfill(
        cutover: Deferred<LegacyStartupMigrationResult>,
    ) = synchronized(recentEndpointBackfillMonitor) {
        if (recentEndpointBackfill?.isActive == true) return@synchronized
        recentEndpointBackfill = applicationScope.launch {
            val cutoverReady = runCatching { cutover.await().isCutoverReady() }
                .getOrDefault(false)
            if (!cutoverReady) return@launch
            runCatching {
                sshSessionRepository.backfillRecentEndpointIdentitiesAfterCutover()
            }.onFailure { error ->
                logger.warning(
                    com.yanjiyu.terminalspike.core.security.AppLogEvent.SETTINGS_WRITE_FAILED,
                    error,
                ) { "Recent-session endpoint identities could not be backfilled." }
            }
            runCatching {
                val referencedFonts = database.backupSnapshotDao().readSnapshot()
                    .terminalProfiles.asSequence()
                    .map { it.fontId }
                    .filter { it.startsWith("custom_") }
                    .toSet()
                customTerminalFonts.reconcilePending(referencedFonts)
            }.onFailure { error ->
                logger.warning(
                    com.yanjiyu.terminalspike.core.security.AppLogEvent.SETTINGS_WRITE_FAILED,
                    error,
                ) { "Pending custom-font restore files could not be reconciled." }
            }
        }
    }
}

/**
 * Retains one successful cutover result, but never pins a failed or blocked attempt for the life of
 * the process. A caller arriving after a retryable attempt completes evicts it synchronously before
 * starting the next single-flight attempt.
 */
internal class LegacyCutoverMigrationGate(
    private val scope: CoroutineScope,
    private val migrate: suspend () -> LegacyStartupMigrationResult,
) {
    @Volatile
    private var cached: Deferred<LegacyStartupMigrationResult>? = null

    fun start(): Deferred<LegacyStartupMigrationResult> = synchronized(this) {
        cached?.let { candidate ->
            if (!candidate.isCompleted || candidate.completedCutoverIsReady()) return candidate
            cached = null
        }

        scope.async { migrate() }.also { cached = it }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Deferred<LegacyStartupMigrationResult>.completedCutoverIsReady(): Boolean =
        runCatching { getCompleted().isCutoverReady() }.getOrDefault(false)
}

internal fun LegacyStartupMigrationResult.isCutoverReady(): Boolean =
    userSettings.outcome != LegacyMigrationSourceOutcome.BLOCKED &&
        knownHosts.outcome != LegacyMigrationSourceOutcome.BLOCKED

internal fun BackupImportResult?.completedStartupRecovery(markerStillPending: Boolean): Boolean =
    this != null && recoveryMarkerRemoved && !markerStillPending
