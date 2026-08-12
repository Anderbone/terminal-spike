package com.yanjiyu.terminalspike.core.security

import android.util.Log
import com.yanjiyu.terminalspike.BuildConfig

/** Stable, non-sensitive event identifiers suitable for release diagnostics. */
enum class AppLogEvent(val code: String) {
    SETTINGS_READ_FAILED("settings_read_failed"),
    SETTINGS_WRITE_FAILED("settings_write_failed"),
    DATABASE_OPEN_FAILED("database_open_failed"),
    LEGACY_MIGRATION_BLOCKED("legacy_migration_blocked"),
    CREDENTIAL_UNAVAILABLE("credential_unavailable"),
    CONNECTION_FAILED("connection_failed"),
    SESSION_SERVICE_FAILED("session_service_failed"),
    BACKUP_FAILED("backup_failed"),
}

fun interface AppLogSink {
    fun write(priority: Int, tag: String, message: String)
}

/**
 * Central logging policy. Release builds never include arbitrary diagnostic text or exception
 * messages. Debug diagnostics are bounded and redacted before reaching Logcat.
 */
class AppLogger(
    private val diagnosticsEnabled: Boolean = BuildConfig.DEBUG,
    private val sink: AppLogSink = AppLogSink { priority, tag, message ->
        Log.println(priority, tag, message)
    },
) {
    fun debug(event: AppLogEvent, detail: (() -> String)? = null) {
        if (diagnosticsEnabled) write(Log.DEBUG, event, detail, null)
    }

    fun warning(
        event: AppLogEvent,
        error: Throwable? = null,
        detail: (() -> String)? = null,
    ) {
        write(Log.WARN, event, detail, error)
    }

    fun error(
        event: AppLogEvent,
        error: Throwable? = null,
        detail: (() -> String)? = null,
    ) {
        write(Log.ERROR, event, detail, error)
    }

    private fun write(
        priority: Int,
        event: AppLogEvent,
        detail: (() -> String)?,
        error: Throwable?,
    ) {
        val message = buildString {
            append(event.code)
            if (diagnosticsEnabled) {
                error?.let {
                    append(" exception=")
                    append(it.javaClass.simpleName.ifBlank { "Throwable" })
                }
                detail?.invoke()?.takeIf(String::isNotBlank)?.let {
                    append(" detail=")
                    append(SensitiveLogRedactor.redact(it))
                }
            }
        }
        sink.write(priority, TAG, message)
    }

    private companion object {
        const val TAG = "TerminalSpike"
    }
}
