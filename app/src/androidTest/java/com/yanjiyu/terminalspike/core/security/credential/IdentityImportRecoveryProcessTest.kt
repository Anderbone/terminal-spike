package com.yanjiyu.terminalspike.core.security.credential

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yanjiyu.terminalspike.debug.IdentityImportRecoveryProbeService
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Restarts only an owned private debug process; no real key, grant or catalog is touched. */
@RunWith(AndroidJUnit4::class)
class IdentityImportRecoveryProcessTest {
    @Test(timeout = 30_000)
    fun unfinishedImportRequiresReselectionAcrossActualProcessDeathAndAcknowledgementPersists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "identity-import-recovery-fixture-${UUID.randomUUID()}"
        val intent = Intent(context, IdentityImportRecoveryProbeService::class.java)
        try {
            val old = Probe(context, intent, name).use { probe ->
                val state = probe.request(IdentityImportRecoveryProbeService.START)
                assertTrue(state.getBoolean("incomplete"))
                assertFalse(state.getBoolean("needsReselection"))
                probe.terminate(state)
                state
            }
            val recovered = Probe(context, intent, name).use { probe ->
                val state = probe.request(IdentityImportRecoveryProbeService.SNAPSHOT)
                assertNotEquals(old.getString("nonce"), state.getString("nonce"))
                assertNotEquals(old.getInt("pid"), state.getInt("pid"))
                assertFalse(state.getBoolean("incomplete"))
                assertTrue(state.getBoolean("needsReselection"))
                assertFalse(probe.request(IdentityImportRecoveryProbeService.ACKNOWLEDGE).getBoolean("needsReselection"))
                probe.terminate(state)
                state
            }
            Probe(context, intent, name).use { probe ->
                val state = probe.request(IdentityImportRecoveryProbeService.SNAPSHOT)
                assertNotEquals(recovered.getString("nonce"), state.getString("nonce"))
                assertNotEquals(recovered.getInt("pid"), state.getInt("pid"))
                assertFalse(state.getBoolean("incomplete"))
                assertFalse(state.getBoolean("needsReselection"))
                probe.terminate(state)
            }
        } finally {
            // Scope is a generated preference file and exported=false service only.
            // Do not clear the package, stop the main app, or modify real recovery state.
            try { context.stopService(intent) }
            finally { assertTrue(context.deleteSharedPreferences(name)) }
        }
    }

    private class Probe(
        private val context: Context,
        private val intent: Intent,
        private val preferenceName: String,
    ) : AutoCloseable {
        private val connected = CountDownLatch(1)
        private val replies = LinkedBlockingQueue<Message>()
        private val replyMessenger = Messenger(Handler(Looper.getMainLooper()) {
            replies.offer(Message.obtain(it))
            true
        })
        private var binder: IBinder? = null
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        private var bound = false

        init {
            try {
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                check(bound && connected.await(5, TimeUnit.SECONDS)) { "Owned probe did not connect" }
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        fun request(operation: Int, nonce: String? = null): Bundle {
            Messenger(requireNotNull(binder)).send(Message.obtain().apply {
                what = operation
                replyTo = replyMessenger
                data = Bundle().apply {
                    putString("preferences", preferenceName)
                    putString("nonce", nonce)
                }
            })
            val response = requireNotNull(replies.poll(5, TimeUnit.SECONDS)) { "Owned probe did not respond" }
            assertEquals(operation, response.what)
            return response.data.also {
                assertFalse("Owned probe rejected the request", it.getBoolean("failed"))
                assertNotEquals(Process.myPid(), it.getInt("pid"))
                assertEquals("${context.packageName}:identity_import_recovery_probe", it.getString("process"))
            }
        }

        fun terminate(state: Bundle) {
            val dead = CountDownLatch(1)
            val ownedBinder = requireNotNull(binder)
            val recipient = IBinder.DeathRecipient { dead.countDown() }
            ownedBinder.linkToDeath(recipient, 0)
            try {
                request(IdentityImportRecoveryProbeService.TERMINATE, requireNotNull(state.getString("nonce")))
                close() // Unbind before death so BIND_AUTO_CREATE cannot restart the service.
                assertTrue("Owned private process must actually exit", dead.await(5, TimeUnit.SECONDS))
            } finally {
                ownedBinder.unlinkToDeath(recipient, 0)
            }
        }

        override fun close() {
            if (bound) {
                bound = false
                context.unbindService(connection)
            }
        }
    }
}
