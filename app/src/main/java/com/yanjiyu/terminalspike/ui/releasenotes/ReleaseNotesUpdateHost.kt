package com.yanjiyu.terminalspike.ui.releasenotes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanjiyu.terminalspike.core.data.settings.AppSettingsRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Called only inside unlocked main content. Terminal/connection prompts take priority. */
@Composable
internal fun ReleaseNotesUpdateHost(
    repository: AppSettingsRepository,
    ready: Boolean,
    canShow: Boolean,
) {
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = null)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var initializationFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var dismissedForNow by rememberSaveable { mutableStateOf(false) }
    val latestRevision = bundledReleaseNotes.maxOfOrNull { it.revision } ?: return
    val current = settings
    LaunchedEffect(ready, current?.hasReleaseNotesSeenRevision()) {
        if (ready && current != null && !current.hasReleaseNotesSeenRevision()) {
            try {
                val baseline = withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    val info = context.packageManager.getPackageInfo(context.packageName, 0)
                    initialReleaseNotesRevision(info.firstInstallTime, info.lastUpdateTime, latestRevision)
                }
                repository.initializeReleaseNotes(baseline)
            } catch (_: IOException) {
                // Optional education must not block launch; the next launch can retry.
                initializationFailed = true
            }
        }
    }
    if (
        !ready || !canShow || initializationFailed || dismissedForNow ||
        !lifecycleState.isAtLeast(Lifecycle.State.RESUMED) ||
        current == null || !current.hasReleaseNotesSeenRevision()
    ) return
    val unread = unreadReleaseNotes(bundledReleaseNotes, Integer.toUnsignedLong(current.releaseNotesSeenRevision))
    if (unread.isEmpty()) return

    ReleaseNotesDialog(
        notes = unread,
        history = bundledReleaseNotes,
        saving = saving,
        saveFailed = saveFailed,
        onDismissWithoutSaving = { dismissedForNow = true },
        onDismiss = {
            if (!saving) {
                saving = true
                saveFailed = false
                scope.launch {
                    try {
                        repository.acknowledgeReleaseNotes(latestRevision)
                    } catch (_: IOException) {
                        saveFailed = true
                    } finally {
                        saving = false
                    }
                }
            }
        },
    )
}
