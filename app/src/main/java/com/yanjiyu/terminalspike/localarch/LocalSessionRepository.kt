package com.yanjiyu.terminalspike.localarch

import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.DefaultSshSessionTerminalFactory
import com.yanjiyu.terminalspike.connection.RemoteSessionTerminalConfiguration
import com.yanjiyu.terminalspike.connection.SessionForegroundStartResult
import com.yanjiyu.terminalspike.connection.SessionForegroundStarter
import com.yanjiyu.terminalspike.connection.SshSessionTerminal
import com.yanjiyu.terminalspike.connection.requiresForegroundService
import com.yanjiyu.terminalspike.terminal.TerminalController
import com.yanjiyu.terminalspike.terminal.model.TerminalRendererProfile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class LocalSessionSnapshot(
    val id: Long,
    val title: String,
    val connectionState: ConnectionState,
    val terminalTitle: String? = null,
    val lastActivityAtEpochMillis: Long = 0,
)

internal data class LocalRuntimeState(
    val sessions: List<LocalSessionSnapshot> = emptyList(),
    val installationActive: Boolean = false,
    val error: String? = null,
) {
    val activeCount: Int get() = sessions.count { it.connectionState.requiresForegroundService() }
    val connectedCount: Int get() = sessions.count { it.connectionState is ConnectionState.Connected }
    val requiresForegroundService: Boolean get() = installationActive || activeCount > 0
}

/** Application-owned local processes. No host records, credentials, reconnect policy or network observer. */
internal class LocalSessionRepository(
    val environment: ArchEnvironment,
    private val scope: CoroutineScope,
    private val foregroundStarter: SessionForegroundStarter,
) {
    private val lock = Any()
    private val entries = linkedMapOf<Long, Entry>()
    private var nextId = -1L
    private var installJob: Job? = null
    private var installationActive = false
    private var lastError: String? = null
    private val mutableRuntime = MutableStateFlow(LocalRuntimeState())
    val runtime = mutableRuntime.asStateFlow()

    init { scope.launch { environment.refresh() } }

    fun start(configuration: RemoteSessionTerminalConfiguration = RemoteSessionTerminalConfiguration()): Long? {
        val entry = synchronized(lock) {
            if (entries.size >= 8 || installationActive || !environment.state.value.installed) {
                lastError = "Install Arch Linux first, and keep at most eight Local Arch tabs open."
                publish()
                return null
            }
            val id = nextId--
            Entry(id, "Local Arch ${-id}", DefaultSshSessionTerminalFactory.create(configuration))
                .also { entries[id] = it; lastError = null; publish() }
        }
        if (foregroundStarter.startFromVisibleUserAction() !is SessionForegroundStartResult.Started) {
            synchronized(lock) {
                entry.state = ConnectionState.Failed("Could not keep the local shell running in the background")
                lastError = "Could not start the terminal background service"
                publish()
            }
            return null
        }
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                environment.withShell { lease ->
                    currentCoroutineContext().ensureActive()
                    val connection = LocalSession(lease.command)
                    synchronized(lock) {
                        if (entry.closed.get()) return@withShell
                        entry.connection = connection
                        entry.terminal.attach(connection)
                    }
                    connection.connect(entry.terminal.columns, entry.terminal.rows,
                        onBytes = { bytes ->
                            entry.terminal.accept(bytes, connection::send)
                            synchronized(lock) {
                                val now = System.currentTimeMillis()
                                val title = entry.terminal.terminalTitle
                                if (now - entry.activity >= 1000 || entry.terminalTitle != title) {
                                    entry.activity = now
                                    entry.terminalTitle = title
                                    publish()
                                }
                            }
                        },
                        onState = { state -> synchronized(lock) {
                            if (!entry.closed.get()) { entry.state = state; publish() }
                        } },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) {
                    if (!entry.closed.get()) entry.state = ConnectionState.Failed("Could not start the Local Arch shell")
                }
            } finally {
                entry.terminal.detach()
                synchronized(lock) {
                    entry.connection = null
                    if (entry.state !is ConnectionState.Failed) entry.state = ConnectionState.Disconnected
                    publish()
                }
            }
        }
        synchronized(lock) {
            entry.job = job
            if (entry.closed.get()) job.cancel() else job.start()
        }
        return entry.id
    }

    fun controllerFor(id: Long): TerminalController? = synchronized(lock) { entries[id]?.terminal?.controller }

    fun updateRendererProfile(profile: TerminalRendererProfile) {
        synchronized(lock) { entries.values.mapNotNull { it.terminal.controller } }
            .forEach { it.updateRendererProfile(profile) }
    }

    fun disconnect(id: Long) {
        synchronized(lock) {
            val entry = entries[id] ?: return
            entry.closed.set(true)
            entry.connection?.close()
            entry.job?.cancel()
            entry.state = ConnectionState.Disconnected
            publish()
        }
    }

    fun close(id: Long) {
        val entry = synchronized(lock) {
            disconnect(id)
            entries.remove(id).also { publish() }
        } ?: return
        scope.launch(Dispatchers.IO) {
            entry.job?.join()
            entry.terminal.stopAndClear()
        }
    }

    /** Called only from a visible install or explicitly confirmed reset/reinstall action. */
    fun installOrReset(reinstall: Boolean = false, reset: Boolean = false): Boolean = synchronized(lock) {
        if (installationActive) return false
        if (entries.values.any { !it.closed.get() && it.state.requiresForegroundService() }) {
            lastError = "Close all Local Arch shells before installing tools, resetting or reinstalling."
            publish()
            return false
        }
        installationActive = true
        lastError = null
        publish()
        if (foregroundStarter.startFromVisibleUserAction() !is SessionForegroundStartResult.Started) {
            installationActive = false
            lastError = "Could not start the terminal background service"
            publish()
            return false
        }
        installJob = scope.launch(Dispatchers.IO) {
            try {
                if (reset) environment.reset() else environment.install(replaceExisting = reinstall)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) {
                    lastError = environment.state.value.error
                        ?: "Could not complete the Arch operation. Close local shells and retry."
                    publish()
                }
            } finally {
                synchronized(lock) { installationActive = false; publish() }
            }
        }
        true
    }

    fun failAllForServiceLoss(message: String) = synchronized(lock) {
        if (!runtime.value.requiresForegroundService) return@synchronized
        installJob?.cancel()
        entries.values.forEach { entry ->
            if (entry.state.requiresForegroundService()) {
                entry.closed.set(true)
                entry.connection?.close()
                entry.job?.cancel()
                entry.state = ConnectionState.Failed(message)
            }
        }
        lastError = message
        publish()
    }

    private fun publish() {
        mutableRuntime.value = LocalRuntimeState(entries.values.map {
            LocalSessionSnapshot(it.id, it.title, it.state, it.terminalTitle, it.activity)
        }, installationActive, lastError)
    }

    private class Entry(val id: Long, val title: String, val terminal: SshSessionTerminal) {
        val closed = AtomicBoolean()
        var state: ConnectionState = ConnectionState.Connecting
        var connection: LocalSession? = null
        var job: Job? = null
        var terminalTitle: String? = null
        var activity: Long = System.currentTimeMillis()
    }
}
