package com.yanjiyu.terminalspike.core.data.repository

import com.yanjiyu.terminalspike.core.data.credential.ClearAllSavedCredentialsResult
import com.yanjiyu.terminalspike.core.data.credential.CredentialEpochClock
import com.yanjiyu.terminalspike.core.data.credential.SavedCredentialClearPreview
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.CustomTerminalTheme
import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.ModelLimits
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.model.SnippetTapAction
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import com.yanjiyu.terminalspike.core.model.SshKeyIdentity
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.settings.SavedHostConnectionCompatibility
import com.yanjiyu.terminalspike.settings.SavedSshIdentity
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.settings.SettingsLoadFailure
import com.yanjiyu.terminalspike.settings.SettingsLoadResult
import com.yanjiyu.terminalspike.settings.UserSettings
import com.yanjiyu.terminalspike.terminal.view.TerminalExtraKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One authoritative Room snapshot used to rebuild the legacy-shaped presentation state during the
 * bounded UI cutover. UUIDs remain the only persistent identities.
 */
internal data class TerminalDataRecords(
    val hosts: List<HostProfile>,
    val credentials: List<SshCredential>,
    val identities: List<SshKeyIdentity>,
    val snippets: List<Snippet>,
    val defaultTerminalProfileId: String,
    val defaultKeyboardProfile: KeyboardProfile,
    val terminalProfiles: List<TerminalProfile> = emptyList(),
    val customTerminalThemes: List<CustomTerminalTheme> = emptyList(),
    val keyboardProfiles: List<KeyboardProfile> = listOf(defaultKeyboardProfile),
    val unavailableSecretIds: Set<String> = emptySet(),
    val migrationWarningCodes: List<String> = emptyList(),
)

internal data class ImportedPrivateKeyMetadata(
    val algorithm: String,
    val fingerprintSha256: String,
    val openSshPublicKey: String,
    val isPassphraseProtected: Boolean,
)

internal fun interface PrivateKeyMetadataInspector {
    fun inspect(privateKey: ByteArray): ImportedPrivateKeyMetadata
}

internal data class TerminalDataCredentialClearOutcome(
    val cleared: ClearAllSavedCredentialsResult,
    val settings: UserSettings,
)

/**
 * Authentication selected by the UUID-backed host editor.
 *
 * Mutable secret bytes are accepted only at the repository boundary and are wiped on every exit.
 * Opaque references remain in Room; plaintext never enters catalog or ViewModel state.
 */
internal sealed interface HostAuthenticationUpdate {
    /** Prompt on every connection and remove any previous host credential binding. */
    data object PromptPassword : HostAuthenticationUpdate

    /** Keep the currently bound, available saved password without loading or replacing it. */
    data object RetainSavedPassword : HostAuthenticationUpdate

    /** Encrypt and atomically bind a replacement password. */
    data class SavePassword(val secret: ByteArray) : HostAuthenticationUpdate

    /** Bind an existing encrypted private-key identity; its passphrase remains session-only. */
    data class PrivateKey(val identityId: String) : HostAuthenticationUpdate

    /** Prompt for keyboard-interactive responses on each connection. */
    data object KeyboardInteractive : HostAuthenticationUpdate
}

/** The narrow persistence surface kept behind [TerminalDataRepository]'s cutover gate and mutex. */
internal interface TerminalDataPersistence {
    suspend fun readRecords(): TerminalDataRecords

    suspend fun upsertHost(profile: HostProfile, retainPassword: Boolean)

    suspend fun upsertHostWithCredentialMetadata(
        profile: HostProfile,
        expectedCredentialId: String?,
        credential: SshCredential?,
    )

    suspend fun upsertHostWithPassword(
        profile: HostProfile,
        expectedCredentialId: String?,
        credentialId: String,
        secretId: String,
        password: ByteArray,
    )

    suspend fun deleteHostAndUnreferencedPassword(hostId: String): Boolean

    suspend fun savePassword(
        hostId: String,
        expectedCredentialId: String?,
        credentialId: String,
        secretId: String,
        password: ByteArray,
    )

    suspend fun clearPassword(hostId: String): Boolean

    suspend fun importIdentity(identity: SshKeyIdentity, privateKey: ByteArray)

    suspend fun updateIdentityMetadata(identity: SshKeyIdentity): Boolean

    suspend fun deleteIdentity(identityId: String): Boolean

    suspend fun upsertSnippet(snippet: Snippet)

    suspend fun deleteSnippet(snippetId: String): Boolean

    suspend fun replaceDefaultKeyboardActions(actions: List<KeyboardAction>)

    suspend fun copyPassword(hostId: String): ByteArray

    suspend fun copyPrivateKey(identityId: String): ByteArray

    suspend fun copyCredentialSecret(credentialId: String): ByteArray

    suspend fun previewClearAllSavedCredentials(): SavedCredentialClearPreview

    suspend fun clearAllSavedCredentials(): ClearAllSavedCredentialsResult
}

/** Typed signal used to preserve the existing explicit recovery UI before Room becomes authority. */
internal class TerminalDataCutoverBlockedException(
    val recoveryFailure: SettingsLoadFailure,
    val errorCode: String?,
) : IllegalStateException("Legacy settings migration is blocked${errorCode?.let { " ($it)" } ?: ""}.")

/** A read of the authoritative Room/DataStore projection failed without modifying its files. */
internal class TerminalDataAuthoritativeReadException(cause: Throwable) :
    IllegalStateException("Authoritative local data could not be read and was preserved.", cause)

/** The mutation committed, but the compatibility snapshot could not be refreshed afterward. */
internal class TerminalDataCommittedRefreshException(
    val readFailure: TerminalDataAuthoritativeReadException,
) : IllegalStateException(
    "The local-data mutation committed, but its presentation snapshot could not be refreshed.",
    readFailure,
)

/** Replacing this key would leave at least one dependent credential with a stale saved passphrase. */
internal class IdentityReimportBlockedBySavedPassphraseException(val identityId: String) :
    IllegalStateException(
        "SSH identity $identityId cannot be replaced while a dependent credential has a saved passphrase.",
    )

/**
 * Compatibility boundary for the current UI while its Long IDs are replaced by core UUID models.
 *
 * Every persistence boundary crosses [authorityGate] and awaits [requireAuthority] before touching
 * Room. Once a successful snapshot has been loaded, this class never reads or writes a legacy file.
 * Presentation IDs are allocated from one collision-free process-local sequence, are never
 * serialized, and remain reserved after deletion or a failed persistence attempt so an ID can
 * never name a different record in-process.
 */
