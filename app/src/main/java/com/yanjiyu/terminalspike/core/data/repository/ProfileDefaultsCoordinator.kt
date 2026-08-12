package com.yanjiyu.terminalspike.core.data.repository

import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileDao
import com.yanjiyu.terminalspike.core.data.db.TerminalProfileDao
import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SelectDefaultProfileResult {
    data class Selected(val profileId: String) : SelectDefaultProfileResult

    data class ProfileNotFound(val profileId: String) : SelectDefaultProfileResult
}

sealed interface DeleteProfileResult {
    data class Deleted(
        val profileId: String,
        /** Non-null when deletion first moved an active default to this profile. */
        val replacementDefaultId: String?,
    ) : DeleteProfileResult

    data class ProfileNotFound(val profileId: String) : DeleteProfileResult

    data class ReplacementRequired(val profileId: String) : DeleteProfileResult

    data class InvalidReplacement(val profileId: String) : DeleteProfileResult

    data class ReplacementNotFound(val profileId: String) : DeleteProfileResult
}

/**
 * Process-singleton consistency boundary for terminal and keyboard defaults.
 *
 * The application container must own one instance. Every selection and deletion shares [operations]
 * so no in-process operation can validate against a default another operation is concurrently
 * changing. Active-default deletion commits its replacement to DataStore before deleting from
 * Room; a crash or Room failure therefore leaves a resolvable default rather than a dangling one.
 */
class ProfileDefaultsCoordinator internal constructor(
    private val settings: AppSettingsRepository,
    private val terminalProfiles: TerminalProfileDao,
    private val keyboardProfiles: KeyboardProfileDao,
    private val operations: Mutex = Mutex(),
) {
    suspend fun selectTerminalDefault(profileId: String): SelectDefaultProfileResult =
        operations.withLock {
            val profile = terminalProfiles.findById(profileId)
                ?: return@withLock SelectDefaultProfileResult.ProfileNotFound(profileId)
            profile.toDomainModel()
            settings.update { it.setDefaultTerminalProfileId(profileId) }
            SelectDefaultProfileResult.Selected(profileId)
        }

    suspend fun selectKeyboardDefault(profileId: String): SelectDefaultProfileResult =
        operations.withLock {
            val profile = keyboardProfiles.findWithKeys(profileId)
                ?: return@withLock SelectDefaultProfileResult.ProfileNotFound(profileId)
            profile.toDomainModel()
            settings.update { it.setDefaultKeyboardProfileId(profileId) }
            SelectDefaultProfileResult.Selected(profileId)
        }

    suspend fun deleteTerminalProfile(
        profileId: String,
        replacementDefaultId: String? = null,
    ): DeleteProfileResult = operations.withLock {
        val current = settings.settings.first()
        if (terminalProfiles.findById(profileId) == null) {
            return@withLock DeleteProfileResult.ProfileNotFound(profileId)
        }
        val replacement = when (val decision = replacementForActiveDefault(
            profileId = profileId,
            activeDefaultId = current.defaultTerminalProfileId,
            replacementDefaultId = replacementDefaultId,
            validateReplacement = { candidateId ->
                terminalProfiles.findById(candidateId)?.toDomainModel() != null
            },
            persistReplacement = { candidateId ->
                settings.update { it.setDefaultTerminalProfileId(candidateId) }
            },
        )) {
            DefaultReplacement.NotNeeded -> null
            is DefaultReplacement.Persisted -> decision.profileId
            is DefaultReplacement.Rejected -> return@withLock decision.result
        }

        requireSingleDelete(terminalProfiles.deleteById(profileId), profileId)
        DeleteProfileResult.Deleted(profileId, replacement)
    }

    suspend fun deleteKeyboardProfile(
        profileId: String,
        replacementDefaultId: String? = null,
    ): DeleteProfileResult = operations.withLock {
        val current = settings.settings.first()
        if (keyboardProfiles.findWithKeys(profileId) == null) {
            return@withLock DeleteProfileResult.ProfileNotFound(profileId)
        }
        val replacement = when (val decision = replacementForActiveDefault(
            profileId = profileId,
            activeDefaultId = current.defaultKeyboardProfileId,
            replacementDefaultId = replacementDefaultId,
            validateReplacement = { candidateId ->
                keyboardProfiles.findWithKeys(candidateId)?.toDomainModel() != null
            },
            persistReplacement = { candidateId ->
                settings.update { it.setDefaultKeyboardProfileId(candidateId) }
            },
        )) {
            DefaultReplacement.NotNeeded -> null
            is DefaultReplacement.Persisted -> decision.profileId
            is DefaultReplacement.Rejected -> return@withLock decision.result
        }

        requireSingleDelete(keyboardProfiles.deleteById(profileId), profileId)
        DeleteProfileResult.Deleted(profileId, replacement)
    }

    private suspend fun replacementForActiveDefault(
        profileId: String,
        activeDefaultId: String,
        replacementDefaultId: String?,
        validateReplacement: suspend (String) -> Boolean,
        persistReplacement: suspend (String) -> AppSettings,
    ): DefaultReplacement {
        if (profileId != activeDefaultId) return DefaultReplacement.NotNeeded
        if (replacementDefaultId == null) {
            return DefaultReplacement.Rejected(DeleteProfileResult.ReplacementRequired(profileId))
        }
        if (replacementDefaultId == profileId) {
            return DefaultReplacement.Rejected(DeleteProfileResult.InvalidReplacement(profileId))
        }
        if (!validateReplacement(replacementDefaultId)) {
            return DefaultReplacement.Rejected(
                DeleteProfileResult.ReplacementNotFound(replacementDefaultId),
            )
        }
        persistReplacement(replacementDefaultId)
        return DefaultReplacement.Persisted(replacementDefaultId)
    }

    private fun requireSingleDelete(deletedRows: Int, profileId: String) {
        check(deletedRows == 1) {
            "Profile '$profileId' disappeared while its default-safe deletion was in progress."
        }
    }

    private sealed interface DefaultReplacement {
        data object NotNeeded : DefaultReplacement

        data class Persisted(val profileId: String) : DefaultReplacement

        data class Rejected(val result: DeleteProfileResult) : DefaultReplacement
    }
}
