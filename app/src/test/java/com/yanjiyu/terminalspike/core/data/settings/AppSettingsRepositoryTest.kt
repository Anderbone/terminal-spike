package com.yanjiyu.terminalspike.core.data.settings

import androidx.datastore.core.DataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppSettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun invalidPersistedSchemaOneIsReportedWithoutReplacingSourceBytes() = runTest {
        val invalid = AppSettingsSerializer.defaultValue.toBuilder()
            .setThemeModeValue(404)
            .build()
        val originalBytes = invalid.toByteArray()
        val file = settingsFile("invalid-read.pb", originalBytes)
        val repository = repository(file)

        val error = expectInvalid { repository.settings.first() }

        assertEquals(setOf(AppSettingsViolation.THEME_MODE), error.violations)
        assertArrayEquals(originalBytes, file.readBytes())
    }

    @Test
    fun invalidMigrationOutputLeavesRevisionZeroSourceBytesUntouched() = runTest {
        val invalidRevisionZero = AppSettings.newBuilder()
            .setDefaultTerminalProfileId("NOT-A-CANONICAL-UUID")
            .build()
        val originalBytes = invalidRevisionZero.toByteArray()
        val file = settingsFile("invalid-migration.pb", originalBytes)
        val repository = repository(file, migrations = listOf(AppSettingsV1Migration))

        val error = expectInvalid { repository.settings.first() }

        assertEquals(setOf(AppSettingsViolation.DEFAULT_TERMINAL_PROFILE_ID), error.violations)
        assertArrayEquals(originalBytes, file.readBytes())
    }

    @Test
    fun invalidRepositoryUpdateLeavesLastValidPayloadUntouched() = runTest {
        val original = AppSettingsSerializer.defaultValue
        val originalBytes = original.toByteArray()
        val file = settingsFile("invalid-update.pb", originalBytes)
        val repository = repository(file)
        assertEquals(original, repository.settings.first())

        val error = expectInvalid {
            repository.update { builder ->
                builder.setSchemaRevision(CURRENT_APP_SETTINGS_SCHEMA + 1)
                builder.setKeepaliveIntervalSeconds(1)
                builder.setLastBackupMode("invalid")
            }
        }

        assertEquals(
            setOf(AppSettingsViolation.KEEPALIVE_INTERVAL, AppSettingsViolation.LAST_BACKUP_MODE),
            error.violations,
        )
        assertArrayEquals(originalBytes, file.readBytes())
        assertEquals(original, repository.settings.first())
    }

    @Test
    fun repositoryOwnsSchemaRevisionDuringValidUpdates() = runTest {
        val file = settingsFile("valid-update.pb", AppSettingsSerializer.defaultValue.toByteArray())
        val repository = repository(file)

        val updated = repository.update { builder ->
            builder.setSchemaRevision(Int.MAX_VALUE)
            builder.setThemeMode(AppSettings.ThemeMode.THEME_MODE_DARK)
        }

        assertEquals(CURRENT_APP_SETTINGS_SCHEMA, updated.schemaRevision)
        assertEquals(AppSettings.ThemeMode.THEME_MODE_DARK, updated.themeMode)
        assertEquals(updated, AppSettings.parseFrom(file.readBytes()))
    }

    private fun kotlinx.coroutines.test.TestScope.repository(
        file: File,
        migrations: List<androidx.datastore.core.DataMigration<AppSettings>> = emptyList(),
    ): AppSettingsRepository = AppSettingsRepository(
        DataStoreFactory.create(
            serializer = AppSettingsSerializer,
            migrations = migrations,
            scope = backgroundScope,
            produceFile = { file },
        ),
    )

    private fun settingsFile(name: String, bytes: ByteArray): File =
        temporaryFolder.newFile(name).also { file -> file.writeBytes(bytes) }

    private suspend fun expectInvalid(block: suspend () -> Unit): InvalidAppSettingsException = try {
        block()
        fail("Expected invalid app settings to be rejected.")
        throw AssertionError("unreachable")
    } catch (error: InvalidAppSettingsException) {
        error
    }
}
