package com.yanjiyu.terminalspike.ui.connections

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val SSH_DNS_SD_SERVICE_TYPE = "_ssh._tcp"
internal const val NEARBY_SSH_DISCOVERY_TIMEOUT_MILLIS = 15_000L
private const val MAX_DISCOVERED_SSH_SERVICES = 32

internal enum class NearbySshPickerMode {
    SYSTEM,
    IN_APP,
}

internal data class NearbySshServiceCandidate(
    val id: String,
    val displayName: String,
)

internal data class ResolvedNearbySshService(
    val serviceName: String,
    val hostname: String?,
    val numericAddresses: List<String>,
    val port: Int,
)

internal data class NearbySshEndpoint(
    val displayName: String,
    val hostname: String,
    val port: Int,
)

internal enum class NearbySshDiscoveryFailure {
    NO_RESULTS,
    NO_SELECTION,
    TIMED_OUT,
    PERMISSION_DENIED,
    START_FAILED,
    RESOLVE_FAILED,
    NO_USABLE_ADDRESS,
}

internal sealed interface NearbySshDiscoveryState {
    data object Idle : NearbySshDiscoveryState

    data class Searching(
        val pickerMode: NearbySshPickerMode,
        val services: List<NearbySshServiceCandidate> = emptyList(),
    ) : NearbySshDiscoveryState

    data class Results(
        val services: List<NearbySshServiceCandidate>,
    ) : NearbySshDiscoveryState

    data class Resolving(
        val pickerMode: NearbySshPickerMode,
        val displayName: String,
    ) : NearbySshDiscoveryState

    data class Selected(
        val endpoint: NearbySshEndpoint,
    ) : NearbySshDiscoveryState

    data class Unavailable(
        val pickerMode: NearbySshPickerMode,
        val failure: NearbySshDiscoveryFailure,
    ) : NearbySshDiscoveryState
}

internal enum class NearbySshBoundaryFailure {
    PERMISSION_DENIED,
    START_FAILED,
    RESOLVE_FAILED,
}

/** Small testable boundary around Android's callback-based NSD API. */
internal interface NearbySshDiscoveryBoundary : AutoCloseable {
    val pickerMode: NearbySshPickerMode

    fun start(listener: DiscoveryListener)

    fun stopDiscovery()

    fun resolve(candidateId: String, listener: ResolutionListener)

    fun stopResolution()

    interface DiscoveryListener {
        fun onServiceFound(candidate: NearbySshServiceCandidate)

        fun onServiceLost(candidateId: String)

        fun onDiscoveryStopped()

        fun onFailure(failure: NearbySshBoundaryFailure)
    }

    interface ResolutionListener {
        fun onResolved(service: ResolvedNearbySshService)

        fun onFailure(failure: NearbySshBoundaryFailure)
    }
}

internal interface NearbySshDiscoveryController : AutoCloseable {
    val state: StateFlow<NearbySshDiscoveryState>

    fun start()

    fun select(candidateId: String)

    fun cancel()
}

internal fun interface NearbySshDiscoveryControllerFactory {
    fun create(): NearbySshDiscoveryController
}

