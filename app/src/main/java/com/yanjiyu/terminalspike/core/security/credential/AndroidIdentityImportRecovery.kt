package com.yanjiyu.terminalspike.core.security.credential

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri

internal class PreferencesIdentityImportRecoveryStore(
    private val preferences: () -> SharedPreferences,
) : IdentityImportRecoveryStore {
    override fun read(): IdentityImportRecoveryState = preferences().let { values ->
        IdentityImportRecoveryState(
            incomplete = values.getBoolean("incomplete", false),
            needsReselection = values.getBoolean("needs-reselection", false),
        )
    }

    override fun write(state: IdentityImportRecoveryState) {
        check(
            preferences().edit()
                .putBoolean("incomplete", state.incomplete)
                .putBoolean("needs-reselection", state.needsReselection)
                .commit(),
        ) { "Identity import recovery state could not be saved." }
    }
}

internal fun createAndroidIdentityImportRecovery(
    context: Context,
    onIssue: (IdentityImportRecoveryIssue) -> Unit,
): IdentityImportRecovery {
    val resolver = context.contentResolver
    return IdentityImportRecovery(
        store = PreferencesIdentityImportRecoveryStore {
            context.getSharedPreferences("identity-import-recovery", Context.MODE_PRIVATE)
        },
        // Key import is currently the only persisted-read-grant owner in this app.
        // Another feature must establish separate ownership before persisting its own grants.
        persistedReadGrants = {
            resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        },
        releaseGrant = { value ->
            val uri = Uri.parse(value)
            try {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (denied: SecurityException) {
                // Revocation may already have happened. A remaining grant still needs a retry.
                if (resolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }) throw denied
            }
        },
        onIssue = onIssue,
    )
}
