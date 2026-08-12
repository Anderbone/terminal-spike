package com.yanjiyu.terminalspike.core.data.settings

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import org.junit.Assert.assertArrayEquals
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppSettingsSerializerTest {
    @Test
    fun defaultsAreSafeAndVersioned() {
        val defaults = AppSettingsSerializer.defaultValue

        assertEquals(CURRENT_APP_SETTINGS_SCHEMA, defaults.schemaRevision)
        assertEquals(AppSettings.ThemeMode.THEME_MODE_SYSTEM, defaults.themeMode)
        assertEquals(AppSettings.AppLockMode.APP_LOCK_MODE_OFF, defaults.appLockMode)
        assertEquals(AppSettings.Osc52Policy.OSC52_POLICY_DISABLED, defaults.osc52Policy)
        assertEquals("df558cdb-05fb-50f3-baf9-e7dd6e911ce5", defaults.defaultTerminalProfileId)
        assertEquals("f23f85fd-3122-5f8b-b28a-d0320f402866", defaults.defaultKeyboardProfileId)
        assertEquals(CURRENT_KEYBOARD_DECK_REVISION, defaults.keyboardDeckRevision)
        assertTrue(defaults.notificationPrivacyEnabled)
        assertFalse(defaults.notificationPermissionEducationConsumed)
        assertTrue(defaults.multilinePasteConfirmationEnabled)
        assertFalse(defaults.reconnectEnabled)
        assertFalse(defaults.keepCpuAwake)
        assertSame(defaults, AppSettingsValidator.requireValidCurrent(defaults))
    }

    @Test
    fun roundTripPreservesTypedValues() = runTest {
        val expected = AppSettingsSerializer.defaultValue.toBuilder()
            .setThemeMode(AppSettings.ThemeMode.THEME_MODE_DARK)
            .setDynamicColorEnabled(true)
            .setDefaultTerminalProfileId("028c9d14-3645-4a36-af4d-86cdcccb52f0")
            .setReconnectEnabled(true)
            .setAppLockMode(AppSettings.AppLockMode.APP_LOCK_MODE_DELAYED)
            .setAppLockDelaySeconds(60)
            .build()
        val bytes = ByteArrayOutputStream().also { output ->
            AppSettingsSerializer.writeTo(expected, output)
        }.toByteArray()

        val actual = AppSettingsSerializer.readFrom(ByteArrayInputStream(bytes))

        assertEquals(expected, actual)
    }

    @Test
    fun optionalStringSettingsMayBeCleared() = runTest {
        val expected = AppSettingsSerializer.defaultValue.toBuilder()
            .clearAccentPreset()
            .clearDefaultTerminalProfileId()
            .clearDefaultKeyboardProfileId()
            .clearLastBackupMode()
            .build()
        val bytes = ByteArrayOutputStream().also { output ->
            AppSettingsSerializer.writeTo(expected, output)
        }.toByteArray()

        assertEquals(
            expected,
            AppSettingsSerializer.readFrom(ByteArrayInputStream(bytes)),
        )
    }

    @Test(expected = UnsupportedAppSettingsVersionException::class)
    fun newerSchemaIsRejectedWithoutReplacingItWithDefaults() = runTest {
        val newer = AppSettingsSerializer.defaultValue.toBuilder()
            .setSchemaRevision(CURRENT_APP_SETTINGS_SCHEMA + 1)
            .build()

        AppSettingsSerializer.readFrom(ByteArrayInputStream(newer.toByteArray()))
    }

    @Test
    fun parseableSchemaOneWithUnspecifiedEnumsIsRejectedWithTypedFieldCodes() = runTest {
        val invalid = AppSettingsSerializer.defaultValue.toBuilder()
            .setThemeMode(AppSettings.ThemeMode.THEME_MODE_UNSPECIFIED)
            .setAppLockMode(AppSettings.AppLockMode.APP_LOCK_MODE_UNSPECIFIED)
            .setOsc52Policy(AppSettings.Osc52Policy.OSC52_POLICY_UNSPECIFIED)
            .build()

        val error = expectInvalid {
            AppSettingsSerializer.readFrom(ByteArrayInputStream(invalid.toByteArray()))
        }

        assertEquals(
            setOf(
                AppSettingsViolation.THEME_MODE,
                AppSettingsViolation.APP_LOCK_MODE,
                AppSettingsViolation.OSC52_POLICY,
            ),
            error.violations,
        )
    }

    @Test
    fun parseableSchemaOneWithUnknownEnumsIsRejected() = runTest {
        val invalid = AppSettingsSerializer.defaultValue.toBuilder()
            .setThemeModeValue(91)
            .setAppLockModeValue(92)
            .setOsc52PolicyValue(93)
            .build()

        val error = expectInvalid {
            AppSettingsSerializer.readFrom(ByteArrayInputStream(invalid.toByteArray()))
        }

        assertEquals(
            setOf(
                AppSettingsViolation.THEME_MODE,
                AppSettingsViolation.APP_LOCK_MODE,
                AppSettingsViolation.OSC52_POLICY,
            ),
            error.violations,
        )
    }

    @Test
    fun schemaOneRejectsNoncanonicalIdsUnsafeNumbersAndInvalidIdentifiers() = runTest {
        val invalid = AppSettingsSerializer.defaultValue.toBuilder()
            .setAccentPreset("mint theme")
            .setDefaultTerminalProfileId(
                AppSettingsSerializer.defaultValue.defaultTerminalProfileId.uppercase(Locale.ROOT),
            )
            .setDefaultKeyboardProfileId("f23f85fd31225f8bb28ad0320f402866")
            .setKeepaliveIntervalSeconds(4)
            .setReconnectMaxAttempts(101)
            .setAppLockDelaySeconds(86_401)
            .setSensitiveClipboardClearSeconds(-1)
            .setLastBackupMode("portable")
            .build()

        val error = expectInvalid {
            AppSettingsSerializer.readFrom(ByteArrayInputStream(invalid.toByteArray()))
        }

        assertEquals(
            setOf(
                AppSettingsViolation.ACCENT_PRESET,
                AppSettingsViolation.DEFAULT_TERMINAL_PROFILE_ID,
                AppSettingsViolation.DEFAULT_KEYBOARD_PROFILE_ID,
                AppSettingsViolation.KEEPALIVE_INTERVAL,
                AppSettingsViolation.RECONNECT_MAX_ATTEMPTS,
                AppSettingsViolation.APP_LOCK_DELAY,
                AppSettingsViolation.SENSITIVE_CLIPBOARD_CLEAR_DELAY,
                AppSettingsViolation.LAST_BACKUP_MODE,
            ),
            error.violations,
        )
    }

    @Test
    fun serializerDoesNotEmitSemanticallyInvalidSettings() = runTest {
        val output = ByteArrayOutputStream()
        val invalid = AppSettingsSerializer.defaultValue.toBuilder()
            .setLastBackupMode("portable")
            .build()

        val error = expectInvalid { AppSettingsSerializer.writeTo(invalid, output) }

        assertEquals(setOf(AppSettingsViolation.LAST_BACKUP_MODE), error.violations)
        assertArrayEquals(byteArrayOf(), output.toByteArray())
    }

    @Test
    fun revisionOneMigrationIsIdempotentAndAppliesSafeDefaults() = runTest {
        val revisionZero = AppSettings.newBuilder()
            .setAccentPreset("blue")
            .setReconnectEnabled(true)
            .build()

        assertTrue(AppSettingsV1Migration.shouldMigrate(revisionZero))
        val migrated = AppSettingsV1Migration.migrate(revisionZero)

        assertEquals(CURRENT_APP_SETTINGS_SCHEMA, migrated.schemaRevision)
        assertEquals("blue", migrated.accentPreset)
        assertTrue(migrated.reconnectEnabled)
        assertEquals(AppSettings.ThemeMode.THEME_MODE_SYSTEM, migrated.themeMode)
        assertTrue(migrated.notificationPrivacyEnabled)
        assertEquals(0, migrated.keyboardDeckRevision)
        assertFalse(AppSettingsV1Migration.shouldMigrate(migrated))
        assertEquals(migrated, AppSettingsV1Migration.migrate(migrated))
    }

    @Test
    fun migrationRejectsInvalidValuesInsteadOfCommittingARevisionOnePayload() = runTest {
        val invalidRevisionZero = AppSettings.newBuilder()
            .setAccentPreset("invalid preset")
            .setReconnectMaxAttempts(101)
            .build()

        val error = expectInvalid { AppSettingsV1Migration.migrate(invalidRevisionZero) }

        assertEquals(
            setOf(AppSettingsViolation.ACCENT_PRESET, AppSettingsViolation.RECONNECT_MAX_ATTEMPTS),
            error.violations,
        )
    }

    @Test
    fun migrationValidatesAlreadyCurrentPayloads() = runTest {
        val invalidCurrent = AppSettingsSerializer.defaultValue.toBuilder()
            .setLastBackupMode("unknown")
            .build()

        val error = expectInvalid { AppSettingsV1Migration.migrate(invalidCurrent) }

        assertEquals(setOf(AppSettingsViolation.LAST_BACKUP_MODE), error.violations)
    }

    private suspend fun expectInvalid(block: suspend () -> Unit): InvalidAppSettingsException = try {
        block()
        fail("Expected invalid app settings to be rejected.")
        throw AssertionError("unreachable")
    } catch (error: InvalidAppSettingsException) {
        error
    }
}