internal class TerminalDataRepository(
    private val persistence: TerminalDataPersistence,
    private val authorityGate: AuthoritativeDataGate,
    private val requireAuthority: suspend () -> Unit,
    private val discardBlockedLegacySettings: suspend () -> Unit,
    private val privateKeyInspector: PrivateKeyMetadataInspector,
    private val clock: CredentialEpochClock = CredentialEpochClock.SYSTEM,
    private val ids: EphemeralTerminalIdRegistry = EphemeralTerminalIdRegistry(),
) {
    private val operations = Mutex()
    private var records: TerminalDataRecords? = null
    private var representedGeneration: Long? = null
    private var presentationIdsInitialized = false
    private var blockedLegacyRecoveryFailure: SettingsLoadFailure? = null

    fun reserveHostPresentationId(): Long = ids.reserveNew(TerminalRecordKind.HOST)

    fun persistentHostId(presentationId: Long): String =
        ids.requireUuid(TerminalRecordKind.HOST, presentationId)

    fun reserveIdentityPresentationId(): Long = ids.reserveNew(TerminalRecordKind.IDENTITY)

    fun reserveSnippetPresentationId(): Long = ids.reserveNew(TerminalRecordKind.SNIPPET)

    suspend fun load(): SettingsLoadResult = loadCatalog().compatibility

    suspend fun loadCatalog(): TerminalDataCatalogLoadResult = operations.withLock {
        authorityGate.withRead { generation ->
            try {
                ensureLoaded(generation)
                blockedLegacyRecoveryFailure = null
                val authoritative = requireNotNull(records)
                TerminalDataCatalogLoadResult(
                    compatibility = authoritative.toSettingsLoadResult(ids),
                    catalog = authoritative.toCatalog(ids),
                )
            } catch (blocked: TerminalDataCutoverBlockedException) {
                check(records == null) {
                    "An authoritative Room snapshot cannot fall back to legacy recovery."
                }
                blockedLegacyRecoveryFailure = blocked.recoveryFailure
                TerminalDataCatalogLoadResult(
                    compatibility = SettingsLoadResult(
                        settings = UserSettings(),
                        failure = blocked.recoveryFailure,
                    ),
                    catalog = null,
                )
            } catch (_: TerminalDataAuthoritativeReadException) {
                check(records == null) {
                    "An unreadable authoritative snapshot must not remain cached."
                }
                blockedLegacyRecoveryFailure = null
                TerminalDataCatalogLoadResult(
                    compatibility = SettingsLoadResult(
                        settings = UserSettings(),
                        failure = SettingsLoadFailure.APP_DATA_UNAVAILABLE,
                    ),
                    catalog = null,
                )
            }
        }
    }

    /** May be called only from the explicit recovery-confirmation UI while cutover is blocked. */
    suspend fun resetAfterRecoveryConfirmation(): SettingsLoadResult = operations.withLock {
        check(records == null && blockedLegacyRecoveryFailure != null) {
            "No blocked legacy-settings recovery is pending."
        }
        authorityGate.withMutation {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                discardBlockedLegacySettings()
                val committedGeneration = markCommitted()
                blockedLegacyRecoveryFailure = null
                invalidateRecords()
                requireAuthority()
                try {
                    acceptRecords(readAuthoritativeRecords(), committedGeneration)
                    requireNotNull(records).toSettingsLoadResult(ids)
                } catch (_: TerminalDataAuthoritativeReadException) {
                    invalidateRecords()
                    SettingsLoadResult(
                        settings = UserSettings(),
                        failure = SettingsLoadFailure.APP_DATA_UNAVAILABLE,
                    )
                }
            }
        }
    }

    suspend fun saveProfile(profile: SavedSshProfile): UserSettings = authorizedMutation {
        val current = requireNotNull(records)
        val uuid = ids.resolveOrReserve(TerminalRecordKind.HOST, profile.id)
        val existing = current.hosts.firstOrNull { it.id == uuid }
        val existingCredential = existing?.credentialId?.let { credentialId ->
            current.credentials.firstOrNull { credential -> credential.id == credentialId }
        }
        val existingPassword = existingCredential?.authentication as? SshAuthentication.Password
        val sameCredentialScope = existing != null &&
            existing.hostname.equals(profile.host, ignoreCase = true) &&
            existing.port == profile.port &&
            existing.username == profile.username
        // The compatibility UI has a dedicated forget action; its display boolean cannot
        // distinguish prompt-only/unavailable password metadata from an explicit clear request.
        // Preserve every password binding while the authentication endpoint remains unchanged.
        val retainsPassword = existingPassword != null && sameCredentialScope
        val retainedCredentialId = when {
            existingCredential == null -> null
            existingCredential.authentication !is SshAuthentication.Password -> existingCredential.id
            retainsPassword -> existingCredential.id
            else -> null
        }
        val now = checkedNow()
        val candidate = HostProfile(
            id = uuid,
            displayName = profile.label,
            hostname = profile.host,
            port = profile.port,
            username = profile.username,
            protocol = existing?.protocol ?: profile.protocol,
            credentialId = retainedCredentialId,
            terminalProfileId = if (existing == null) {
                current.defaultTerminalProfileId
            } else {
                existing.terminalProfileId
            },
            keyboardProfileId = if (existing == null) {
                current.defaultKeyboardProfile.id
            } else {
                existing.keyboardProfileId
            },
            isFavorite = existing?.isFavorite ?: false,
            group = existing?.group,
            tag = existing?.tag,
            startupCommand = existing?.startupCommand,
            keepaliveIntervalSeconds = existing?.keepaliveIntervalSeconds,
            reconnectPolicy = existing?.reconnectPolicy,
            moshPort = existing?.moshPort ?: profile.moshPort,
            moshPortRange = existing?.moshPortRange ?: profile.moshPortRange,
            moshServerCommand = existing?.moshServerCommand ?: profile.moshServerCommand,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = maxOf(now, existing?.updatedAtEpochMillis ?: now),
        )
        persistence.upsertHost(candidate, retainPassword = retainsPassword)
    }

    suspend fun deleteProfile(presentationId: Long): UserSettings = authorizedMutation {
        val uuid = ids.requireUuid(TerminalRecordKind.HOST, presentationId)
        check(persistence.deleteHostAndUnreferencedPassword(uuid)) {
            "Host profile disappeared before its acknowledged deletion."
        }
    }

    suspend fun savePassword(presentationHostId: Long, password: ByteArray): UserSettings = try {
        authorizedMutation {
            val hostId = ids.requireUuid(TerminalRecordKind.HOST, presentationHostId)
            val current = requireNotNull(records)
            val existingHost = current.hosts.firstOrNull { it.id == hostId }
                ?: error("Host profile disappeared before its password could be saved.")
            val existingCredential = existingHost.credentialId?.let { credentialId ->
                current.credentials.firstOrNull { it.id == credentialId }
            }?.takeIf { it.authentication is SshAuthentication.Password }
            val credentialId = existingCredential?.id ?: UUID.randomUUID().toString()
            val secretId = (existingCredential?.authentication as? SshAuthentication.Password)
                ?.secretReferenceId ?: UUID.randomUUID().toString()
            persistence.savePassword(
                hostId = hostId,
                expectedCredentialId = existingHost.credentialId,
                credentialId = credentialId,
                secretId = secretId,
                password = password,
            )
        }
    } finally {
        password.fill(0)
    }

    /** Saves host metadata and its password as one transaction for connect-and-save flows. */
    suspend fun saveProfileWithPassword(
        profile: SavedSshProfile,
        password: ByteArray,
    ): UserSettings = try {
        authorizedMutation {
            val current = requireNotNull(records)
            val uuid = ids.resolveOrReserve(TerminalRecordKind.HOST, profile.id)
            val existing = current.hosts.firstOrNull { it.id == uuid }
            val existingCredential = existing?.credentialId?.let { credentialId ->
                current.credentials.firstOrNull { credential -> credential.id == credentialId }
            }
            val existingPassword = existingCredential?.authentication as? SshAuthentication.Password
            val credentialId = existingCredential?.id?.takeIf { existingPassword != null }
                ?: UUID.randomUUID().toString()
            val secretId = existingPassword?.secretReferenceId ?: UUID.randomUUID().toString()
            val now = checkedNow()
            val candidate = HostProfile(
                id = uuid,
                displayName = profile.label,
                hostname = profile.host,
                port = profile.port,
                username = profile.username,
                protocol = existing?.protocol ?: profile.protocol,
                credentialId = credentialId,
                terminalProfileId = if (existing == null) {
                    current.defaultTerminalProfileId
                } else {
                    existing.terminalProfileId
                },
                keyboardProfileId = if (existing == null) {
                    current.defaultKeyboardProfile.id
                } else {
                    existing.keyboardProfileId
                },
                isFavorite = existing?.isFavorite ?: false,
                group = existing?.group,
                tag = existing?.tag,
                startupCommand = existing?.startupCommand,
                keepaliveIntervalSeconds = existing?.keepaliveIntervalSeconds,
                reconnectPolicy = existing?.reconnectPolicy,
                moshPort = existing?.moshPort ?: profile.moshPort,
                moshPortRange = existing?.moshPortRange ?: profile.moshPortRange,
                moshServerCommand = existing?.moshServerCommand ?: profile.moshServerCommand,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = maxOf(now, existing?.updatedAtEpochMillis ?: now),
            )
            persistence.upsertHostWithPassword(
                profile = candidate,
                expectedCredentialId = existing?.credentialId,
                credentialId = credentialId,
                secretId = secretId,
                password = password,
            )
        }
    } finally {
        password.fill(0)
    }

    suspend fun forgetPassword(presentationHostId: Long): UserSettings = authorizedMutation {
        val hostId = ids.requireUuid(TerminalRecordKind.HOST, presentationHostId)
        check(persistence.clearPassword(hostId)) {
            "Host profile disappeared before its password could be cleared."
        }
    }

    /**
     * Persists every field from the UUID-backed host editor and changes authentication as one
     * guarded aggregate mutation. The supplied profile never carries plaintext authentication.
     */
    suspend fun saveHostProfile(
        profile: HostProfile,
        authentication: HostAuthenticationUpdate,
    ): UserSettings = try {
        authorizedMutation {
            val current = requireNotNull(records)
            val existing = current.hosts.firstOrNull { it.id == profile.id }
            val existingCredential = existing?.credentialId?.let { credentialId ->
                current.credentials.firstOrNull { credential -> credential.id == credentialId }
            }
            val now = checkedNow()
            val base = profile.copy(
                credentialId = null,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: profile.createdAtEpochMillis,
                updatedAtEpochMillis = maxOf(
                    now,
                    profile.updatedAtEpochMillis,
                    existing?.updatedAtEpochMillis ?: 0L,
                ),
            )
            when (authentication) {
                HostAuthenticationUpdate.PromptPassword -> {
                    persistence.upsertHostWithCredentialMetadata(
                        profile = base,
                        expectedCredentialId = existing?.credentialId,
                        credential = null,
                    )
                }

                HostAuthenticationUpdate.RetainSavedPassword -> {
                    val existingHost = requireNotNull(existing) {
                        "A new host cannot retain a saved password."
                    }
                    val password = existingCredential?.takeIf { credential ->
                        val selected = credential.authentication as? SshAuthentication.Password
                        selected?.secretReferenceId != null &&
                            selected.secretReferenceId !in current.unavailableSecretIds
                    } ?: throw IllegalArgumentException(
                        "The host no longer has an available saved password.",
                    )
                    require(existingHost.sameAuthenticationEndpointAs(base)) {
                        "A saved password cannot be retained after changing its endpoint or username."
                    }
                    persistence.upsertHostWithCredentialMetadata(
                        profile = base.copy(credentialId = password.id),
                        expectedCredentialId = existingHost.credentialId,
                        credential = password,
                    )
                }

                is HostAuthenticationUpdate.SavePassword -> {
                    require(authentication.secret.isNotEmpty()) { "A saved password must not be empty." }
                    val existingPassword = existingCredential
                        ?.takeIf { it.authentication is SshAuthentication.Password }
                        ?.takeIf { existing.sameAuthenticationEndpointAs(base) }
                    val credentialId = existingPassword?.id ?: UUID.randomUUID().toString()
                    val secretId = (existingPassword?.authentication as? SshAuthentication.Password)
                        ?.secretReferenceId ?: UUID.randomUUID().toString()
                    persistence.upsertHostWithPassword(
                        profile = base.copy(credentialId = credentialId),
                        expectedCredentialId = existing?.credentialId,
                        credentialId = credentialId,
                        secretId = secretId,
                        password = authentication.secret,
                    )
                }

                is HostAuthenticationUpdate.PrivateKey -> {
                    val identity = current.identities.firstOrNull { it.id == authentication.identityId }
                        ?: throw IllegalArgumentException("The selected SSH key no longer exists.")
                    val credentialName = "${base.displayName} key"
                        .take(ModelLimits.MAX_DISPLAY_NAME_LENGTH)
                    val existingPrivateKey = existingCredential?.takeIf { credential ->
                        (credential.authentication as? SshAuthentication.PrivateKey)
                            ?.keyIdentityId == identity.id
                    }
                    val credential = existingPrivateKey ?: SshCredential(
                        id = UUID.randomUUID().toString(),
                        displayName = credentialName,
                        authentication = SshAuthentication.PrivateKey(identity.id),
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    )
                    persistence.upsertHostWithCredentialMetadata(
                        profile = base.copy(credentialId = credential.id),
                        expectedCredentialId = existing?.credentialId,
                        credential = credential.copy(
                            displayName = credentialName,
                            updatedAtEpochMillis = maxOf(now, credential.updatedAtEpochMillis),
                        ),
                    )
                }

                HostAuthenticationUpdate.KeyboardInteractive -> {
                    val credentialName = "${base.displayName} interactive"
                        .take(ModelLimits.MAX_DISPLAY_NAME_LENGTH)
                    val existingInteractive = existingCredential
                        ?.takeIf { it.authentication is SshAuthentication.KeyboardInteractive }
                    val credential = existingInteractive ?: SshCredential(
                        id = UUID.randomUUID().toString(),
                        displayName = credentialName,
                        authentication = SshAuthentication.KeyboardInteractive(),
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    )
                    persistence.upsertHostWithCredentialMetadata(
                        profile = base.copy(credentialId = credential.id),
                        expectedCredentialId = existing?.credentialId,
                        credential = credential.copy(
                            displayName = credentialName,
                            updatedAtEpochMillis = maxOf(now, credential.updatedAtEpochMillis),
                        ),
                    )
                }
            }
        }
    } finally {
        if (authentication is HostAuthenticationUpdate.SavePassword) {
            authentication.secret.fill(0)
        }
    }

    suspend fun deleteHostProfile(persistentId: String): UserSettings = authorizedMutation {
        requireCanonicalPersistentId(persistentId, "host profile ID")
        check(requireNotNull(records).hosts.any { it.id == persistentId }) {
            "Host profile disappeared before its acknowledged deletion."
        }
        check(persistence.deleteHostAndUnreferencedPassword(persistentId)) {
            "Host profile disappeared before its acknowledged deletion."
        }
    }

    suspend fun importIdentity(
        presentationId: Long,
        label: String,
        privateKey: ByteArray,
    ): UserSettings = try {
        authorizedMutation {
            val uuid = ids.resolveOrReserve(TerminalRecordKind.IDENTITY, presentationId)
            val current = requireNotNull(records)
            val existing = current.identities.firstOrNull { it.id == uuid }
            if (existing != null) {
                val hasSavedDependentPassphrase = current.credentials.any { credential ->
                    val authentication = credential.authentication
                    authentication is SshAuthentication.PrivateKey &&
                        authentication.keyIdentityId == uuid &&
                        authentication.passphraseSecretReferenceId != null
                }
                if (hasSavedDependentPassphrase) {
                    throw IdentityReimportBlockedBySavedPassphraseException(uuid)
                }
            }
            val metadata = privateKeyInspector.inspect(privateKey)
            if (existing != null) {
                require(
                    metadata.algorithm == existing.algorithm &&
                        metadata.fingerprintSha256 == existing.publicKeyFingerprint,
                ) {
                    "The selected private key does not match the identity being recovered."
                }
            }
            val now = checkedNow()
            val candidate = SshKeyIdentity(
                id = uuid,
                name = label,
                algorithm = metadata.algorithm,
                publicKeyFingerprint = metadata.fingerprintSha256,
                publicKey = metadata.openSshPublicKey,
                privateKeySecretReferenceId = existing?.privateKeySecretReferenceId
                    ?: UUID.randomUUID().toString(),
                origin = existing?.origin ?: SshKeyOrigin.IMPORTED,
                isPassphraseProtected = metadata.isPassphraseProtected,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = maxOf(now, existing?.updatedAtEpochMillis ?: now),
                comment = existing?.comment,
            )
            persistence.importIdentity(candidate, privateKey)
        }
    } finally {
        privateKey.fill(0)
    }

    suspend fun deleteIdentity(presentationId: Long): UserSettings = authorizedMutation {
        val uuid = ids.requireUuid(TerminalRecordKind.IDENTITY, presentationId)
        check(persistence.deleteIdentity(uuid)) {
            "SSH identity disappeared before its acknowledged deletion."
        }
    }

    /** Imports or generates one identity using its real persistent UUID. */
    suspend fun saveIdentity(
        identity: SshKeyIdentity,
        privateKey: ByteArray,
    ): UserSettings = try {
        authorizedMutation {
            val current = requireNotNull(records)
            val existing = current.identities.firstOrNull { it.id == identity.id }
            val inspected = privateKeyInspector.inspect(privateKey)
            require(inspected.algorithm == identity.algorithm) {
                "The private-key algorithm does not match its metadata."
            }
            require(inspected.fingerprintSha256 == identity.publicKeyFingerprint) {
                "The private-key fingerprint does not match its metadata."
            }
            require(inspected.openSshPublicKey == identity.publicKey) {
                "The private key does not match its public key."
            }
            if (existing != null) {
                require(existing.privateKeySecretReferenceId == identity.privateKeySecretReferenceId) {
                    "An existing SSH key cannot be rebound to another encrypted-secret ID."
                }
            }
            persistence.importIdentity(
                identity.copy(
                    createdAtEpochMillis = existing?.createdAtEpochMillis
                        ?: identity.createdAtEpochMillis,
                    updatedAtEpochMillis = maxOf(
                        checkedNow(),
                        identity.updatedAtEpochMillis,
                        existing?.updatedAtEpochMillis ?: 0L,
                    ),
                ),
                privateKey,
            )
        }
    } finally {
        privateKey.fill(0)
    }

    suspend fun renameIdentity(
        persistentId: String,
        name: String,
        comment: String?,
    ): UserSettings = authorizedMutation {
        requireCanonicalPersistentId(persistentId, "SSH identity ID")
        val existing = requireNotNull(records).identities.firstOrNull { it.id == persistentId }
            ?: error("SSH identity disappeared before its metadata could be updated.")
        check(
            persistence.updateIdentityMetadata(
                existing.copy(
                    name = name,
                    comment = comment,
                    updatedAtEpochMillis = maxOf(checkedNow(), existing.updatedAtEpochMillis),
                ),
            ),
        ) { "SSH identity disappeared before its metadata could be updated." }
    }

    suspend fun deleteIdentity(persistentId: String): UserSettings = authorizedMutation {
        requireCanonicalPersistentId(persistentId, "SSH identity ID")
        check(persistence.deleteIdentity(persistentId)) {
            "SSH identity disappeared before its acknowledged deletion."
        }
    }

    suspend fun saveSnippet(snippet: CommandSnippet): UserSettings = authorizedMutation {
        val current = requireNotNull(records)
        val uuid = ids.resolveOrReserve(TerminalRecordKind.SNIPPET, snippet.id)
        val existing = current.snippets.firstOrNull { it.id == uuid }
        val now = checkedNow()
        val isMultiline = snippet.command.any { it == '\n' || it == '\r' }
        val tapAction = existing?.tapAction ?: SnippetTapAction.SEND_IMMEDIATELY
        persistence.upsertSnippet(
            Snippet(
                id = uuid,
                name = snippet.label,
                group = existing?.group,
                command = snippet.command,
                tapAction = tapAction,
                appendEnter = snippet.appendEnter,
                confirmMultilineExecution = (tapAction == SnippetTapAction.SEND_IMMEDIATELY && isMultiline) ||
                    (existing?.confirmMultilineExecution ?: snippet.confirmMultilineExecution),
                isFavorite = existing?.isFavorite ?: false,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = maxOf(now, existing?.updatedAtEpochMillis ?: now),
            ),
        )
    }

    suspend fun deleteSnippet(presentationId: Long): UserSettings = authorizedMutation {
        val uuid = ids.requireUuid(TerminalRecordKind.SNIPPET, presentationId)
        check(persistence.deleteSnippet(uuid)) {
            "Snippet disappeared before its acknowledged deletion."
        }
    }

    suspend fun saveSnippet(snippet: Snippet): UserSettings = authorizedMutation {
        val current = requireNotNull(records)
        val existing = current.snippets.firstOrNull { it.id == snippet.id }
        persistence.upsertSnippet(
            snippet.copy(
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: snippet.createdAtEpochMillis,
                updatedAtEpochMillis = maxOf(
                    checkedNow(),
                    snippet.updatedAtEpochMillis,
                    existing?.updatedAtEpochMillis ?: 0L,
                ),
            ),
        )
    }

    suspend fun deleteSnippet(persistentId: String): UserSettings = authorizedMutation {
        requireCanonicalPersistentId(persistentId, "snippet ID")
        check(persistence.deleteSnippet(persistentId)) {
            "Snippet disappeared before its acknowledged deletion."
        }
    }

    suspend fun saveExtraKeys(keys: List<TerminalExtraKey>): UserSettings = authorizedMutation {
        val currentActions = requireNotNull(records).defaultKeyboardProfile.orderedActions
        require(keys.distinct().size == keys.size) { "Terminal keys must be unique." }
        val visibleCurrentActions = currentActions.filter { it.toTerminalExtraKeyOrNull() != null }
        if (keys.isEmpty()) {
            require(visibleCurrentActions.isEmpty()) {
                "At least one visible terminal key is required."
            }
        }
        val mergedActions = if (keys.isEmpty()) {
            currentActions
        } else {
            val requestedActions = keys.map(TerminalExtraKey::toKeyboardAction)
            mergeCompatibilityKeyboardActions(currentActions, requestedActions)
        }
        persistence.replaceDefaultKeyboardActions(mergedActions)
    }

    suspend fun copyPassword(presentationHostId: Long): ByteArray = operations.withLock {
        authorityGate.withRead { generation ->
            ensureLoaded(generation)
            persistence.copyPassword(ids.requireUuid(TerminalRecordKind.HOST, presentationHostId))
        }
    }

    suspend fun copyPrivateKey(presentationIdentityId: Long): ByteArray = operations.withLock {
        authorityGate.withRead { generation ->
            ensureLoaded(generation)
            persistence.copyPrivateKey(
                ids.requireUuid(TerminalRecordKind.IDENTITY, presentationIdentityId),
            )
        }
    }

    suspend fun copyPrivateKey(persistentIdentityId: String): ByteArray = operations.withLock {
        authorityGate.withRead { generation ->
            ensureLoaded(generation)
            requireCanonicalPersistentId(persistentIdentityId, "SSH identity ID")
            persistence.copyPrivateKey(persistentIdentityId)
        }
    }

    suspend fun copyCredentialSecret(persistentCredentialId: String): ByteArray = operations.withLock {
        authorityGate.withRead { generation ->
            ensureLoaded(generation)
            requireCanonicalPersistentId(persistentCredentialId, "SSH credential ID")
            persistence.copyCredentialSecret(persistentCredentialId)
        }
    }

    suspend fun previewClearAllSavedCredentials(): SavedCredentialClearPreview = operations.withLock {
        authorityGate.withRead { generation ->
            ensureLoaded(generation)
            persistence.previewClearAllSavedCredentials()
        }
    }

    /** Returns only after the authoritative compatibility catalog has reloaded the cleared state. */
    suspend fun clearAllSavedCredentials(): TerminalDataCredentialClearOutcome {
        lateinit var cleared: ClearAllSavedCredentialsResult
        val settings = authorizedMutation {
            cleared = persistence.clearAllSavedCredentials()
        }
        return TerminalDataCredentialClearOutcome(cleared, settings)
    }

    private suspend fun authorizedMutation(
        mutation: suspend () -> Unit,
    ): UserSettings = operations.withLock {
        authorityGate.withMutation {
            ensureLoaded(generation)
            currentCoroutineContext().ensureActive()
            // Once persistence begins, publish its generation and reconcile Room truth even if the
            // UI owner is cancelled. This closes the commit/resume cancellation window without
            // advancing the generation for a persistence call that failed before committing.
            withContext(NonCancellable) {
                var mutationFailure: Throwable? = null
                var reconciliationGeneration = generation
                try {
                    mutation()
                    reconciliationGeneration = markCommitted()
                } catch (failure: Throwable) {
                    mutationFailure = failure
                } finally {
                    invalidateRecords()
                }

                var lastReadFailure: TerminalDataAuthoritativeReadException? = null
                repeat(POST_COMMIT_REFRESH_ATTEMPTS) {
                    var refreshed = false
                    try {
                        acceptRecords(readAuthoritativeRecords(), reconciliationGeneration)
                        refreshed = true
                    } catch (failure: TerminalDataAuthoritativeReadException) {
                        lastReadFailure = failure
                    }
                    if (refreshed) {
                        mutationFailure?.let { throw it }
                        return@withContext requireNotNull(records).toLegacySettings(ids)
                    }
                }
                mutationFailure?.let { failure ->
                    lastReadFailure?.let(failure::addSuppressed)
                    throw failure
                }
                throw TerminalDataCommittedRefreshException(requireNotNull(lastReadFailure))
            }
        }
    }

    private suspend fun ensureLoaded(generation: Long) {
        if (representedGeneration != null && representedGeneration != generation) {
            invalidateRecords()
        }
        requireAuthority()
        if (records == null) {
            acceptRecords(readAuthoritativeRecords(), generation)
        }
    }

    private suspend fun readAuthoritativeRecords(): TerminalDataRecords = try {
        persistence.readRecords()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: TerminalDataAuthoritativeReadException) {
        throw failure
    } catch (failure: Exception) {
        throw TerminalDataAuthoritativeReadException(failure)
    }

    private fun acceptRecords(authoritative: TerminalDataRecords, generation: Long) {
        val presentationRecords = authoritative.presentationRecords()
        if (!presentationIdsInitialized) {
            ids.rebuild(presentationRecords)
            presentationIdsInitialized = true
        } else {
            ids.reconcile(presentationRecords)
        }
        records = authoritative
        representedGeneration = generation
    }

    private fun invalidateRecords() {
        records = null
        representedGeneration = null
    }

    private fun TerminalDataRecords.presentationRecords() = buildList {
        hosts.forEach { add(TerminalRecordKind.HOST to it.id) }
        identities.forEach { add(TerminalRecordKind.IDENTITY to it.id) }
        snippets.forEach { add(TerminalRecordKind.SNIPPET to it.id) }
    }

    private fun checkedNow(): Long = clock.nowEpochMillis().also { now ->
        require(now in 0..253_402_300_799_999L) { "Timestamp is outside the supported range." }
    }

    private companion object {
        const val POST_COMMIT_REFRESH_ATTEMPTS = 2
    }
}

