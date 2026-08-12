package com.yanjiyu.terminalspike.connection.mosh

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.yanjiyu.terminalspike.mosh.api.IMoshPlugin
import com.yanjiyu.terminalspike.mosh.api.MoshAddressFamily
import com.yanjiyu.terminalspike.mosh.api.MoshApi
import com.yanjiyu.terminalspike.mosh.api.MoshCapabilities
import com.yanjiyu.terminalspike.mosh.api.MoshCapability
import com.yanjiyu.terminalspike.mosh.api.MoshContractLimit
import com.yanjiyu.terminalspike.mosh.api.MoshErrorCode
import com.yanjiyu.terminalspike.mosh.api.MoshNetworkHint
import com.yanjiyu.terminalspike.mosh.api.MoshOption
import com.yanjiyu.terminalspike.mosh.api.MoshSessionHandle
import com.yanjiyu.terminalspike.mosh.api.MoshSessionRequest
import com.yanjiyu.terminalspike.mosh.api.MoshStopReason
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

interface MoshExtensionClient {
    val status: StateFlow<MoshExtensionStatus>

    /** Idempotently discovers, verifies, binds, and negotiates the extension. */
    suspend fun connect(): MoshExtensionStatus

    /** Re-runs package verification and negotiation, replacing any existing binding. */
    suspend fun refresh(): MoshExtensionStatus

    fun sessionEvents(sessionId: UUID): Flow<com.yanjiyu.terminalspike.mosh.api.MoshSessionEvent>

    /** Calling this transfers ownership of [MoshSessionRequest.moshKeyRead] to the client. */
    suspend fun startSession(request: MoshSessionRequest): MoshClientResult<MoshSessionHandle>

    suspend fun resizeSession(
        sessionId: UUID,
        columns: Int,
        rows: Int,
    ): MoshClientResult<Unit>

    suspend fun updateNetworkHint(
        sessionId: UUID,
        hint: MoshNetworkHint,
    ): MoshClientResult<Unit>

    suspend fun stopSession(
        sessionId: UUID,
        reason: Int,
    ): MoshClientResult<Unit>

    suspend fun shutdown()
}