internal class DefaultNearbySshDiscoveryController(
    private val boundary: NearbySshDiscoveryBoundary,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val timeoutMillis: Long = NEARBY_SSH_DISCOVERY_TIMEOUT_MILLIS,
) : NearbySshDiscoveryController {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow<NearbySshDiscoveryState>(NearbySshDiscoveryState.Idle)
    private val candidates = linkedMapOf<String, NearbySshServiceCandidate>()
    private var timeoutJob: Job? = null
    private var operationId = 0L
    private var closed = false

    override val state: StateFlow<NearbySshDiscoveryState> = mutableState.asStateFlow()

    override fun start() {
        if (closed) return
        val operation = beginOperation()
        candidates.clear()
        mutableState.value = NearbySshDiscoveryState.Searching(boundary.pickerMode)
        scheduleTimeout(operation, resolving = false)
        try {
            boundary.start(
                object : NearbySshDiscoveryBoundary.DiscoveryListener {
                    override fun onServiceFound(candidate: NearbySshServiceCandidate) {
                        if (!owns(operation)) return
                        candidates[candidate.id] = candidate
                        trimCandidates()
                        if (boundary.pickerMode == NearbySshPickerMode.SYSTEM) {
                            beginResolution(candidate)
                        } else {
                            mutableState.value = NearbySshDiscoveryState.Searching(
                                pickerMode = NearbySshPickerMode.IN_APP,
                                services = sortedCandidates(),
                            )
                        }
                    }

                    override fun onServiceLost(candidateId: String) {
                        if (!owns(operation) || candidates.remove(candidateId) == null) return
                        mutableState.value = NearbySshDiscoveryState.Searching(
                            pickerMode = boundary.pickerMode,
                            services = sortedCandidates(),
                        )
                    }

                    override fun onDiscoveryStopped() {
                        if (!owns(operation)) return
                        finishDiscoveryWithoutSelection()
                    }

                    override fun onFailure(failure: NearbySshBoundaryFailure) {
                        if (!owns(operation)) return
                        finishWithFailure(failure.toDiscoveryFailure())
                    }
                },
            )
        } catch (_: Exception) {
            if (owns(operation)) finishWithFailure(NearbySshDiscoveryFailure.START_FAILED)
        }
    }

    override fun select(candidateId: String) {
        if (closed) return
        candidates[candidateId]?.let(::beginResolution)
    }

    override fun cancel() {
        if (closed) return
        beginOperation()
        candidates.clear()
        runCatching { boundary.stopDiscovery() }
        runCatching { boundary.stopResolution() }
        mutableState.value = NearbySshDiscoveryState.Idle
    }

    override fun close() {
        if (closed) return
        closed = true
        operationId += 1
        timeoutJob?.cancel()
        runCatching { boundary.close() }
        scope.cancel()
    }

    private fun beginResolution(candidate: NearbySshServiceCandidate) {
        val operation = beginOperation()
        mutableState.value = NearbySshDiscoveryState.Resolving(
            pickerMode = boundary.pickerMode,
            displayName = candidate.displayName,
        )
        runCatching { boundary.stopDiscovery() }
        scheduleTimeout(operation, resolving = true)
        try {
            boundary.resolve(
                candidate.id,
                object : NearbySshDiscoveryBoundary.ResolutionListener {
                    override fun onResolved(service: ResolvedNearbySshService) {
                        if (!owns(operation)) return
                        timeoutJob?.cancel()
                        mutableState.value = service.toNearbySshEndpoint()
                            ?.let { endpoint -> NearbySshDiscoveryState.Selected(endpoint) }
                            ?: NearbySshDiscoveryState.Unavailable(
                                pickerMode = boundary.pickerMode,
                                failure = NearbySshDiscoveryFailure.NO_USABLE_ADDRESS,
                            )
                    }

                    override fun onFailure(failure: NearbySshBoundaryFailure) {
                        if (!owns(operation)) return
                        finishWithFailure(failure.toDiscoveryFailure())
                    }
                },
            )
        } catch (_: Exception) {
            if (owns(operation)) finishWithFailure(NearbySshDiscoveryFailure.RESOLVE_FAILED)
        }
    }

    private fun beginOperation(): Long {
        operationId += 1
        timeoutJob?.cancel()
        return operationId
    }

    private fun owns(operation: Long): Boolean = !closed && operationId == operation

    private fun scheduleTimeout(operation: Long, resolving: Boolean) {
        timeoutJob = scope.launch {
            delay(timeoutMillis)
            if (!owns(operation)) return@launch
            if (resolving) {
                runCatching { boundary.stopResolution() }
                finishWithFailure(NearbySshDiscoveryFailure.TIMED_OUT)
            } else {
                runCatching { boundary.stopDiscovery() }
                timeoutJob = null
                mutableState.value = if (
                    boundary.pickerMode == NearbySshPickerMode.IN_APP && candidates.isNotEmpty()
                ) {
                    NearbySshDiscoveryState.Results(sortedCandidates())
                } else {
                    NearbySshDiscoveryState.Unavailable(
                        pickerMode = boundary.pickerMode,
                        failure = NearbySshDiscoveryFailure.TIMED_OUT,
                    )
                }
            }
        }
    }

    private fun finishDiscoveryWithoutSelection() {
        timeoutJob?.cancel()
        timeoutJob = null
        mutableState.value = when {
            boundary.pickerMode == NearbySshPickerMode.SYSTEM -> NearbySshDiscoveryState.Unavailable(
                pickerMode = NearbySshPickerMode.SYSTEM,
                failure = NearbySshDiscoveryFailure.NO_SELECTION,
            )
            candidates.isEmpty() -> NearbySshDiscoveryState.Unavailable(
                pickerMode = NearbySshPickerMode.IN_APP,
                failure = NearbySshDiscoveryFailure.NO_RESULTS,
            )
            else -> NearbySshDiscoveryState.Results(sortedCandidates())
        }
    }

    private fun finishWithFailure(failure: NearbySshDiscoveryFailure) {
        timeoutJob?.cancel()
        timeoutJob = null
        runCatching { boundary.stopDiscovery() }
        runCatching { boundary.stopResolution() }
        mutableState.value = NearbySshDiscoveryState.Unavailable(boundary.pickerMode, failure)
    }

    private fun trimCandidates() {
        while (candidates.size > MAX_DISCOVERED_SSH_SERVICES) {
            candidates.remove(candidates.keys.first())
        }
    }

    private fun sortedCandidates(): List<NearbySshServiceCandidate> = candidates.values.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER, NearbySshServiceCandidate::displayName)
            .thenBy(NearbySshServiceCandidate::id),
    )
}