private fun HostProfile.sameAuthenticationEndpointAs(other: HostProfile): Boolean =
    hostname.equals(other.hostname, ignoreCase = true) &&
        port == other.port &&
        username == other.username

private fun requireCanonicalPersistentId(value: String, fieldName: String) {
    require(value.length == 36 && runCatching { UUID.fromString(value).toString() }.getOrNull() == value) {
        "$fieldName must be a canonical lowercase UUID."
    }
}

private fun mergeCompatibilityKeyboardActions(
    current: List<KeyboardAction>,
    requestedVisible: List<KeyboardAction>,
): List<KeyboardAction> {
    val requested = requestedVisible.iterator()
    return buildList(current.size + requestedVisible.size) {
        current.forEach { action ->
            if (action.toTerminalExtraKeyOrNull() == null) {
                add(action)
            } else if (requested.hasNext()) {
                add(requested.next())
            }
        }
        while (requested.hasNext()) add(requested.next())
    }
}

internal enum class TerminalRecordKind {
    HOST,
    IDENTITY,
    SNIPPET,
}

/** Collision-free global presentation-ID registry with permanent in-process tombstones. */
internal class EphemeralTerminalIdRegistry {
    private val uuidByKey = mutableMapOf<Pair<TerminalRecordKind, Long>, String>()
    private val idByRecord = mutableMapOf<Pair<TerminalRecordKind, String>, Long>()
    private val retired = mutableSetOf<Pair<TerminalRecordKind, Long>>()
    private val observedRecords = mutableSetOf<Pair<TerminalRecordKind, String>>()
    private var nextId = 1L
    private var rebuilt = false

