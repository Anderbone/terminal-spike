package com.yanjiyu.terminalspike.debug

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.yanjiyu.terminalspike.core.security.credential.IdentityImportRecovery
import com.yanjiyu.terminalspike.core.security.credential.PreferencesIdentityImportRecoveryStore
import java.util.UUID

/** Debug-only, same-UID probe. Never opens the real recovery preferences or a document. */
class IdentityImportRecoveryProbeService : Service() {
    private val worker = HandlerThread("identity-import-recovery-probe")
    private val nonce = UUID.randomUUID().toString()
    private var preferenceName: String? = null
    private var recovery: IdentityImportRecovery? = null
    private lateinit var handler: Handler
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        worker.start()
        handler = Handler(worker.looper) { request ->
            handle(request)
            true
        }
        messenger = Messenger(handler)
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handle(request: Message) {
        val reply = request.replyTo ?: return
        val result = Bundle()
        try {
            val name = requireNotNull(request.data.getString("preferences"))
            require(name.matches(Regex("identity-import-recovery-fixture-[0-9a-f-]{36}")))
            require(name == preferenceName || preferenceName == null)
            val store = PreferencesIdentityImportRecoveryStore { getSharedPreferences(name, MODE_PRIVATE) }
            val owner = recovery ?: IdentityImportRecovery(store, { emptySet() }, { error("No probe grants") })
                .also { recovery = it; preferenceName = name }
            val processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
                else java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000')
            check(processName == "$packageName:identity_import_recovery_probe")
            when (request.what) {
                START -> owner.begin() // Deliberately unfinished; only this private process is killed.
                ACKNOWLEDGE -> owner.acknowledgeReselection()
                SNAPSHOT -> Unit
                TERMINATE -> check(request.data.getString("nonce") == nonce)
                else -> error("Unknown probe request")
            }
            val state = store.read()
            result.putBoolean("incomplete", state.incomplete)
            result.putBoolean("needsReselection", state.needsReselection)
            result.putInt("pid", Process.myPid())
            result.putString("process", processName)
            result.putString("nonce", nonce)
            reply.send(Message.obtain().apply { what = request.what; data = result })
            if (request.what == TERMINATE) {
                // Reply first; the test observes Binder death before it creates the next process.
                Handler(mainLooper).postDelayed({ Process.killProcess(Process.myPid()) }, 100)
            }
        } catch (_: Exception) {
            // Test errors never disclose provider paths, key data or user preferences.
            reply.send(Message.obtain().apply {
                what = request.what
                data = Bundle().apply { putBoolean("failed", true) }
            })
        }
    }

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val SNAPSHOT = 1
        const val START = 2
        const val ACKNOWLEDGE = 3
        const val TERMINATE = 4
    }
}
