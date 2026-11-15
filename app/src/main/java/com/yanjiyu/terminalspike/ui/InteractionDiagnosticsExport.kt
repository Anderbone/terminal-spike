package com.yanjiyu.terminalspike.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yanjiyu.terminalspike.BuildConfig
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.security.InteractionDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The snapshot is captured before opening the system picker, while the incident is still recent. */
@Composable
internal fun InteractionDiagnosticsExport(content: @Composable (() -> Unit, Boolean) -> Unit) {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) {
            busy = false
        } else {
            scope.launch {
                try {
                    // Read the frozen file so rotation/recreation while the picker is open
                    // cannot lose an in-memory pending report or produce an empty document.
                    val snapshot = InteractionDiagnostics.frozenIncident()
                    withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                            it.write(snapshot.toByteArray(Charsets.UTF_8))
                        }
                    }
                    Toast.makeText(context, R.string.interaction_diagnostics_saved, Toast.LENGTH_LONG).show()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.interaction_diagnostics_failed, Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                }
            }
        }
    }
    content({
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    InteractionDiagnostics.snapshot()
                    launcher.launch("terminal-spike-diagnostics-${System.currentTimeMillis()}.txt")
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    busy = false
                    Toast.makeText(context, R.string.interaction_diagnostics_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }, !busy)
}

/** Queue first; delivery is independent of terminal connectivity and survives a dismissed menu. */
@Composable
internal fun InteractionDiagnosticsSend(content: @Composable (() -> Unit, Boolean) -> Unit) {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    content({
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    val id = InteractionDiagnostics.queueForComputer()
                    Toast.makeText(context, R.string.interaction_diagnostics_queued, Toast.LENGTH_LONG).show()
                    repeat(15) {
                        delay(2_000)
                        val status = InteractionDiagnostics.deliveryStatus(id)
                        if (status != null) {
                            Toast.makeText(context,
                                if (status == "analysis_failed") R.string.interaction_diagnostics_analysis_failed
                                else R.string.interaction_diagnostics_delivered,
                                Toast.LENGTH_LONG).show()
                            return@launch
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.interaction_diagnostics_send_failed, Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                }
            }
        }
    }, !busy)
}