    @Synchronized
    fun rebuild(records: List<Pair<TerminalRecordKind, String>>) {
        check(!rebuilt && uuidByKey.isEmpty() && idByRecord.isEmpty()) {
            "Presentation IDs may be rebuilt only once per process repository."
        }
        val distinct = records.distinct()
        require(distinct.size == records.size) { "A Room UUID was repeated in one record kind." }
        distinct.sortedWith(compareBy<Pair<TerminalRecordKind, String>> { it.first.ordinal }.thenBy { it.second })
            .forEach { (kind, uuid) ->
                require(UUID.fromString(uuid).toString() == uuid) { "Room IDs must be canonical UUIDs." }
                bind(kind, nextAvailableId(), uuid)
            }
        observedRecords += distinct
        rebuilt = true
    }

    /** Reconciles records previously observed in Room while retaining uncommitted UUID reservations. */
    @Synchronized
    fun reconcile(records: List<Pair<TerminalRecordKind, String>>) {
        check(rebuilt) { "Presentation IDs must be rebuilt before reconciliation." }
        val current = records.toSet()
        require(current.size == records.size) { "A Room UUID was repeated in one record kind." }
        current.forEach { (kind, uuid) ->
            require(UUID.fromString(uuid).toString() == uuid) { "Room IDs must be canonical UUIDs." }
            if (idByRecord[kind to uuid] == null) {
                bind(kind, nextAvailableId(), uuid)
            }
        }
        (observedRecords - current).forEach { record ->
            idByRecord[record]?.let { presentationId ->
                retire(record.first, presentationId)
            }
        }
        observedRecords.clear()
        observedRecords += current
    }

