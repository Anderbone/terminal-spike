package com.yanjiyu.terminalspike.core.data.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow

class AppSettingsRepository internal constructor(
    private val store: DataStore<AppSettings>,
) {
    val settings: Flow<AppSettings> = store.data

    suspend fun update(transform: (AppSettings.Builder) -> Unit): AppSettings =
        store.updateData { current ->
            val updated = current.toBuilder()
                .also(transform)
                .setSchemaRevision(CURRENT_APP_SETTINGS_SCHEMA)
                .build()
            AppSettingsValidator.requireValidCurrent(updated)
        }

    companion object {
        const val FILE_NAME = "app_settings.pb"

        internal fun create(
            context: Context,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            migrations: List<DataMigration<AppSettings>> = listOf(AppSettingsV1Migration),
        ): AppSettingsRepository = AppSettingsRepository(
            DataStoreFactory.create(
                serializer = AppSettingsSerializer,
                migrations = migrations,
                scope = scope,
                produceFile = { context.filesDir.resolve("datastore/$FILE_NAME") },
            ),
        )
    }
}
