package com.yanjiyu.terminalspike.core.data.settings

import androidx.datastore.core.DataMigration

/** Idempotent semantic upgrade for any pre-revision settings payload encountered in development. */
internal object AppSettingsV1Migration : DataMigration<AppSettings> {
    override suspend fun shouldMigrate(currentData: AppSettings): Boolean {
        val schemaRevision = Integer.toUnsignedLong(currentData.schemaRevision)
        if (schemaRevision > CURRENT_APP_SETTINGS_SCHEMA.toLong()) {
            throw UnsupportedAppSettingsVersionException(schemaRevision)
        }
        if (currentData.schemaRevision == CURRENT_APP_SETTINGS_SCHEMA) {
            AppSettingsValidator.requireValidCurrent(currentData)
        }
        return currentData.schemaRevision == 0
    }

    override suspend fun migrate(currentData: AppSettings): AppSettings {
        if (!shouldMigrate(currentData)) return AppSettingsValidator.requireValidCurrent(currentData)
        val migrated = AppSettingsSerializer.defaultValue.toBuilder()
            .mergeFrom(currentData)
            .setSchemaRevision(CURRENT_APP_SETTINGS_SCHEMA)
            // A DataStore-only migration cannot prove that the paired Room repair ran.
            .setKeyboardDeckRevision(0)
            .build()
        return AppSettingsValidator.requireValidCurrent(migrated)
    }

    override suspend fun cleanUp() = Unit
}