    @Synchronized
    fun reserveNew(kind: TerminalRecordKind): Long {
        check(rebuilt) { "Presentation IDs cannot be reserved before the Room snapshot is loaded." }
        val presentationId = nextAvailableId()
        bind(kind, presentationId, UUID.randomUUID().toString())
        return presentationId
    }

    @Synchronized
    fun resolveOrReserve(kind: TerminalRecordKind, presentationId: Long): String {
        require(presentationId > 0) { "Presentation IDs must be positive." }
        val key = kind to presentationId
        uuidByKey[key]?.let { return it }
        check(retired.none { it.second == presentationId }) {
            "A retired presentation ID cannot be reused by any record kind."
        }
        check(uuidByKey.keys.none { it.second == presentationId }) {
            "A presentation ID cannot identify two record kinds."
        }
        return UUID.randomUUID().toString().also { uuid ->
            bind(kind, presentationId, uuid)
            if (presentationId >= nextId) {
                check(presentationId < Long.MAX_VALUE) { "Presentation IDs are exhausted." }
                nextId = presentationId + 1
            }
        }
    }

    @Synchronized
    fun requireUuid(kind: TerminalRecordKind, presentationId: Long): String =
        uuidByKey[kind to presentationId]
            ?: throw IllegalArgumentException("Unknown $kind presentation ID $presentationId.")

