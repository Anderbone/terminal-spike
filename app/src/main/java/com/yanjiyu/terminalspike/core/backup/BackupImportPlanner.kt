package com.yanjiyu.terminalspike.core.backup

import com.yanjiyu.terminalspike.core.model.HostProfile
import com.yanjiyu.terminalspike.core.model.KnownHost
import com.yanjiyu.terminalspike.core.model.SshAuthentication
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import java.util.UUID

enum class BackupImportStrategy {
    MERGE,
    REPLACE_CORRESPONDING,
    KEEP_BOTH,
}

enum class BackupImportRecordKind {
    HOST,
    CREDENTIAL,
    SSH_KEY,
    KNOWN_HOST,
    SNIPPET,
    TERMINAL_PROFILE,
    TERMINAL_THEME,
    KEYBOARD_PROFILE,
    GLOBAL_SETTINGS,
}

enum class BackupImportIncompatibleReason {
    CUSTOM_TERMINAL_THEME_UNSUPPORTED,
    TERMINAL_PROFILE_USES_UNSUPPORTED_THEME,
    TERMINAL_PROFILE_USES_OMITTED_CUSTOM_FONT,
    HOST_USES_SKIPPED_TERMINAL_PROFILE,
    DEFAULT_USES_SKIPPED_TERMINAL_PROFILE,
}

/** A decoded record that this app version deliberately will not persist. */
data class BackupImportIncompatibleRecord(
    val recordId: String,
    val kind: BackupImportRecordKind,
    val reason: BackupImportIncompatibleReason,
)

data class BackupImportConflict(
    val recordId: String,
    val existingKind: BackupImportRecordKind,
    val incomingKind: BackupImportRecordKind,
    /** Differs from [recordId] for a natural known-host key conflict. */
    val existingRecordId: String = recordId,
)

sealed class BackupImportPlanException(message: String) : Exception(message) {
    class UnresolvedReference(
        val ownerId: String,
        val targetId: String,
        val kind: BackupReferenceKind,
    ) : BackupImportPlanException(
        "Import reference $ownerId -> $targetId (${kind.name.lowercase()}) cannot be resolved.",
    )

    class ExhaustedIdentifiers : BackupImportPlanException(
        "Import could not allocate a unique record identifier.",
    )

    class IncompatibleRecordKind(
        val recordId: String,
        val existingKind: BackupImportRecordKind,
        val incomingKind: BackupImportRecordKind,
    ) : BackupImportPlanException(
        "Import record $recordId cannot replace ${existingKind.name.lowercase()} with " +
            "${incomingKind.name.lowercase()}.",
    )

    class IncompatibleSecretOwner(
        val secretId: String,
        val existingOwnerId: String,
        val incomingOwnerId: String,
    ) : BackupImportPlanException(
        "Import secret $secretId belongs to a different record or secret purpose.",
    )
}

/**
 * Pure, validated import decision. It consumes the incoming snapshot's portable-secret ownership;
 * callers must close this plan and must not separately close/use the incoming snapshot afterward.
 */
class BackupImportPlan internal constructor(
    val strategy: BackupImportStrategy,
    val snapshotToApply: BackupPayloadSnapshot,
    val conflicts: List<BackupImportConflict>,
    val skippedIncomingRecordIds: Set<String>,
    val recordIdRewrites: Map<String, String>,
    val secretIdRewrites: Map<String, String>,
    /** Records intentionally omitted because this app version has no compatible persistence model. */
    val incompatibleIncomingRecords: List<BackupImportIncompatibleRecord>,
    /** Existing trust rows removed before replacing the same endpoint/algorithm under another ID. */
    val knownHostRecordIdsToDelete: Set<String>,
    val externallyResolvedReferences: List<BackupUnresolvedReference>,
    /** Only Replace changes global settings and therefore requires an internal recovery snapshot. */
    val applyGlobalSettings: Boolean,
    val requiresRecoverySnapshot: Boolean,
) : AutoCloseable {
    override fun close() = snapshotToApply.close()
}