internal fun ResolvedNearbySshService.toNearbySshEndpoint(): NearbySshEndpoint? {
    if (port !in 1..65_535) return null
    val normalizedHostname = hostname
        ?.trim()
        ?.removeSuffix(".")
        ?.takeIf(::isUsableDiscoveredHost)
    val numericAddress = numericAddresses.asSequence()
        .map(String::trim)
        .filter(::isUsableNumericAddress)
        .sortedBy { address -> if (':' in address) 1 else 0 }
        .firstOrNull()
    val resolvedHost = normalizedHostname ?: numericAddress ?: return null
    val normalizedName = serviceName
        .trim()
        .filterNot(Char::isISOControl)
        .take(96)
        .ifBlank { resolvedHost }
    return NearbySshEndpoint(
        displayName = normalizedName,
        hostname = resolvedHost,
        port = port,
    )
}

internal fun HostEditorDraft.prefillFromNearbySsh(endpoint: NearbySshEndpoint): HostEditorDraft = copy(
    displayName = endpoint.displayName,
    hostname = endpoint.hostname,
    port = endpoint.port.toString(),
)

private fun isUsableDiscoveredHost(value: String): Boolean =
    value.isNotEmpty() &&
        value.length <= 253 &&
        value.none(Char::isWhitespace) &&
        value.none(Char::isISOControl)

private fun isUsableNumericAddress(value: String): Boolean =
    isUsableDiscoveredHost(value) &&
        '%' !in value &&
        (':' in value || value.all { character -> character.isDigit() || character == '.' })

private fun NearbySshBoundaryFailure.toDiscoveryFailure(): NearbySshDiscoveryFailure = when (this) {
    NearbySshBoundaryFailure.PERMISSION_DENIED -> NearbySshDiscoveryFailure.PERMISSION_DENIED
    NearbySshBoundaryFailure.START_FAILED -> NearbySshDiscoveryFailure.START_FAILED
    NearbySshBoundaryFailure.RESOLVE_FAILED -> NearbySshDiscoveryFailure.RESOLVE_FAILED
}
