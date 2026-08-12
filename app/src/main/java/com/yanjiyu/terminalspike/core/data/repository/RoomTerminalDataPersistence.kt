package com.yanjiyu.terminalspike.core.data.repository

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.yanjiyu.terminalspike.core.data.credential.ClearAllSavedCredentialsResult
import com.yanjiyu.terminalspike.core.data.credential.CredentialEpochClock
import com.yanjiyu.terminalspike.core.data.credential.EncryptedSecretState
import com.yanjiyu.terminalspike.core.data.credential.RoomCredentialAggregateStore
import com.yanjiyu.terminalspike.core.data.credential.SavedCredentialClearPreview
import com.yanjiyu.terminalspike.core.data.db.AppDatabase
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsRepository
import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshKeyIdentity
import com.yanjiyu.terminalspike.core.security.credential.CredentialId
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretKind
import com.yanjiyu.terminalspike.core.security.credential.CredentialSecretReference
import com.yanjiyu.terminalspike.core.security.credential.CredentialStore
import com.yanjiyu.terminalspike.core.security.credential.SecretId
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.first

/** Room/DataStore implementation; all public access remains gated by [TerminalDataRepository]. */
internal class RoomTerminalDataPersistence(
    private val database: AppDatabase,
    private val appSettings: AppSettingsRepository,
    private val hosts: HostProfileRepository,
    private val credentials: SshCredentialRepository,
    private val identities: SshKeyIdentityRepository,
    private val terminalProfiles: TerminalProfileRepository,
    private val customTerminalThemes: CustomTerminalThemeRepository =
        CustomTerminalThemeRepository(database.customTerminalThemeDao()),
    private val keyboardProfiles: KeyboardProfileRepository,
    private val snippets: SnippetRepository,
    private val credentialMutations: RoomCredentialAggregateStore,
    private val credentialStore: CredentialStore,
    private val clock: CredentialEpochClock = CredentialEpochClock.SYSTEM,
    /** Test seam proving the credential transaction rolls back when host metadata cannot commit. */
    private val beforeHostCredentialMetadataWrite: suspend () -> Unit = {},
) : TerminalDataPersistence {
    private val hostMutations = RoomTerminalToolsMutations(
        database = database,
        credentialMutations = credentialMutations,
        credentialStore = credentialStore,
        clock = clock,
        afterMutationStep = { checkpoint ->
            if (checkpoint == RoomTerminalToolsMutationCheckpoint.CREDENTIAL_COMMITTED) {
                beforeHostCredentialMetadataWrite()
            }
        },
    )

    override suspend fun readRecords(): TerminalDataRecords {
        val defaults = appSettings.settings.first()
        val terminalProfileSnapshot = terminalProfiles.observeAll().first()
        val defaultTerminal = terminalProfileSnapshot.firstOrNull { profile ->
            profile.id == defaults.defaultTerminalProfileId
        } ?: terminalProfileSnapshot.firstOrNull()
            ?: error("At least one terminal profile is required.")
        val keyboardProfileSnapshot = keyboardProfiles.observeAll().first()
        val defaultKeyboard = keyboardProfileSnapshot.firstOrNull { profile ->
            profile.id == defaults.defaultKeyboardProfileId
        }
            ?: keyboardProfileSnapshot.firstOrNull()
            ?: error("At least one keyboard profile is required.")
        val credentialRecords = database.credentialRecordDao()
        check(
            credentialRecords.countSecretsWithUnknownState(
                readyStateCode = EncryptedSecretState.READY.wireCode,
                unavailableStateCode = EncryptedSecretState.LEGACY_UNAVAILABLE.wireCode,
            ) == 0,
        ) { "An encrypted credential has an unsupported persistence state." }
        val unavailableSecretIds = credentialRecords.findSecretIdsByState(
            EncryptedSecretState.LEGACY_UNAVAILABLE.wireCode,
        ).toSet()
        val migrationWarningCodes = database.legacyMigrationDao().observeStates().first()
            .flatMap { state ->
                state.warningCodes.split(';').filter(String::isNotBlank)
            }
            .distinct()
            .sorted()
        return TerminalDataRecords(
            hosts = hosts.observeAll().first(),
            credentials = credentials.observeAll().first(),
            identities = identities.observeAll().first(),
            snippets = snippets.observeAll().first(),
            defaultTerminalProfileId = defaultTerminal.id,
            defaultKeyboardProfile = defaultKeyboard,
            terminalProfiles = terminalProfileSnapshot,
            customTerminalThemes = customTerminalThemes.observeAll().first(),
            keyboardProfiles = keyboardProfileSnapshot,
            unavailableSecretIds = unavailableSecretIds,
            migrationWarningCodes = migrationWarningCodes,
        )
    }

    override suspend fun upsertHost(
        profile: com.yanjiyu.terminalspike.core.model.HostProfile,
        retainPassword: Boolean,
    ) = hostMutations.upsertHost(profile)

    override suspend fun upsertHostWithCredentialMetadata(
        profile: com.yanjiyu.terminalspike.core.model.HostProfile,
        expectedCredentialId: String?,
        credential: com.yanjiyu.terminalspike.core.model.SshCredential?,
    ) = hostMutations.upsertHostWithCredentialMetadata(
        profile = profile,
        expectedCredentialId = expectedCredentialId,
        credential = credential,
    )

    override suspend fun upsertHostWithPassword(
        profile: com.yanjiyu.terminalspike.core.model.HostProfile,
        expectedCredentialId: String?,
        credentialId: String,
        secretId: String,
        password: ByteArray,
    ) = hostMutations.upsertHostWithPassword(
        profile = profile,
        expectedCredentialId = expectedCredentialId,
        credentialId = credentialId,
        secretId = secretId,
        password = password,
    )

    override suspend fun deleteHostAndUnreferencedPassword(hostId: String): Boolean =
        hostMutations.deleteHostAndUnreferencedCredential(hostId)

    override suspend fun savePassword(
        hostId: String,
        expectedCredentialId: String?,
        credentialId: String,
        secretId: String,
        password: ByteArray,
    ) {
        hostMutations.savePassword(
            hostId = hostId,
            expectedCredentialId = expectedCredentialId,
            credentialId = credentialId,
            secretId = secretId,
            password = password,
        )
    }

    override suspend fun clearPassword(hostId: String): Boolean =
        hostMutations.clearPassword(hostId)

    override suspend fun importIdentity(identity: SshKeyIdentity, privateKey: ByteArray) {
        identities.saveWithPrivateKey(identity, privateKey)
    }

    override suspend fun updateIdentityMetadata(identity: SshKeyIdentity): Boolean =
        identities.updateMetadata(identity)

    override suspend fun deleteIdentity(identityId: String): Boolean =
        identities.delete(identityId).metadataDeleted

    override suspend fun upsertSnippet(snippet: com.yanjiyu.terminalspike.core.model.Snippet) {
        if (snippets.get(snippet.id) == null) {
            snippets.insert(snippet)
        } else {
            check(snippets.update(snippet)) { "Snippet disappeared during update." }
        }
    }

    override suspend fun deleteSnippet(snippetId: String): Boolean = snippets.delete(snippetId)

    override suspend fun replaceDefaultKeyboardActions(actions: List<KeyboardAction>) {
        val defaultId = appSettings.settings.first().defaultKeyboardProfileId
        val existing = keyboardProfiles.get(defaultId)
            ?: error("The default keyboard profile is missing.")
        val now = checkedNow()
        check(
            keyboardProfiles.update(
                existing.copy(
                    orderedActions = actions,
                    updatedAtEpochMillis = maxOf(now, existing.updatedAtEpochMillis),
                ),
            ),
        ) { "The default keyboard profile disappeared during update." }
    }

    override suspend fun copyPassword(hostId: String): ByteArray {
        val host = hosts.get(hostId) ?: error("Host profile is missing.")
        val credentialId = host.credentialId ?: error("Host profile has no saved password.")
        val credential = credentials.get(credentialId) ?: error("Saved password metadata is missing.")
        val secretId = (credential.authentication as? SshAuthentication.Password)
            ?.secretReferenceId ?: error("Host credential is not a saved password.")
        val reference = CredentialSecretReference(
            credentialId = CredentialId.parseCanonical(credential.id),
            secretId = SecretId.parseCanonical(secretId),
            kind = CredentialSecretKind.PASSWORD,
        )
        return credentialStore.withSecret(reference, ByteArray::copyOf)
    }

    override suspend fun copyPrivateKey(identityId: String): ByteArray {
        val identity = identities.get(identityId) ?: error("SSH identity is missing.")
        val reference = CredentialSecretReference(
            credentialId = CredentialId.parseCanonical(identity.id),
            secretId = SecretId.parseCanonical(identity.privateKeySecretReferenceId),
            kind = CredentialSecretKind.PRIVATE_KEY,
        )
        return credentialStore.withSecret(reference, ByteArray::copyOf)
    }

    override suspend fun copyCredentialSecret(credentialId: String): ByteArray {
        val credential = credentials.get(credentialId) ?: error("SSH credential is missing.")
        val (secretId, kind) = when (val authentication = credential.authentication) {
            is SshAuthentication.Password ->
                authentication.secretReferenceId to CredentialSecretKind.PASSWORD
            is SshAuthentication.PrivateKey ->
                authentication.passphraseSecretReferenceId to CredentialSecretKind.KEY_PASSPHRASE
            is SshAuthentication.KeyboardInteractive ->
                authentication.reusableResponseSecretReferenceId to
                    CredentialSecretKind.KEYBOARD_INTERACTIVE
        }
        val requiredSecretId = secretId ?: error("SSH credential has no saved secret.")
        val reference = CredentialSecretReference(
            credentialId = CredentialId.parseCanonical(credential.id),
            secretId = SecretId.parseCanonical(requiredSecretId),
            kind = kind,
        )
        return credentialStore.withSecret(reference, ByteArray::copyOf)
    }

    override suspend fun previewClearAllSavedCredentials(): SavedCredentialClearPreview =
        credentialMutations.previewClearAllSavedCredentials()

    override suspend fun clearAllSavedCredentials(): ClearAllSavedCredentialsResult =
        credentialMutations.clearAllSavedCredentials()

    private fun checkedNow(): Long = clock.nowEpochMillis().also { now ->
        require(now in 0..253_402_300_799_999L) { "Timestamp is outside the supported range." }
    }
}

/** Stateless JSch parser. It never retains the caller's private-key bytes. */
internal object JschPrivateKeyMetadataInspector : PrivateKeyMetadataInspector {
    override fun inspect(privateKey: ByteArray): ImportedPrivateKeyMetadata {
        require(privateKey.isNotEmpty()) { "The private-key document is empty." }
        val workingCopy = privateKey.copyOf()
        val keyPair = try {
            KeyPair.load(JSch(), workingCopy, null)
        } finally {
            workingCopy.fill(0)
        }
        return try {
            val publicKey = requireNotNull(keyPair.publicKeyBlob?.copyOf()) {
                "The private key has no public-key payload."
            }
            require(publicKey.isNotEmpty()) { "The private key has an empty public-key payload." }
            val algorithm = keyPair.keyTypeString
            require(algorithm.isNotBlank()) { "The private-key algorithm is unavailable." }
            val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(publicKey),
            )
            ImportedPrivateKeyMetadata(
                algorithm = algorithm,
                fingerprintSha256 = fingerprint,
                openSshPublicKey = "$algorithm ${Base64.getEncoder().encodeToString(publicKey)}",
                isPassphraseProtected = keyPair.isEncrypted,
            )
        } finally {
            keyPair.dispose()
        }
    }
}