fun interface BackupUuidGenerator {
    fun nextCanonicalUuid(): String

    companion object {
        val RANDOM = BackupUuidGenerator { UUID.randomUUID().toString() }
    }
}

/** Conflict detection plus complete Keep-both graph rewriting, with no repository mutations. */
class BackupImportPlanner(
    private val uuidGenerator: BackupUuidGenerator = BackupUuidGenerator.RANDOM,
) {
    fun plan(
        current: BackupPayloadSnapshot,
        incoming: BackupPayloadSnapshot,
        strategy: BackupImportStrategy,
    ): BackupImportPlan = plan(
        current = current,
        incoming = incoming,
        strategy = strategy,
        source = BackupImportSource.EXTERNAL_ARCHIVE,
    )

    /**
     * Plans a same-device recovery snapshot captured directly from this app's persistence model.
     *
     * External archives need compatibility projection when the exporter did not opt into carrying
     * a referenced custom font file. An internal recovery marker already represents the exact
     * pre-import Room references, however, so projecting it would discard the original profiles and
     * their dependent hosts during exact rollback.
     */
    internal fun planInternalRecovery(
        current: BackupPayloadSnapshot,
        incoming: BackupPayloadSnapshot,
    ): BackupImportPlan = plan(
        current = current,
        incoming = incoming,
        strategy = BackupImportStrategy.REPLACE_CORRESPONDING,
        source = BackupImportSource.INTERNAL_RECOVERY,
    )

    private fun plan(
        current: BackupPayloadSnapshot,
        incoming: BackupPayloadSnapshot,
        strategy: BackupImportStrategy,
        source: BackupImportSource,
    ): BackupImportPlan {
        try {
            if (source == BackupImportSource.INTERNAL_RECOVERY) {
                require(incoming.mode == BackupMode.FULL) {
                    "An internal recovery plan requires a Full same-device snapshot."
                }
            }
            val compatibility = when (source) {
                BackupImportSource.EXTERNAL_ARCHIVE -> projectSupportedRecords(incoming)
                BackupImportSource.INTERNAL_RECOVERY -> SupportedImportProjection(
                    snapshot = incoming,
                    incompatibleRecords = emptyList(),
                    skippedRecordIds = emptySet(),
                )
            }
            val supportedIncoming = compatibility.snapshot
            val currentKinds = current.recordKinds()
            val incomingKinds = supportedIncoming.recordKinds()
            val idConflicts = incomingKinds.entries
                .mapNotNull { (id, incomingKind) ->
                    currentKinds[id]?.let { existingKind ->
                        BackupImportConflict(id, existingKind, incomingKind)
                    }
                }
            idConflicts.firstOrNull { it.existingKind != it.incomingKind }?.let { conflict ->
                throw BackupImportPlanException.IncompatibleRecordKind(
                    recordId = conflict.recordId,
                    existingKind = conflict.existingKind,
                    incomingKind = conflict.incomingKind,
                )
            }
            val currentKnownHosts = current.knownHosts.associateBy { it.naturalTrustKey() }
            val naturalKnownHostConflicts = supportedIncoming.knownHosts.mapNotNull { incomingKnownHost ->
                currentKnownHosts[incomingKnownHost.naturalTrustKey()]
                    ?.takeIf { existing -> existing.id != incomingKnownHost.id }
                    ?.let { existing ->
                        BackupImportConflict(
                            recordId = incomingKnownHost.id,
                            existingKind = BackupImportRecordKind.KNOWN_HOST,
                            incomingKind = BackupImportRecordKind.KNOWN_HOST,
                            existingRecordId = existing.id,
                        )
                    }
            }
            val conflicts = (
                idConflicts +
                    naturalKnownHostConflicts +
                    BackupImportConflict(
                        BackupPayloadFormat.GLOBAL_SETTINGS_RECORD_ID,
                        BackupImportRecordKind.GLOBAL_SETTINGS,
                        BackupImportRecordKind.GLOBAL_SETTINGS,
                    )
                ).distinct().sortedWith(
                compareBy<BackupImportConflict>(
                    { it.recordId },
                    { it.existingRecordId },
                    { it.incomingKind.name },
                ),
            )

            return when (strategy) {
                BackupImportStrategy.MERGE -> merge(current, supportedIncoming, conflicts, compatibility)
                BackupImportStrategy.REPLACE_CORRESPONDING ->
                    replace(current, supportedIncoming, conflicts, compatibility)
                BackupImportStrategy.KEEP_BOTH ->
                    keepBoth(current, supportedIncoming, conflicts, compatibility)
            }
        } catch (error: Throwable) {
            incoming.close()
            throw error
        }
    }

    private fun merge(
        current: BackupPayloadSnapshot,
        incoming: BackupPayloadSnapshot,
        conflicts: List<BackupImportConflict>,
        compatibility: SupportedImportProjection,
    ): BackupImportPlan {
        val existingIds = current.recordKinds().keys
        val skipped = conflicts.asSequence()
            .filter { it.incomingKind != BackupImportRecordKind.GLOBAL_SETTINGS }
            .mapTo(linkedSetOf(), BackupImportConflict::recordId)
            .apply { addAll(compatibility.skippedRecordIds) }

        val acceptedCredentialSources = incoming.credentials.filter { record ->
            (record.metadata.id !in skipped).also { accepted ->
                if (!accepted) record.portableSecret?.wipe()
            }
        }
        val acceptedKeySources = incoming.sshKeys.filter { record ->
            (record.metadata.id !in skipped).also { accepted ->
                if (!accepted) record.portablePrivateKey?.wipe()
            }
        }
        val existingSecretIds = current.secretReferenceIds()
        val usedSecretIds = (existingSecretIds + incoming.secretReferenceIds()).toMutableSet()
        val secretRewrites = linkedMapOf<String, String>()
        acceptedCredentialSources.mapNotNull { it.secretReferenceId }.forEach { secretId ->
            if (secretId in existingSecretIds) {
                secretRewrites.getOrPut(secretId) { allocate(usedSecretIds) }
            }
        }
        acceptedKeySources.map { it.metadata.privateKeySecretReferenceId }.forEach { secretId ->
            if (secretId in existingSecretIds) {
                secretRewrites.getOrPut(secretId) { allocate(usedSecretIds) }
            }
        }
        val acceptedCredentials = acceptedCredentialSources.map { record ->
            BackupCredentialRecord(
                metadata = record.metadata.copy(
                    authentication = record.metadata.authentication.rewrite(emptyMap(), secretRewrites),
                ),
                portableSecret = record.portableSecret,
            )
        }
        val acceptedKeys = acceptedKeySources.map { record ->
            BackupSshKeyRecord(
                metadata = record.metadata.copy(
                    privateKeySecretReferenceId = secretRewrites[
                        record.metadata.privateKeySecretReferenceId
                    ] ?: record.metadata.privateKeySecretReferenceId,
                ),
                portablePrivateKey = record.portablePrivateKey,
            )
        }
        val snapshot = BackupPayloadSnapshot(
            mode = incoming.mode,
            hostProfiles = incoming.hostProfiles.filterNot { it.id in skipped },
            credentials = acceptedCredentials,
            sshKeys = acceptedKeys,
            knownHosts = incoming.knownHosts.filterNot { it.id in skipped },
            snippets = incoming.snippets.filterNot { it.id in skipped },
            terminalProfiles = incoming.terminalProfiles.filterNot { it.id in skipped },
            terminalThemes = incoming.terminalThemes.filterNot { it.id in skipped },
            keyboardProfiles = incoming.keyboardProfiles.filterNot { it.id in skipped },
            customFonts = incoming.customFonts.filter { font ->
                incoming.terminalProfiles.any { profile ->
                    profile.id !in skipped && profile.fontId == font.fontId
                }
            },
            globalSettings = incoming.globalSettings,
        )
        val external = validateExternalReferences(snapshot, existingIds)
        return BackupImportPlan(
            strategy = BackupImportStrategy.MERGE,
            snapshotToApply = snapshot,
            conflicts = conflicts,
            skippedIncomingRecordIds = skipped,
            recordIdRewrites = emptyMap(),
            secretIdRewrites = secretRewrites,
            incompatibleIncomingRecords = compatibility.incompatibleRecords,
            knownHostRecordIdsToDelete = emptySet(),
            externallyResolvedReferences = external,
            applyGlobalSettings = false,
            requiresRecoverySnapshot = false,
        )
    }

    private fun replace(
        current: BackupPayloadSnapshot,
        incoming: BackupPayloadSnapshot,
        conflicts: List<BackupImportConflict>,
        compatibility: SupportedImportProjection,
    ): BackupImportPlan {
        val currentSecretOwners = current.secretOwners()
        incoming.secretOwners().forEach { (secretId, incomingOwner) ->
            val existingOwner = currentSecretOwners[secretId] ?: return@forEach
            if (existingOwner != incomingOwner) {
                throw BackupImportPlanException.IncompatibleSecretOwner(
                    secretId = secretId,
                    existingOwnerId = existingOwner.recordId,
                    incomingOwnerId = incomingOwner.recordId,
                )
            }
        }
        val allowedExternalIds = compatibility.incompatibleRecords.asSequence()
            .filter { it.reason == BackupImportIncompatibleReason.DEFAULT_USES_SKIPPED_TERMINAL_PROFILE }
            .mapNotNull {
                current.globalSettings.defaultTerminalProfileId?.takeIf { id ->
                    current.terminalProfiles.any { profile -> profile.id == id }
                }
            }
            .toSet()
        val external = incoming.unresolvedReferences.onEach { reference ->
            if (reference.targetId !in allowedExternalIds) throw reference.toPlanException()
        }
        return BackupImportPlan(
            strategy = BackupImportStrategy.REPLACE_CORRESPONDING,
            snapshotToApply = incoming,
            conflicts = conflicts,
            skippedIncomingRecordIds = compatibility.skippedRecordIds,
            recordIdRewrites = emptyMap(),
            secretIdRewrites = emptyMap(),
            incompatibleIncomingRecords = compatibility.incompatibleRecords,
            knownHostRecordIdsToDelete = conflicts.asSequence()
                .filter { conflict ->
                    conflict.incomingKind == BackupImportRecordKind.KNOWN_HOST &&
                        conflict.existingKind == BackupImportRecordKind.KNOWN_HOST &&
                        conflict.existingRecordId != conflict.recordId
                }
                .mapTo(linkedSetOf(), BackupImportConflict::existingRecordId),
            externallyResolvedReferences = external,
            applyGlobalSettings = true,
            requiresRecoverySnapshot = true,
        )
    }

    private fun keepBoth(
        current: BackupPayloadSnapshot,
        incoming: BackupPayloadSnapshot,
        conflicts: List<BackupImportConflict>,
        compatibility: SupportedImportProjection,
    ): BackupImportPlan {
        val usedRecordIds = (
            current.recordKinds().keys +
                incoming.recordKinds().keys +
                BackupPayloadFormat.GLOBAL_SETTINGS_RECORD_ID
            ).toMutableSet()
        val naturalKnownHostConflictIds = conflicts.asSequence()
            .filter { conflict ->
                conflict.incomingKind == BackupImportRecordKind.KNOWN_HOST &&
                    conflict.existingKind == BackupImportRecordKind.KNOWN_HOST &&
                    conflict.existingRecordId != conflict.recordId
            }
            .mapTo(linkedSetOf(), BackupImportConflict::recordId)
        val idRewrites = conflicts.asSequence()
            .filter { conflict ->
                conflict.incomingKind != BackupImportRecordKind.GLOBAL_SETTINGS &&
                    conflict.existingRecordId == conflict.recordId &&
                    conflict.recordId !in naturalKnownHostConflictIds
            }
            .map(BackupImportConflict::recordId)
            .distinct()
            .sorted()
            .associateWithTo(linkedMapOf()) { allocate(usedRecordIds) }

        val usedSecretIds = buildSet {
            addAll(current.secretReferenceIds())
            addAll(incoming.secretReferenceIds())
        }.toMutableSet()
        val existingSecretIds = current.secretReferenceIds()
        val secretRewrites = linkedMapOf<String, String>()
        incoming.credentials.forEach { record ->
            record.secretReferenceId?.let { secretId ->
                if (record.metadata.id in idRewrites || secretId in existingSecretIds) {
                    secretRewrites.getOrPut(secretId) { allocate(usedSecretIds) }
                }
            }
        }
        incoming.sshKeys.forEach { record ->
            val secretId = record.metadata.privateKeySecretReferenceId
            if (record.metadata.id in idRewrites || secretId in existingSecretIds) {
                secretRewrites.getOrPut(secretId) { allocate(usedSecretIds) }
            }
        }

        val mapped = BackupPayloadSnapshot(
            mode = incoming.mode,
            hostProfiles = incoming.hostProfiles.map { it.rewrite(idRewrites) },
            credentials = incoming.credentials.map { record ->
                BackupCredentialRecord(
                    metadata = record.metadata.copy(
                        id = idRewrites[record.metadata.id] ?: record.metadata.id,
                        authentication = record.metadata.authentication.rewrite(idRewrites, secretRewrites),
                    ),
                    portableSecret = record.portableSecret,
                )
            },
            sshKeys = incoming.sshKeys.map { record ->
                BackupSshKeyRecord(
                    metadata = record.metadata.copy(
                        id = idRewrites[record.metadata.id] ?: record.metadata.id,
                        privateKeySecretReferenceId = secretRewrites[
                            record.metadata.privateKeySecretReferenceId
                        ] ?: record.metadata.privateKeySecretReferenceId,
                    ),
                    portablePrivateKey = record.portablePrivateKey,
                )
            },
            knownHosts = incoming.knownHosts.filterNot { knownHost ->
                knownHost.id in naturalKnownHostConflictIds
            }.map { knownHost ->
                knownHost.copy(id = idRewrites[knownHost.id] ?: knownHost.id)
            },
            snippets = incoming.snippets.map { snippet ->
                snippet.copy(id = idRewrites[snippet.id] ?: snippet.id)
            },
            terminalProfiles = incoming.terminalProfiles.map { profile ->
                profile.copy(
                    id = idRewrites[profile.id] ?: profile.id,
                    themeId = idRewrites[profile.themeId] ?: profile.themeId,
                )
            },
            terminalThemes = incoming.terminalThemes.map { theme ->
                BackupTerminalTheme(
                    id = idRewrites[theme.id] ?: theme.id,
                    name = theme.name,
                    foregroundArgb = theme.foregroundArgb,
                    backgroundArgb = theme.backgroundArgb,
                    cursorArgb = theme.cursorArgb,
                    selectionArgb = theme.selectionArgb,
                    ansi16Argb = theme.ansi16Argb,
                    createdAtEpochMillis = theme.createdAtEpochMillis,
                    updatedAtEpochMillis = theme.updatedAtEpochMillis,
                    boldUsesBrightColours = theme.boldUsesBrightColours,
                )
            },
            keyboardProfiles = incoming.keyboardProfiles.map { profile ->
                profile.copy(id = idRewrites[profile.id] ?: profile.id)
            },
            customFonts = incoming.customFonts,
            globalSettings = incoming.globalSettings.copy(
                defaultTerminalProfileId = incoming.globalSettings.defaultTerminalProfileId?.let { id ->
                    idRewrites[id] ?: id
                },
                defaultKeyboardProfileId = incoming.globalSettings.defaultKeyboardProfileId?.let { id ->
                    idRewrites[id] ?: id
                },
            ),
        )
        val external = validateExternalReferences(mapped, current.recordKinds().keys)
        return BackupImportPlan(
            strategy = BackupImportStrategy.KEEP_BOTH,
            snapshotToApply = mapped,
            conflicts = conflicts,
            skippedIncomingRecordIds = naturalKnownHostConflictIds + compatibility.skippedRecordIds,
            recordIdRewrites = idRewrites,
            secretIdRewrites = secretRewrites,
            incompatibleIncomingRecords = compatibility.incompatibleRecords,
            knownHostRecordIdsToDelete = emptySet(),
            externallyResolvedReferences = external,
            applyGlobalSettings = false,
            requiresRecoverySnapshot = false,
        )
    }

    private fun validateExternalReferences(
        snapshot: BackupPayloadSnapshot,
        existingIds: Set<String>,
    ): List<BackupUnresolvedReference> = snapshot.unresolvedReferences.onEach { reference ->
        if (reference.targetId !in existingIds) throw reference.toPlanException()
    }

    private fun allocate(used: MutableSet<String>): String {
        repeat(MAX_UUID_ATTEMPTS) {
            val candidate = uuidGenerator.nextCanonicalUuid()
            val canonical = runCatching { UUID.fromString(candidate).toString() }.getOrNull()
            if (candidate.length == 36 && canonical == candidate && used.add(candidate)) return candidate
        }
        throw BackupImportPlanException.ExhaustedIdentifiers()
    }

    /** External archives activate a custom font only when its validated byte record is present. */
    private fun projectSupportedRecords(
        incoming: BackupPayloadSnapshot,
    ): SupportedImportProjection {
        val includedFontIds = incoming.customFonts.mapTo(hashSetOf()) { it.fontId }
        val needsProjection = incoming.terminalProfiles.any { profile ->
            profile.fontId.startsWith(CUSTOM_FONT_ID_PREFIX) && profile.fontId !in includedFontIds
        }
        val referencedIncludedFonts = incoming.terminalProfiles.mapTo(hashSetOf()) { it.fontId }
            .intersect(includedFontIds)
        if (!needsProjection && referencedIncludedFonts.size == incoming.customFonts.size) {
            return SupportedImportProjection(incoming, emptyList(), emptySet())
        }
        val supported = BackupPayloadSnapshot(
            mode = incoming.mode,
            hostProfiles = incoming.hostProfiles,
            credentials = incoming.credentials,
            sshKeys = incoming.sshKeys,
            knownHosts = incoming.knownHosts,
            snippets = incoming.snippets,
            terminalProfiles = incoming.terminalProfiles.map { profile ->
                if (
                    profile.fontId.startsWith(CUSTOM_FONT_ID_PREFIX) &&
                    profile.fontId !in includedFontIds
                ) {
                    profile.copy(fontId = TerminalRendererProfile.SYSTEM_MONOSPACE_FONT_ID)
                } else {
                    profile
                }
            },
            terminalThemes = incoming.terminalThemes,
            keyboardProfiles = incoming.keyboardProfiles,
            customFonts = incoming.customFonts.filter { it.fontId in referencedIncludedFonts },
            globalSettings = incoming.globalSettings,
        )
        return SupportedImportProjection(
            snapshot = supported,
            incompatibleRecords = emptyList(),
            skippedRecordIds = emptySet(),
        )
    }

    private companion object {
        const val MAX_UUID_ATTEMPTS = 100
        const val CUSTOM_FONT_ID_PREFIX = "custom_"
    }
}