class AndroidMoshExtensionClient internal constructor(
    private val platform: MoshExtensionPlatform,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bindTimeoutMillis: Long = DEFAULT_BIND_TIMEOUT_MILLIS,
) : MoshExtensionClient {
    constructor(context: Context) : this(AndroidMoshExtensionPlatform(context.applicationContext))

    private val clientJob: Job = SupervisorJob()
    private val clientScope = CoroutineScope(clientJob + ioDispatcher)
    private val bindingMutex = Mutex()
    private val sessionMutationMutex = Mutex()
    private val activeSessions = ConcurrentHashMap.newKeySet<String>()
    private val callbackDispatcher = MoshCallbackDispatcher { sessionId ->
        activeSessions -= sessionId.toString()
    }
    private val closed = AtomicBoolean(false)
    private val rebindScheduled = AtomicBoolean(false)
    private val mutableStatus = MutableStateFlow<MoshExtensionStatus>(MoshExtensionStatus.Checking)

    override val status: StateFlow<MoshExtensionStatus> = mutableStatus.asStateFlow()

    private var bound: BoundExtension? = null

    override suspend fun connect(): MoshExtensionStatus = withContext(ioDispatcher) {
        bindingMutex.withLock { connectLocked() }
    }

    override suspend fun refresh(): MoshExtensionStatus = withContext(ioDispatcher) {
        bindingMutex.withLock {
            if (activeSessions.isNotEmpty()) return@withLock connectLocked()
            detachBoundLocked()
            connectLocked()
        }
    }

    override fun sessionEvents(
        sessionId: UUID,
    ): Flow<com.yanjiyu.terminalspike.mosh.api.MoshSessionEvent> = callbackDispatcher.track(sessionId)

    override suspend fun startSession(
        request: MoshSessionRequest,
    ): MoshClientResult<MoshSessionHandle> = withContext(ioDispatcher) {
        try {
            startSessionTransferred(request)
        } finally {
            request.moshKeyRead.closeQuietly()
        }
    }

    private suspend fun startSessionTransferred(
        request: MoshSessionRequest,
    ): MoshClientResult<MoshSessionHandle> = sessionMutationMutex.withLock {
        val sessionId = request.sessionId.canonicalUuidOrNull()
            ?: return@withLock MoshClientResult.Failure(MoshClientFailure.INVALID_REQUEST)
        if (!request.moshKeyRead.fileDescriptor.valid()) {
            return@withLock MoshClientResult.Failure(MoshClientFailure.INVALID_REQUEST)
        }
        if (closed.get()) return@withLock MoshClientResult.Failure(MoshClientFailure.CLIENT_CLOSED)
        val extension = requireBound()
            ?: return@withLock MoshClientResult.Failure(MoshClientFailure.EXTENSION_UNAVAILABLE)
        if (!extension.supports(request)) {
            return@withLock MoshClientResult.Failure(MoshClientFailure.CAPABILITY_MISMATCH)
        }
        val canonicalId = sessionId.toString()
        if (
            canonicalId in activeSessions ||
            activeSessions.size >= extension.protocol.maximumConcurrentSessions ||
            (activeSessions.isNotEmpty() &&
                extension.protocol.capabilityFlags and MoshCapability.MULTIPLE_SESSIONS == 0L)
        ) {
            return@withLock MoshClientResult.Failure(MoshClientFailure.CAPABILITY_MISMATCH)
        }

        callbackDispatcher.track(sessionId)
        activeSessions += canonicalId
        val handle = try {
            extension.plugin.startSession(request)
        } catch (_: DeadObjectException) {
            activeSessions -= canonicalId
            callbackDispatcher.forget(sessionId)
            connectionLost(extension, MoshExtensionError.BINDER_DIED)
            return@withLock MoshClientResult.Failure(MoshClientFailure.REMOTE_FAILURE)
        } catch (_: RemoteException) {
            activeSessions -= canonicalId
            callbackDispatcher.forget(sessionId)
            remoteFailure(extension)
            return@withLock MoshClientResult.Failure(MoshClientFailure.REMOTE_FAILURE)
        } catch (_: RuntimeException) {
            activeSessions -= canonicalId
            callbackDispatcher.forget(sessionId)
            remoteFailure(extension)
            return@withLock MoshClientResult.Failure(MoshClientFailure.REMOTE_FAILURE)
        }

        if (
            handle == null ||
            canonicalId !in activeSessions ||
            !handle.isValidFor(request, extension.protocol)
        ) {
            handle?.closeQuietly()
            activeSessions -= canonicalId
            callbackDispatcher.forget(sessionId)
            return@withLock MoshClientResult.Failure(MoshClientFailure.INVALID_EXTENSION_RESPONSE)
        }
        MoshClientResult.Success(handle)
    }

    override suspend fun resizeSession(
        sessionId: UUID,
        columns: Int,
        rows: Int,
    ): MoshClientResult<Unit> = withContext(ioDispatcher) {
        if (
            columns !in 1..MoshContractLimit.MAX_COLUMNS ||
            rows !in 1..MoshContractLimit.MAX_ROWS ||
            sessionId.toString() !in activeSessions
        ) {
            return@withContext MoshClientResult.Failure(MoshClientFailure.INVALID_REQUEST)
        }
        callBound { plugin -> plugin.resizeSession(sessionId.toString(), columns, rows) }
    }

    override suspend fun updateNetworkHint(
        sessionId: UUID,
        hint: MoshNetworkHint,
    ): MoshClientResult<Unit> = withContext(ioDispatcher) {
        if (sessionId.toString() !in activeSessions) {
            return@withContext MoshClientResult.Failure(MoshClientFailure.INVALID_REQUEST)
        }
        val extension = requireBound()
            ?: return@withContext MoshClientResult.Failure(MoshClientFailure.EXTENSION_UNAVAILABLE)
        if (!extension.protocol.supportsAddressFamily(hint.addressFamily, allowUnspecified = true)) {
            return@withContext MoshClientResult.Failure(MoshClientFailure.CAPABILITY_MISMATCH)
        }
        call(extension) { plugin -> plugin.updateNetworkHint(sessionId.toString(), hint) }
    }

    override suspend fun stopSession(
        sessionId: UUID,
        reason: Int,
    ): MoshClientResult<Unit> = withContext(ioDispatcher) {
        if (reason !in VALID_STOP_REASONS || sessionId.toString() !in activeSessions) {
            return@withContext MoshClientResult.Failure(MoshClientFailure.INVALID_REQUEST)
        }
        sessionMutationMutex.withLock {
            try {
                callBound { plugin -> plugin.stopSession(sessionId.toString(), reason) }
            } finally {
                activeSessions -= sessionId.toString()
                callbackDispatcher.forget(sessionId)
            }
        }
    }

    override suspend fun shutdown() {
        withContext(ioDispatcher) {
            if (!closed.compareAndSet(false, true)) return@withContext
            bindingMutex.withLock { detachBoundLocked() }
            activeSessions.clear()
            callbackDispatcher.failAll(MoshErrorCode.CANCELLED)
            mutableStatus.value = MoshExtensionStatus.Error(null, MoshExtensionError.CLIENT_CLOSED)
            clientScope.cancel()
        }
    }

    private suspend fun callBound(block: (IMoshPlugin) -> Unit): MoshClientResult<Unit> {
        if (closed.get()) return MoshClientResult.Failure(MoshClientFailure.CLIENT_CLOSED)
        val extension = requireBound()
            ?: return MoshClientResult.Failure(MoshClientFailure.EXTENSION_UNAVAILABLE)
        return call(extension, block)
    }

    private fun call(
        extension: BoundExtension,
        block: (IMoshPlugin) -> Unit,
    ): MoshClientResult<Unit> = try {
        block(extension.plugin)
        MoshClientResult.Success(Unit)
    } catch (_: DeadObjectException) {
        connectionLost(extension, MoshExtensionError.BINDER_DIED)
        MoshClientResult.Failure(MoshClientFailure.REMOTE_FAILURE)
    } catch (_: RemoteException) {
        remoteFailure(extension)
        MoshClientResult.Failure(MoshClientFailure.REMOTE_FAILURE)
    } catch (_: RuntimeException) {
        remoteFailure(extension)
        MoshClientResult.Failure(MoshClientFailure.REMOTE_FAILURE)
    }

    private suspend fun requireBound(): BoundExtension? = bindingMutex.withLock {
        connectLocked()
        bound
    }

    private suspend fun connectLocked(): MoshExtensionStatus {
        if (closed.get()) {
            return MoshExtensionStatus.Error(null, MoshExtensionError.CLIENT_CLOSED).also {
                mutableStatus.value = it
            }
        }
        bound?.takeIf { it.binder.isBinderAlive }?.let { current ->
            return MoshExtensionStatus.Available(current.version, current.protocol).also {
                mutableStatus.value = it
            }
        }
        detachBoundLocked()
        mutableStatus.value = MoshExtensionStatus.Checking
        return when (val discovery = platform.discover()) {
            MoshDiscoveryDecision.Absent -> updateStatus(MoshExtensionStatus.Absent)
            is MoshDiscoveryDecision.Disabled -> updateStatus(
                MoshExtensionStatus.Disabled(discovery.version),
            )
            is MoshDiscoveryDecision.Untrusted -> updateStatus(
                MoshExtensionStatus.Untrusted(discovery.version, discovery.reason),
            )
            MoshDiscoveryDecision.QueryFailed -> updateStatus(
                MoshExtensionStatus.Error(null, MoshExtensionError.PACKAGE_QUERY_FAILED),
            )
            is MoshDiscoveryDecision.Trusted -> bindAndNegotiateLocked(discovery.version)
        }
    }

    private suspend fun bindAndNegotiateLocked(
        version: MoshExtensionVersion,
    ): MoshExtensionStatus {
        val completion = CompletableDeferred<ConnectionSignal>()
        lateinit var connection: ClientServiceConnection
        connection = ClientServiceConnection(
            completion = completion,
            onConnectionLost = { error -> connectionLost(connection, error) },
        )
        val accepted = try {
            platform.bind(connection)
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) {
            return updateStatus(MoshExtensionStatus.Error(version, MoshExtensionError.BIND_REJECTED))
        }
        val signal = withTimeoutOrNull(bindTimeoutMillis) { completion.await() }
        if (signal !is ConnectionSignal.Connected) {
            platform.unbindQuietly(connection)
            val error = (signal as? ConnectionSignal.Failed)?.error ?: MoshExtensionError.BIND_TIMEOUT
            return updateStatus(MoshExtensionStatus.Error(version, error))
        }
        if (signal.component != MoshExtensionContract.component) {
            platform.unbindQuietly(connection)
            return updateStatus(MoshExtensionStatus.Error(version, MoshExtensionError.WRONG_BINDER))
        }
        val binder = signal.binder
        val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
        if (!binder.isBinderAlive || descriptor != MoshExtensionContract.BINDER_DESCRIPTOR) {
            platform.unbindQuietly(connection)
            return updateStatus(MoshExtensionStatus.Error(version, MoshExtensionError.WRONG_BINDER))
        }
        val plugin = IMoshPlugin.Stub.asInterface(binder)
        if (plugin == null) {
            platform.unbindQuietly(connection)
            return updateStatus(MoshExtensionStatus.Error(version, MoshExtensionError.WRONG_BINDER))
        }
        val deathRecipient = IBinder.DeathRecipient {
            connectionLost(connection, MoshExtensionError.BINDER_DIED)
        }
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (_: RemoteException) {
            platform.unbindQuietly(connection)
            return updateStatus(MoshExtensionStatus.Error(version, MoshExtensionError.BINDER_DIED))
        }

        var callbackRegistered = false
        return try {
            val apiVersion = plugin.apiVersion
            val capabilities: MoshCapabilities? = plugin.capabilities
            if (capabilities == null) {
                releaseCandidate(plugin, binder, connection, deathRecipient, callbackRegistered)
                return updateStatus(
                    MoshExtensionStatus.Incompatible(
                        version,
                        apiVersion,
                        MoshExtensionCompatibilityReason.INVALID_EXTENSION_RESPONSE,
                    ),
                )
            }
            when (val negotiation = MoshProtocolNegotiator.negotiate(apiVersion, capabilities)) {
                is MoshNegotiationDecision.Incompatible -> {
                    releaseCandidate(plugin, binder, connection, deathRecipient, callbackRegistered)
                    updateStatus(
                        MoshExtensionStatus.Incompatible(
                            version,
                            negotiation.extensionApiVersion,
                            negotiation.reason,
                        ),
                    )
                }
                is MoshNegotiationDecision.Compatible -> {
                    plugin.registerCallback(callbackDispatcher)
                    callbackRegistered = true
                    bound = BoundExtension(
                        version = version,
                        protocol = negotiation.protocol,
                        plugin = plugin,
                        binder = binder,
                        connection = connection,
                        deathRecipient = deathRecipient,
                    )
                    updateStatus(MoshExtensionStatus.Available(version, negotiation.protocol))
                }
            }
        } catch (_: DeadObjectException) {
            releaseCandidate(plugin, binder, connection, deathRecipient, callbackRegistered)
            updateStatus(MoshExtensionStatus.Error(version, MoshExtensionError.BINDER_DIED))
        } catch (_: RemoteException) {
            releaseCandidate(plugin, binder, connection, deathRecipient, callbackRegistered)
            updateStatus(MoshExtensionStatus.Error(version, MoshExtensionError.REMOTE_FAILURE))
        } catch (_: RuntimeException) {
            releaseCandidate(plugin, binder, connection, deathRecipient, callbackRegistered)
            updateStatus(
                MoshExtensionStatus.Incompatible(
                    version,
                    null,
                    MoshExtensionCompatibilityReason.INVALID_EXTENSION_RESPONSE,
                ),
            )
        }
    }

    private fun connectionLost(extension: BoundExtension, error: MoshExtensionError) {
        connectionLost(extension.connection, error)
    }

    private fun connectionLost(connection: ClientServiceConnection, error: MoshExtensionError) {
        clientScope.launch {
            val shouldRebind = bindingMutex.withLock {
                val current = bound
                if (current == null || current.connection !== connection) return@withLock false
                bound = null
                releaseBound(current)
                activeSessions.clear()
                callbackDispatcher.failAll(MoshErrorCode.EXTENSION_DIED)
                mutableStatus.value = MoshExtensionStatus.Error(current.version, error)
                !closed.get()
            }
            if (shouldRebind && rebindScheduled.compareAndSet(false, true)) {
                try {
                    delay(REBIND_DELAY_MILLIS)
                    connect()
                } finally {
                    rebindScheduled.set(false)
                }
            }
        }
    }

    private fun remoteFailure(extension: BoundExtension) {
        if (!extension.binder.isBinderAlive) {
            connectionLost(extension, MoshExtensionError.BINDER_DIED)
        } else {
            mutableStatus.value = MoshExtensionStatus.Error(
                extension.version,
                MoshExtensionError.REMOTE_FAILURE,
            )
        }
    }

    private fun detachBoundLocked() {
        val current = bound ?: return
        bound = null
        releaseBound(current)
    }

    private fun releaseBound(current: BoundExtension) {
        runCatching { current.plugin.unregisterCallback(callbackDispatcher) }
        runCatching { current.binder.unlinkToDeath(current.deathRecipient, 0) }
        platform.unbindQuietly(current.connection)
    }

    private fun releaseCandidate(
        plugin: IMoshPlugin,
        binder: IBinder,
        connection: ServiceConnection,
        deathRecipient: IBinder.DeathRecipient,
        callbackRegistered: Boolean,
    ) {
        if (callbackRegistered) runCatching { plugin.unregisterCallback(callbackDispatcher) }
        runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        platform.unbindQuietly(connection)
    }

    private fun <T : MoshExtensionStatus> updateStatus(value: T): T {
        mutableStatus.value = value
        return value
    }

    private fun BoundExtension.supports(request: MoshSessionRequest): Boolean {
        if (!protocol.supportsAddressFamily(request.addressFamily, allowUnspecified = false)) return false
        val predictionBits = request.optionFlags and
            (MoshOption.PREDICTION_ALWAYS or MoshOption.PREDICTION_NEVER)
        return predictionBits == 0L || protocol.capabilityFlags and MoshCapability.PREDICTION_CONTROL != 0L
    }

    private fun MoshNegotiatedProtocol.supportsAddressFamily(
        addressFamily: Int,
        allowUnspecified: Boolean,
    ): Boolean = when (addressFamily) {
        MoshAddressFamily.UNSPECIFIED -> allowUnspecified
        MoshAddressFamily.IPV4 -> capabilityFlags and MoshCapability.IPV4 != 0L
        MoshAddressFamily.IPV6 -> capabilityFlags and MoshCapability.IPV6 != 0L
        else -> false
    }

    private fun MoshSessionHandle.isValidFor(
        request: MoshSessionRequest,
        protocol: MoshNegotiatedProtocol,
    ): Boolean {
        val knownCapabilities = MoshCapability.IPV4 or
            MoshCapability.IPV6 or
            MoshCapability.NETWORK_ROAMING or
            MoshCapability.PREDICTION_CONTROL or
            MoshCapability.MULTIPLE_SESSIONS
        return modelVersion == MoshApi.MODEL_VERSION &&
            sessionId == request.sessionId &&
            terminalInputWrite.fileDescriptor.valid() &&
            terminalOutputRead.fileDescriptor.valid() &&
            negotiatedCapabilityFlags and knownCapabilities.inv() == 0L &&
            negotiatedCapabilityFlags and protocol.capabilityFlags == negotiatedCapabilityFlags &&
            when (request.addressFamily) {
                MoshAddressFamily.IPV4 -> negotiatedCapabilityFlags and MoshCapability.IPV4 != 0L
                MoshAddressFamily.IPV6 -> negotiatedCapabilityFlags and MoshCapability.IPV6 != 0L
                else -> false
            }
    }

    private fun MoshSessionHandle.closeQuietly() {
        terminalInputWrite.closeQuietly()
        terminalOutputRead.closeQuietly()
    }

    private fun ParcelFileDescriptor.closeQuietly() {
        runCatching(::close)
    }

    private fun MoshExtensionPlatform.unbindQuietly(connection: ServiceConnection) {
        runCatching { unbind(connection) }
    }

    private fun String.canonicalUuidOrNull(): UUID? {
        val parsed = runCatching(UUID::fromString).getOrNull() ?: return null
        return parsed.takeIf { it.toString() == this }
    }

    private data class BoundExtension(
        val version: MoshExtensionVersion,
        val protocol: MoshNegotiatedProtocol,
        val plugin: IMoshPlugin,
        val binder: IBinder,
        val connection: ClientServiceConnection,
        val deathRecipient: IBinder.DeathRecipient,
    )

    private sealed interface ConnectionSignal {
        data class Connected(
            val component: ComponentName,
            val binder: IBinder,
        ) : ConnectionSignal

        data class Failed(val error: MoshExtensionError) : ConnectionSignal
    }

    private class ClientServiceConnection(
        private val completion: CompletableDeferred<ConnectionSignal>,
        private val onConnectionLost: (MoshExtensionError) -> Unit,
    ) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            completion.complete(ConnectionSignal.Connected(name, service))
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (!completion.complete(ConnectionSignal.Failed(MoshExtensionError.BINDER_DIED))) {
                onConnectionLost(MoshExtensionError.BINDER_DIED)
            }
        }

        override fun onBindingDied(name: ComponentName) {
            if (!completion.complete(ConnectionSignal.Failed(MoshExtensionError.BINDER_DIED))) {
                onConnectionLost(MoshExtensionError.BINDER_DIED)
            }
        }

        override fun onNullBinding(name: ComponentName) {
            if (!completion.complete(ConnectionSignal.Failed(MoshExtensionError.NULL_BINDING))) {
                onConnectionLost(MoshExtensionError.NULL_BINDING)
            }
        }
    }

    private companion object {
        const val DEFAULT_BIND_TIMEOUT_MILLIS = 5_000L
        const val REBIND_DELAY_MILLIS = 250L
        val VALID_STOP_REASONS = setOf(
            MoshStopReason.USER_REQUESTED,
            MoshStopReason.SESSION_REPLACED,
            MoshStopReason.APP_SHUTDOWN,
            MoshStopReason.ERROR_RECOVERY,
        )
    }
}
