package com.yanjiyu.terminalspike.core.security

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.yanjiyu.terminalspike.BuildConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Debug only. All disk work is serialized off the UI/transport threads. No network or logcat. */
internal object InteractionDiagnostics {
    private val buffer by lazy { InteractionTraceBuffer() }
    private val worker by lazy {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "interaction-diagnostics").apply { isDaemon = true }
        }
    }
    private var store: InteractionTraceStore? = null // worker-owned
    private var ioFailures = 0L // worker-owned
    private val writers by lazy { ConcurrentHashMap<Int, () -> Unit>() }
    @Volatile private var lastMainTick = 0L

    fun initialize(context: Context) {
        if (!BuildConfig.DEBUG) return
        val directory = File(context.filesDir, "interaction-diagnostics")
        lastMainTick = SystemClock.uptimeMillis()
        val main = Handler(Looper.getMainLooper())
        main.post(object : Runnable {
            override fun run() {
                lastMainTick = SystemClock.uptimeMillis()
                main.postDelayed(this, 1_000)
            }
        })
        worker.execute {
            store = InteractionTraceStore(directory)
        }
        record(InteractionTraceEvent.START, 0, BuildConfig.VERSION_CODE.toLong(), Process.myPid().toLong())
        worker.scheduleWithFixedDelay({
            writers.values.forEach { it() }
            val delay = SystemClock.uptimeMillis() - lastMainTick
            record(InteractionTraceEvent.MAIN_HEARTBEAT, 0, delay, buffer.droppedEvents, ioFailures,
                sampleMillis = if (delay > 2_000) 2_000 else 10_000)
            flush()
        }, 2, 2, TimeUnit.SECONDS)
    }

    fun trackWriter(key: Int, status: () -> Unit) {
        if (BuildConfig.DEBUG) writers[key] = status
    }

    fun untrackWriter(key: Int) {
        if (BuildConfig.DEBUG) writers.remove(key)
    }

    fun record(
        event: InteractionTraceEvent,
        owner: Int,
        a: Long = 0,
        b: Long = 0,
        c: Long = 0,
        d: Long = 0,
        sampleMillis: Long = 0,
    ) {
        if (!BuildConfig.DEBUG) return
        buffer.record(event, owner, SystemClock.uptimeMillis(), System.currentTimeMillis(), a, b, c, d, sampleMillis)
    }

    private fun flush() {
        try {
            store?.append(buffer.drain())
        } catch (_: Exception) {
            // Diagnostics must not stop the app, transport, or future flush attempts.
            ioFailures++
        }
    }

    suspend fun snapshot(): String {
        check(BuildConfig.DEBUG)
        record(InteractionTraceEvent.EXPORT, 0)
        return suspendCancellableCoroutine { continuation ->
            worker.execute {
                try {
                    writers.values.forEach { it() }
                    flush()
                    val header = buildString {
                        appendLine("Terminal Spike interaction diagnostics v1")
                        appendLine("version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} debug=true")
                        appendLine("model=${android.os.Build.MODEL} api=${android.os.Build.VERSION.SDK_INT}")
                        appendLine("io_failures=$ioFailures; files rotate at 256 KiB each; pending events bounded at 2048")
                        appendLine("Numeric metadata only. No terminal text, input text, endpoints or credentials.")
                        appendLine("owner is process-local; ATTACH links controller to connection; transport 1=SSH 2=Mosh")
                        appendLine("columns: unix_ms uptime_ms event owner a b c d; booleans 0=false 1=true")
                        InteractionTraceEvent.entries.forEach { appendLine("# ${it.name}: ${it.fields}") }
                    }
                    // Keep one frozen incident even if the picker is cancelled or the app reconnects.
                    val result = requireNotNull(store) { "Diagnostics not initialized" }.freezeIncident(header)
                    continuation.resume(result)
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            }
        }
    }

    suspend fun frozenIncident(): String {
        check(BuildConfig.DEBUG)
        return suspendCancellableCoroutine { continuation ->
            worker.execute {
                try {
                    continuation.resume(requireNotNull(store).readIncident())
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            }
        }
    }

    suspend fun queueForComputer(): String {
        snapshot()
        return onWorker { requireNotNull(store).queueIncident() }
    }

    suspend fun deliveryStatus(id: String): String? = onWorker { requireNotNull(store).deliveryStatus(id) }

    private suspend fun <T> onWorker(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        worker.execute {
            try {
                continuation.resume(block())
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }
    }
}