private enum class BackupImportSource {
    EXTERNAL_ARCHIVE,
    INTERNAL_RECOVERY,
}

private data class SupportedImportProjection(
    val snapshot: BackupPayloadSnapshot,
    val incompatibleRecords: List<BackupImportIncompatibleRecord>,
    val skippedRecordIds: Set<String>,
)

private fun BackupPayloadSnapshot.recordKinds(): Map<String, BackupImportRecordKind> = buildMap {
    hostProfiles.forEach { put(it.id, BackupImportRecordKind.HOST) }
    credentials.forEach { put(it.metadata.id, BackupImportRecordKind.CREDENTIAL) }
    sshKeys.forEach { put(it.metadata.id, BackupImportRecordKind.SSH_KEY) }
    knownHosts.forEach { put(it.id, BackupImportRecordKind.KNOWN_HOST) }
    snippets.forEach { put(it.id, BackupImportRecordKind.SNIPPET) }
    terminalProfiles.forEach { put(it.id, BackupImportRecordKind.TERMINAL_PROFILE) }
    terminalThemes.forEach { put(it.id, BackupImportRecordKind.TERMINAL_THEME) }
    keyboardProfiles.forEach { put(it.id, BackupImportRecordKind.KEYBOARD_PROFILE) }
}

private fun BackupPayloadSnapshot.secretReferenceIds(): Set<String> = buildSet {
    credentials.mapNotNullTo(this) { it.secretReferenceId }
    sshKeys.mapTo(this) { it.metadata.privateKeySecretReferenceId }
}

