package com.yanjiyu.terminalspike.terminal

import com.yanjiyu.terminalspike.connection.RecentHerdrProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class HerdrRecentProjectsState(
    val projects: List<RecentHerdrProject>? = null,
    val refreshing: Boolean = false,
)

/** Connection-local, bounded by the remote reader. Refresh failures retain the last good list. */
internal class HerdrRecentProjectsCache(
    private val load: () -> List<RecentHerdrProject>?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : AutoCloseable {
    private val lock = Any()
    private val mutableState = MutableStateFlow(HerdrRecentProjectsState())
    val state = mutableState.asStateFlow()
    private var pending: Job? = null
    private var lastAttempt: Long? = null
    private var generation = 0L
    private var closed = false

    /** Periodic prefetch is throttled; explicit opens refresh without hiding cached rows. */
    fun refresh(force: Boolean = false) {
        synchronized(lock) {
            if (closed || pending != null) return
            val now = nowMillis()
            if (!force && lastAttempt?.let { now - it < 30_000L } == true) return
            lastAttempt = now
            val requestedGeneration = generation
            mutableState.value = mutableState.value.copy(refreshing = true)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val projects = try {
                        load()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    synchronized(lock) {
                        if (!closed && requestedGeneration == generation) {
                            mutableState.value = mutableState.value.copy(
                                projects = projects ?: mutableState.value.projects,
                            )
                        }
                    }
                } finally {
                    synchronized(lock) {
                        if (requestedGeneration == generation) {
                            pending = null
                            mutableState.value = mutableState.value.copy(refreshing = false)
                        }
                    }
                }
            }
            pending = job
            job.start()
        }
    }

    fun projectOpened(path: String) {
        synchronized(lock) {
            if (closed) return
            generation++
            pending?.cancel()
            pending = null
            mutableState.value = HerdrRecentProjectsState(
                projects = mutableState.value.projects?.filterNot { it.path == path },
            )
            lastAttempt = null
        }
        refresh()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            generation++
            pending?.cancel()
            pending = null
            mutableState.value = HerdrRecentProjectsState()
        }
    }
}
