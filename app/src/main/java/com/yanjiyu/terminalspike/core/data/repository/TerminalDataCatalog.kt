package com.yanjiyu.terminalspike.core.data.repository

import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.CustomTerminalTheme
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.Snippet
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.core.model.SshCredential
import com.yanjiyu.terminalspike.core.model.SshKeyIdentity
import com.yanjiyu.terminalspike.core.model.SshKeyOrigin
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.settings.SettingsLoadResult

/** Whether optional encrypted material exists without ever loading its bytes. */
internal enum class CatalogSecretAvailability {
    NOT_CONFIGURED,
    AVAILABLE,
    UNAVAILABLE,
}

internal data class CatalogHostProfile(
    val presentationId: Long,
    val profile: HostProfile,
)

internal data class CatalogSshCredential(
    val id: String,
    val displayName: String,
    /** Domain metadata contains opaque references only; secret payloads live in CredentialStore. */
    val authentication: SshAuthentication,
    val savedSecretAvailability: CatalogSecretAvailability,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/** Public identity metadata. The encrypted private-key reference deliberately stays internal. */
internal data class CatalogSshKeyIdentity(
    val presentationId: Long,
    val id: String,
    val name: String,
    val algorithm: String,
    val publicKeyFingerprint: String,
    val publicKey: String?,
    val origin: SshKeyOrigin,
    val isPassphraseProtected: Boolean,
    val privateKeyAvailability: CatalogSecretAvailability,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val comment: String?,
)

internal data class CatalogSnippet(
    val presentationId: Long,
    val snippet: Snippet,
)

/**
 * One immutable, non-secret view of the same authoritative records used for compatibility state.
 *
 * UUID/presentation-ID pairs live on hosts, identities, and snippets so both directions remain
 * stable for the lifetime of the repository without serialising the presentation IDs.
 */
internal data class TerminalDataCatalog(
    val hosts: List<CatalogHostProfile>,
    val credentials: List<CatalogSshCredential>,
    val identities: List<CatalogSshKeyIdentity>,
    val snippets: List<CatalogSnippet>,
    val terminalProfiles: List<TerminalProfile>,
    val customTerminalThemes: List<CustomTerminalTheme> = emptyList(),
    val keyboardProfiles: List<KeyboardProfile>,
    val defaultTerminalProfileId: String,
    val defaultKeyboardProfileId: String,
    val unavailableSecretCount: Int,
    val migrationWarningCodes: List<String>,
) {
    fun hostPresentationId(persistentId: String): Long? =
        hosts.firstOrNull { it.profile.id == persistentId }?.presentationId

    fun persistentHostId(presentationId: Long): String? =
        hosts.firstOrNull { it.presentationId == presentationId }?.profile?.id

    fun identityPresentationId(persistentId: String): Long? =
        identities.firstOrNull { it.id == persistentId }?.presentationId

    fun persistentIdentityId(presentationId: Long): String? =
        identities.firstOrNull { it.presentationId == presentationId }?.id

    fun snippetPresentationId(persistentId: String): Long? =
        snippets.firstOrNull { it.snippet.id == persistentId }?.presentationId

    fun persistentSnippetId(presentationId: Long): String? =
        snippets.firstOrNull { it.presentationId == presentationId }?.snippet?.id
}

/** Atomic result: both views were derived from one [TerminalDataRecords] instance under the gate. */
internal data class TerminalDataCatalogLoadResult(
    val compatibility: SettingsLoadResult,
    val catalog: TerminalDataCatalog?,
)

internal fun TerminalDataRecords.toCatalog(
    ids: EphemeralTerminalIdRegistry,
): TerminalDataCatalog = TerminalDataCatalog(
    hosts = hosts.map { profile ->
        CatalogHostProfile(
            presentationId = ids.presentationId(TerminalRecordKind.HOST, profile.id),
            profile = profile.copy(),
        )
    },
    credentials = credentials.map { credential -> credential.toCatalog(unavailableSecretIds) },
    identities = identities.map { identity ->
        identity.toCatalog(
            presentationId = ids.presentationId(TerminalRecordKind.IDENTITY, identity.id),
            unavailableSecretIds = unavailableSecretIds,
        )
    },
    snippets = snippets.map { snippet ->
        CatalogSnippet(
            presentationId = ids.presentationId(TerminalRecordKind.SNIPPET, snippet.id),
            snippet = snippet.copy(),
        )
    },
    terminalProfiles = terminalProfiles.map { profile -> profile.copy() },
    customTerminalThemes = customTerminalThemes.map { theme ->
        theme.copy(ansi16Argb = theme.ansi16Argb.toList())
    },
    keyboardProfiles = keyboardProfiles.map { profile ->
        profile.copy(orderedActions = profile.orderedActions.toList())
    },
    defaultTerminalProfileId = defaultTerminalProfileId,
    defaultKeyboardProfileId = defaultKeyboardProfile.id,
    unavailableSecretCount = unavailableSecretIds.size,
    migrationWarningCodes = migrationWarningCodes.toList(),
)

private fun SshCredential.toCatalog(
    unavailableSecretIds: Set<String>,
): CatalogSshCredential = CatalogSshCredential(
    id = id,
    displayName = displayName,
    authentication = authentication,
    savedSecretAvailability = authentication.savedSecretReferenceId()
        .availability(unavailableSecretIds),
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun SshAuthentication.savedSecretReferenceId(): String? = when (this) {
    is SshAuthentication.Password -> secretReferenceId
    is SshAuthentication.PrivateKey -> passphraseSecretReferenceId
    is SshAuthentication.KeyboardInteractive -> reusableResponseSecretReferenceId
}

private fun SshKeyIdentity.toCatalog(
    presentationId: Long,
    unavailableSecretIds: Set<String>,
): CatalogSshKeyIdentity = CatalogSshKeyIdentity(
    presentationId = presentationId,
    id = id,
    name = name,
    algorithm = algorithm,
    publicKeyFingerprint = publicKeyFingerprint,
    publicKey = publicKey,
    origin = origin,
    isPassphraseProtected = isPassphraseProtected,
    privateKeyAvailability = privateKeySecretReferenceId.availability(unavailableSecretIds),
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    comment = comment,
)

private fun String?.availability(
    unavailableSecretIds: Set<String>,
): CatalogSecretAvailability = when {
    this == null -> CatalogSecretAvailability.NOT_CONFIGURED
    this in unavailableSecretIds -> CatalogSecretAvailability.UNAVAILABLE
    else -> CatalogSecretAvailability.AVAILABLE
}
