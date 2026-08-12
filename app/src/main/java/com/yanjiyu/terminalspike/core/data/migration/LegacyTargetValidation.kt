package com.yanjiyu.terminalspike.core.data.migration

import com.yanjiyu.terminalspike.core.data.credential.validateHeader
import com.yanjiyu.terminalspike.core.data.db.KeyboardProfileWithKeys
import com.yanjiyu.terminalspike.core.data.db.LegacyMigrationBatch
import com.yanjiyu.terminalspike.core.data.repository.toDomainModel

/**
 * Proves that a translated settings aggregate can cross every target persistence boundary before
 * its transaction is allowed to record completion. Legacy input remains untouched on failure.
 */
internal fun LegacyMigrationBatch.requireUserSettingsTargetCompatibility(): LegacyMigrationBatch =
    requireTargetCompatibility("Legacy user settings") {
        terminalProfiles.forEach { it.toDomainModel() }

        val keyboardProfileIds = keyboardProfiles.map { it.id }.toSet()
        require(keyboardProfileIds.size == keyboardProfiles.size) {
            "Keyboard profile IDs must be unique."
        }
        require(keyboardKeys.all { it.profileId in keyboardProfileIds }) {
            "Every keyboard key must reference a migrated keyboard profile."
        }
        val keysByProfile = keyboardKeys.groupBy { it.profileId }
        keyboardProfiles.forEach { profile ->
            KeyboardProfileWithKeys(
                profile = profile,
                keys = keysByProfile[profile.id].orEmpty().sortedBy { it.position },
            ).toDomainModel()
        }

        secrets.forEach { it.validateHeader() }
        keyIdentities.forEach { it.toDomainModel() }
        credentials.forEach { it.toDomainModel() }
        hosts.forEach { it.toDomainModel() }
        snippets.forEach { it.toDomainModel() }
    }

/** Validates parser output against the same mapper used by the known-host repository. */
internal fun LegacyMigrationBatch.requireKnownHostsTargetCompatibility(): LegacyMigrationBatch =
    requireTargetCompatibility("Legacy known hosts") {
        knownHosts.forEach { it.toDomainModel() }
    }

private inline fun LegacyMigrationBatch.requireTargetCompatibility(
    sourceName: String,
    validate: LegacyMigrationBatch.() -> Unit,
): LegacyMigrationBatch {
    try {
        validate()
    } catch (failure: Exception) {
        throw IllegalArgumentException("$sourceName cannot be represented by the target data model.", failure)
    }
    return this
}