    @Synchronized
    fun presentationId(kind: TerminalRecordKind, uuid: String): Long =
        idByRecord[kind to uuid]
            ?: throw IllegalArgumentException("Unknown $kind Room UUID $uuid.")

    @Synchronized
    fun retire(kind: TerminalRecordKind, presentationId: Long) {
        val key = kind to presentationId
        val uuid = uuidByKey.remove(key)
            ?: throw IllegalArgumentException("Unknown $kind presentation ID $presentationId.")
        idByRecord.remove(kind to uuid)
        retired += key
    }

    private fun nextAvailableId(): Long {
        while (
            uuidByKey.keys.any { it.second == nextId } ||
            retired.any { it.second == nextId }
        ) {
            check(nextId < Long.MAX_VALUE) { "Presentation IDs are exhausted." }
            nextId += 1
        }
        return nextId.also {
            check(nextId < Long.MAX_VALUE) { "Presentation IDs are exhausted." }
            nextId += 1
        }
    }

    private fun bind(kind: TerminalRecordKind, presentationId: Long, uuid: String) {
        check(uuidByKey.put(kind to presentationId, uuid) == null)
        check(idByRecord.put(kind to uuid, presentationId) == null)
    }
}

private fun TerminalDataRecords.toLegacySettings(ids: EphemeralTerminalIdRegistry): UserSettings {
    val credentialsById = credentials.associateBy(SshCredential::id)
    val profiles = hosts.map { host ->
        val credential = host.credentialId?.let(credentialsById::get)
        val hasSavedPassword = (credential?.authentication as? SshAuthentication.Password)
            ?.secretReferenceId
            ?.let { it !in unavailableSecretIds }
            ?: false
        SavedSshProfile(
            id = ids.presentationId(TerminalRecordKind.HOST, host.id),
            label = host.displayName,
            host = host.hostname,
            port = host.port,
            username = host.username,
            hasSavedPassword = hasSavedPassword,
            connectionCompatibility = when {
                credential?.authentication is SshAuthentication.PrivateKey ->
                    SavedHostConnectionCompatibility.PRIVATE_KEY_REQUIRES_FULL_UI
                credential?.authentication is SshAuthentication.KeyboardInteractive ->
                    SavedHostConnectionCompatibility.KEYBOARD_INTERACTIVE_UNAVAILABLE
                host.startupCommand != null || host.keepaliveIntervalSeconds != null ||
                    host.reconnectPolicy != null ||
                    (host.terminalProfileId != null &&
                        host.terminalProfileId != defaultTerminalProfileId) ||
                    (host.keyboardProfileId != null &&
                        host.keyboardProfileId != defaultKeyboardProfile.id) ||
                    !defaultKeyboardProfile.supportsCompatibilityRuntime() ->
                    SavedHostConnectionCompatibility.PROFILE_OPTIONS_REQUIRE_FULL_UI
                host.protocol == ConnectionProtocol.MOSH ->
                    SavedHostConnectionCompatibility.MOSH_PASSWORD
                else -> SavedHostConnectionCompatibility.SSH_PASSWORD
            },
            persistentId = host.id,
            isFavorite = host.isFavorite,
            protocol = host.protocol,
            moshPort = host.moshPort,
            moshPortRange = host.moshPortRange,
            moshServerCommand = host.moshServerCommand,
        )
    }
    val presentationIdentities = identities.map { identity ->
        SavedSshIdentity(
            id = ids.presentationId(TerminalRecordKind.IDENTITY, identity.id),
            label = identity.name,
            keyType = identity.algorithm,
            fingerprint = identity.publicKeyFingerprint,
            passphraseRequired = identity.isPassphraseProtected,
            isAvailable = identity.privateKeySecretReferenceId !in unavailableSecretIds,
            recoveryToken = identity.id,
        )
    }
    val presentationSnippets = snippets.map { snippet ->
        CommandSnippet(
            id = ids.presentationId(TerminalRecordKind.SNIPPET, snippet.id),
            label = snippet.name,
            command = snippet.command,
            appendEnter = snippet.appendEnter,
            confirmMultilineExecution = snippet.confirmMultilineExecution,
            sendsImmediately = snippet.tapAction == SnippetTapAction.SEND_IMMEDIATELY,
        )
    }
    val mappedKeys = defaultKeyboardProfile.orderedActions
        .mapNotNull(KeyboardAction::toTerminalExtraKeyOrNull)
        .upgradeShippedDefaultDeck()
    val keyboardRuntimeCompatible = defaultKeyboardProfile.supportsCompatibilityRuntime()
    return UserSettings(
        profiles = profiles,
        snippets = presentationSnippets,
        extraKeys = mappedKeys.takeIf { keyboardRuntimeCompatible }.orEmpty(),
        identities = presentationIdentities,
        keyboardRuntimeCompatible = keyboardRuntimeCompatible,
    )
}