private data class BackupSecretOwner(
    val recordId: String,
    val recordKind: BackupImportRecordKind,
    val secretKind: String,
)

private fun BackupPayloadSnapshot.secretOwners(): Map<String, BackupSecretOwner> = buildMap {
    credentials.forEach { record ->
        record.secretReferenceId?.let { secretId ->
            put(
                secretId,
                BackupSecretOwner(
                    recordId = record.metadata.id,
                    recordKind = BackupImportRecordKind.CREDENTIAL,
                    secretKind = record.metadata.authentication.secretKindCode(),
                ),
            )
        }
    }
    sshKeys.forEach { record ->
        put(
            record.metadata.privateKeySecretReferenceId,
            BackupSecretOwner(
                recordId = record.metadata.id,
                recordKind = BackupImportRecordKind.SSH_KEY,
                secretKind = "private_key",
            ),
        )
    }
}

private fun SshAuthentication.secretKindCode(): String = when (this) {
    is SshAuthentication.Password -> "password"
    is SshAuthentication.PrivateKey -> "key_passphrase"
    is SshAuthentication.KeyboardInteractive -> "keyboard_interactive"
}

private fun KnownHost.naturalTrustKey(): Triple<String, Int, String> =
    Triple(host, port, keyAlgorithm)

private fun HostProfile.rewrite(ids: Map<String, String>): HostProfile = copy(
    id = ids[id] ?: id,
    credentialId = credentialId?.let { ids[it] ?: it },
    terminalProfileId = terminalProfileId?.let { ids[it] ?: it },
    keyboardProfileId = keyboardProfileId?.let { ids[it] ?: it },
)

private fun SshAuthentication.rewrite(
    ids: Map<String, String>,
    secrets: Map<String, String>,
): SshAuthentication = when (this) {
    is SshAuthentication.Password -> copy(
        secretReferenceId = secretReferenceId?.let { secrets[it] ?: it },
    )
    is SshAuthentication.PrivateKey -> copy(
        keyIdentityId = ids[keyIdentityId] ?: keyIdentityId,
        passphraseSecretReferenceId = passphraseSecretReferenceId?.let { secrets[it] ?: it },
    )
    is SshAuthentication.KeyboardInteractive -> copy(
        reusableResponseSecretReferenceId = reusableResponseSecretReferenceId?.let { secrets[it] ?: it },
    )
}

private fun BackupUnresolvedReference.toPlanException() = BackupImportPlanException.UnresolvedReference(
    ownerId = ownerId,
    targetId = targetId,
    kind = kind,
)
