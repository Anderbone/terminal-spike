package com.yanjiyu.terminalspike.core.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

internal const val CURRENT_APP_SETTINGS_SCHEMA = 1
internal const val CURRENT_KEYBOARD_DECK_REVISION = 1

internal object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings.newBuilder()
        .setSchemaRevision(CURRENT_APP_SETTINGS_SCHEMA)
        .setThemeMode(AppSettings.ThemeMode.THEME_MODE_SYSTEM)
        .setAccentPreset("mint")
        .setDefaultTerminalProfileId("df558cdb-05fb-50f3-baf9-e7dd6e911ce5")
        .setDefaultKeyboardProfileId("f23f85fd-3122-5f8b-b28a-d0320f402866")
        .setKeepaliveIntervalSeconds(30)
        .setReconnectMaxAttempts(5)
        .setNotificationPrivacyEnabled(true)
        .setAppLockMode(AppSettings.AppLockMode.APP_LOCK_MODE_OFF)
        .setOsc52Policy(AppSettings.Osc52Policy.OSC52_POLICY_DISABLED)
        .setMultilinePasteConfirmationEnabled(true)
        .setLastBackupMode("standard")
        .setKeyboardDeckRevision(CURRENT_KEYBOARD_DECK_REVISION)
        .build()
        .let(AppSettingsValidator::requireValidCurrent)

    override suspend fun readFrom(input: InputStream): AppSettings {
        val settings = try {
            AppSettings.parseFrom(input)
        } catch (error: InvalidProtocolBufferException) {
            throw CorruptionException("App settings are not valid protocol-buffer data.", error)
        }
        val schemaRevision = Integer.toUnsignedLong(settings.schemaRevision)
        if (schemaRevision > CURRENT_APP_SETTINGS_SCHEMA.toLong()) {
            throw UnsupportedAppSettingsVersionException(schemaRevision)
        }
        return if (settings.schemaRevision == CURRENT_APP_SETTINGS_SCHEMA) {
            AppSettingsValidator.requireValidCurrent(settings)
        } else {
            settings
        }
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        require(t.schemaRevision == CURRENT_APP_SETTINGS_SCHEMA) {
            "Refusing to write unsupported app-settings schema ${t.schemaRevision}."
        }
        AppSettingsValidator.requireValidCurrent(t).writeTo(output)
    }
}

internal class UnsupportedAppSettingsVersionException(
    val schemaRevision: Long,
) : IllegalStateException(
    "App settings schema $schemaRevision is newer than supported schema $CURRENT_APP_SETTINGS_SCHEMA.",
)
