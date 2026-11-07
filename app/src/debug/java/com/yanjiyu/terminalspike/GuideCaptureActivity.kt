package com.yanjiyu.terminalspike

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.yanjiyu.terminalspike.connection.SshAuthentication
import com.yanjiyu.terminalspike.connection.SshConnectionConfig
import com.yanjiyu.terminalspike.connection.StartSshSessionResult
import com.yanjiyu.terminalspike.core.data.settings.AppSettings
import com.yanjiyu.terminalspike.settings.CommandSnippet
import com.yanjiyu.terminalspike.ui.releasenotes.bundledReleaseNotes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Emulator-only capture setup. All captured screens are the shipped MainActivity and real SSH UI. */
class GuideCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Build.FINGERPRINT.contains("generic") && !Build.MODEL.startsWith("sdk_gphone")) {
            finish()
            return
        }
        getSharedPreferences("quick-start", MODE_PRIVATE).edit().putBoolean("acknowledged", true).apply()
        lifecycleScope.launch {
            val container = (application as TerminalSpikeApplication).container
            withContext(Dispatchers.IO) {
                container.resolveStartup().join()
                container.terminalDataRepository.load()
                container.settings.update {
                    it.themeMode = AppSettings.ThemeMode.THEME_MODE_DARK
                    it.snippetsAboveKeyboard = true
                    it.releaseNotesSeenRevision = bundledReleaseNotes.maxOf { note -> note.revision }
                }
                listOf("git status", "git diff", "continue", "codex").forEachIndexed { index, label ->
                    container.terminalDataRepository.saveSnippet(CommandSnippet(
                        id = 700L + index, label = label, command = label, appendEnter = true,
                    ))
                }
            }
            val result = container.sshSessionRepository.startUserInitiatedSession(
                title = "Atlas workspace",
                config = SshConnectionConfig(
                    host = "10.0.2.2", port = 32227, username = requireNotNull(intent.getStringExtra("username")),
                    authentication = SshAuthentication.PrivateKey("guide-capture", {
                        File(filesDir, "guide-capture-key").readBytes()
                    }, null),
                    tmuxSessionSelectorEnabled = true,
                ),
            )
            check(result is StartSshSessionResult.Started) { "Capture connection could not start: $result" }
            startActivity(Intent(this@GuideCaptureActivity, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_TERMINAL_SESSION
                putExtra(MainActivity.EXTRA_TERMINAL_SESSION_ID, result.sessionId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
        }
    }
}