/**
 * Existing installs keep their custom deck exactly. Only either byte-for-byte action order that
 * previously shipped as a default is promoted to the current, directly usable 18-key deck.
 */
private fun List<TerminalExtraKey>.upgradeShippedDefaultDeck(): List<TerminalExtraKey> =
    if (
        this == TerminalExtraKey.LEGACY_DEFAULT_ORDER ||
        this == TerminalExtraKey.PAGED_DEFAULT_ORDER
    ) {
        TerminalExtraKey.DEFAULT_ORDER
    } else {
        this
    }

private fun KeyboardProfile.supportsCompatibilityRuntime(): Boolean =
    layout == KeyboardLayout.ONE_ROW &&
        modifierBehavior == ModifierBehavior.ONE_SHOT &&
        !hapticFeedbackEnabled &&
        keyRepeatEnabled &&
        inputMode == TerminalInputMode.RAW &&
        tmuxPrefix == KeyboardProfile.DEFAULT_TMUX_PREFIX

private fun TerminalDataRecords.toSettingsLoadResult(
    ids: EphemeralTerminalIdRegistry,
): SettingsLoadResult {
    val unavailableCount = unavailableSecretIds.size
    val warning = when {
        unavailableCount > 0 ->
            "$unavailableCount migrated credential${if (unavailableCount == 1) " is" else "s are"} " +
                "unavailable. Re-enter saved passwords or re-import affected private keys; " +
                "the preserved legacy data was not deleted."
        migrationWarningCodes.isNotEmpty() ->
            "Some legacy records need review after migration; preserved source data was not deleted."
        else -> null
    }
    return SettingsLoadResult(
        settings = toLegacySettings(ids),
        warning = warning,
    )
}

private fun TerminalExtraKey.toKeyboardAction(): KeyboardAction = when (this) {
    TerminalExtraKey.ESC -> KeyboardAction.ESCAPE
    TerminalExtraKey.CTRL -> KeyboardAction.CONTROL
    TerminalExtraKey.ALT -> KeyboardAction.ALT
    TerminalExtraKey.TAB -> KeyboardAction.TAB
    TerminalExtraKey.ENTER -> KeyboardAction.ENTER
    TerminalExtraKey.INSERT -> KeyboardAction.INSERT
    TerminalExtraKey.UP -> KeyboardAction.ARROW_UP
    TerminalExtraKey.DOWN -> KeyboardAction.ARROW_DOWN
    TerminalExtraKey.LEFT -> KeyboardAction.ARROW_LEFT
    TerminalExtraKey.RIGHT -> KeyboardAction.ARROW_RIGHT
    TerminalExtraKey.PAGE_UP -> KeyboardAction.PAGE_UP
    TerminalExtraKey.PAGE_DOWN -> KeyboardAction.PAGE_DOWN
    TerminalExtraKey.HOME -> KeyboardAction.HOME
    TerminalExtraKey.END -> KeyboardAction.END
    TerminalExtraKey.DELETE -> KeyboardAction.DELETE
    TerminalExtraKey.CTRL_C -> KeyboardAction.CTRL_C
    TerminalExtraKey.CTRL_D -> KeyboardAction.CTRL_D
    TerminalExtraKey.CTRL_A -> KeyboardAction.CTRL_A
    TerminalExtraKey.CTRL_B -> KeyboardAction.CTRL_B
    TerminalExtraKey.CTRL_E -> KeyboardAction.CTRL_E
    TerminalExtraKey.CTRL_R -> KeyboardAction.CTRL_R
    TerminalExtraKey.CTRL_W -> KeyboardAction.CTRL_W
    TerminalExtraKey.CTRL_L -> KeyboardAction.CTRL_L
    TerminalExtraKey.CTRL_U -> KeyboardAction.CTRL_U
    TerminalExtraKey.SLASH -> KeyboardAction.SLASH
    TerminalExtraKey.PIPE -> KeyboardAction.PIPE
    TerminalExtraKey.DASH -> KeyboardAction.HYPHEN
    TerminalExtraKey.TILDE -> KeyboardAction.TILDE
    TerminalExtraKey.BACKTICK -> KeyboardAction.BACKTICK
    TerminalExtraKey.BACKSLASH -> KeyboardAction.BACKSLASH
    TerminalExtraKey.AT -> KeyboardAction.AT_SIGN
    TerminalExtraKey.UNDERSCORE -> KeyboardAction.UNDERSCORE
    TerminalExtraKey.F1 -> KeyboardAction.F1
    TerminalExtraKey.F2 -> KeyboardAction.F2
    TerminalExtraKey.F3 -> KeyboardAction.F3
    TerminalExtraKey.F4 -> KeyboardAction.F4
    TerminalExtraKey.F5 -> KeyboardAction.F5
    TerminalExtraKey.F6 -> KeyboardAction.F6
    TerminalExtraKey.F7 -> KeyboardAction.F7
    TerminalExtraKey.F8 -> KeyboardAction.F8
    TerminalExtraKey.F9 -> KeyboardAction.F9
    TerminalExtraKey.F10 -> KeyboardAction.F10
    TerminalExtraKey.F11 -> KeyboardAction.F11
    TerminalExtraKey.F12 -> KeyboardAction.F12
    TerminalExtraKey.HIDE_KEYBOARD -> KeyboardAction.HIDE_KEYBOARD
    TerminalExtraKey.BACKSPACE -> KeyboardAction.BACKSPACE
    TerminalExtraKey.CTRL_Z -> KeyboardAction.CTRL_Z
    TerminalExtraKey.CTRL_K -> KeyboardAction.CTRL_K
    TerminalExtraKey.COLON -> KeyboardAction.COLON
    TerminalExtraKey.SEMICOLON -> KeyboardAction.SEMICOLON
    TerminalExtraKey.HASH -> KeyboardAction.HASH
    TerminalExtraKey.DOLLAR -> KeyboardAction.DOLLAR
    TerminalExtraKey.EQUALS -> KeyboardAction.EQUALS
    TerminalExtraKey.SPACE -> KeyboardAction.SPACE
    TerminalExtraKey.EXCLAMATION -> KeyboardAction.EXCLAMATION
    TerminalExtraKey.QUESTION -> KeyboardAction.QUESTION
    TerminalExtraKey.ASTERISK -> KeyboardAction.ASTERISK
    TerminalExtraKey.PLUS -> KeyboardAction.PLUS
    TerminalExtraKey.PERIOD -> KeyboardAction.PERIOD
    TerminalExtraKey.COMMA -> KeyboardAction.COMMA
    TerminalExtraKey.LEFT_PAREN -> KeyboardAction.LEFT_PAREN
    TerminalExtraKey.RIGHT_PAREN -> KeyboardAction.RIGHT_PAREN
    TerminalExtraKey.LEFT_BRACKET -> KeyboardAction.LEFT_BRACKET
    TerminalExtraKey.RIGHT_BRACKET -> KeyboardAction.RIGHT_BRACKET
    TerminalExtraKey.LEFT_BRACE -> KeyboardAction.LEFT_BRACE
    TerminalExtraKey.RIGHT_BRACE -> KeyboardAction.RIGHT_BRACE
    TerminalExtraKey.SINGLE_QUOTE -> KeyboardAction.SINGLE_QUOTE
    TerminalExtraKey.DOUBLE_QUOTE -> KeyboardAction.DOUBLE_QUOTE
    TerminalExtraKey.LESS_THAN -> KeyboardAction.LESS_THAN
    TerminalExtraKey.GREATER_THAN -> KeyboardAction.GREATER_THAN
    TerminalExtraKey.AMPERSAND -> KeyboardAction.AMPERSAND
    TerminalExtraKey.CARET -> KeyboardAction.CARET
    TerminalExtraKey.PERCENT -> KeyboardAction.PERCENT
}

private fun KeyboardAction.toTerminalExtraKeyOrNull(): TerminalExtraKey? = when (this) {
    KeyboardAction.ESCAPE -> TerminalExtraKey.ESC
    KeyboardAction.CONTROL -> TerminalExtraKey.CTRL
    KeyboardAction.ALT -> TerminalExtraKey.ALT
    KeyboardAction.TAB -> TerminalExtraKey.TAB
    KeyboardAction.ENTER -> TerminalExtraKey.ENTER
    KeyboardAction.INSERT -> TerminalExtraKey.INSERT
    KeyboardAction.ARROW_UP -> TerminalExtraKey.UP
    KeyboardAction.ARROW_DOWN -> TerminalExtraKey.DOWN
    KeyboardAction.ARROW_LEFT -> TerminalExtraKey.LEFT
    KeyboardAction.ARROW_RIGHT -> TerminalExtraKey.RIGHT
    KeyboardAction.PAGE_UP -> TerminalExtraKey.PAGE_UP
    KeyboardAction.PAGE_DOWN -> TerminalExtraKey.PAGE_DOWN
    KeyboardAction.HOME -> TerminalExtraKey.HOME
    KeyboardAction.END -> TerminalExtraKey.END
    KeyboardAction.DELETE -> TerminalExtraKey.DELETE
    KeyboardAction.CTRL_C -> TerminalExtraKey.CTRL_C
    KeyboardAction.CTRL_D -> TerminalExtraKey.CTRL_D
    KeyboardAction.CTRL_A -> TerminalExtraKey.CTRL_A
    KeyboardAction.CTRL_B -> TerminalExtraKey.CTRL_B
    KeyboardAction.CTRL_E -> TerminalExtraKey.CTRL_E
    KeyboardAction.CTRL_R -> TerminalExtraKey.CTRL_R
    KeyboardAction.CTRL_W -> TerminalExtraKey.CTRL_W
    KeyboardAction.CTRL_L -> TerminalExtraKey.CTRL_L
    KeyboardAction.CTRL_U -> TerminalExtraKey.CTRL_U
    KeyboardAction.SLASH -> TerminalExtraKey.SLASH
    KeyboardAction.PIPE -> TerminalExtraKey.PIPE
    KeyboardAction.HYPHEN -> TerminalExtraKey.DASH
    KeyboardAction.TILDE -> TerminalExtraKey.TILDE
    KeyboardAction.BACKTICK -> TerminalExtraKey.BACKTICK
    KeyboardAction.BACKSLASH -> TerminalExtraKey.BACKSLASH
    KeyboardAction.AT_SIGN -> TerminalExtraKey.AT
    KeyboardAction.UNDERSCORE -> TerminalExtraKey.UNDERSCORE
    KeyboardAction.F1 -> TerminalExtraKey.F1
    KeyboardAction.F2 -> TerminalExtraKey.F2
    KeyboardAction.F3 -> TerminalExtraKey.F3
    KeyboardAction.F4 -> TerminalExtraKey.F4
    KeyboardAction.F5 -> TerminalExtraKey.F5
    KeyboardAction.F6 -> TerminalExtraKey.F6
    KeyboardAction.F7 -> TerminalExtraKey.F7
    KeyboardAction.F8 -> TerminalExtraKey.F8
    KeyboardAction.F9 -> TerminalExtraKey.F9
    KeyboardAction.F10 -> TerminalExtraKey.F10
    KeyboardAction.F11 -> TerminalExtraKey.F11
    KeyboardAction.F12 -> TerminalExtraKey.F12
    KeyboardAction.HIDE_KEYBOARD -> TerminalExtraKey.HIDE_KEYBOARD
    KeyboardAction.BACKSPACE -> TerminalExtraKey.BACKSPACE
    KeyboardAction.CTRL_Z -> TerminalExtraKey.CTRL_Z
    KeyboardAction.CTRL_K -> TerminalExtraKey.CTRL_K
    KeyboardAction.COLON -> TerminalExtraKey.COLON
    KeyboardAction.SEMICOLON -> TerminalExtraKey.SEMICOLON
    KeyboardAction.HASH -> TerminalExtraKey.HASH
    KeyboardAction.DOLLAR -> TerminalExtraKey.DOLLAR
    KeyboardAction.EQUALS -> TerminalExtraKey.EQUALS
    KeyboardAction.SPACE -> TerminalExtraKey.SPACE
    KeyboardAction.EXCLAMATION -> TerminalExtraKey.EXCLAMATION
    KeyboardAction.QUESTION -> TerminalExtraKey.QUESTION
    KeyboardAction.ASTERISK -> TerminalExtraKey.ASTERISK
    KeyboardAction.PLUS -> TerminalExtraKey.PLUS
    KeyboardAction.PERIOD -> TerminalExtraKey.PERIOD
    KeyboardAction.COMMA -> TerminalExtraKey.COMMA
    KeyboardAction.LEFT_PAREN -> TerminalExtraKey.LEFT_PAREN
    KeyboardAction.RIGHT_PAREN -> TerminalExtraKey.RIGHT_PAREN
    KeyboardAction.LEFT_BRACKET -> TerminalExtraKey.LEFT_BRACKET
    KeyboardAction.RIGHT_BRACKET -> TerminalExtraKey.RIGHT_BRACKET
    KeyboardAction.LEFT_BRACE -> TerminalExtraKey.LEFT_BRACE
    KeyboardAction.RIGHT_BRACE -> TerminalExtraKey.RIGHT_BRACE
    KeyboardAction.SINGLE_QUOTE -> TerminalExtraKey.SINGLE_QUOTE
    KeyboardAction.DOUBLE_QUOTE -> TerminalExtraKey.DOUBLE_QUOTE
    KeyboardAction.LESS_THAN -> TerminalExtraKey.LESS_THAN
    KeyboardAction.GREATER_THAN -> TerminalExtraKey.GREATER_THAN
    KeyboardAction.AMPERSAND -> TerminalExtraKey.AMPERSAND
    KeyboardAction.CARET -> TerminalExtraKey.CARET
    KeyboardAction.PERCENT -> TerminalExtraKey.PERCENT
    KeyboardAction.SHIFT,
    KeyboardAction.TMUX_PREFIX,
    KeyboardAction.PASTE,
    KeyboardAction.SNIPPETS,
    KeyboardAction.KEYBOARD_SETTINGS,
    -> null
}
