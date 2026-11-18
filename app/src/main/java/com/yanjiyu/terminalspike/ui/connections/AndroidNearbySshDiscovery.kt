package com.yanjiyu.terminalspike.ui.connections

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import java.util.concurrent.Executor

/** Main-thread-owned NSD adapter. Framework callbacks may arrive on legacy binder handlers. */
@MainThread
internal class AndroidNearbySshDiscoveryBoundary(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val platform: NearbySshNsdPlatform = AndroidNsdPlatform(
        context.getSystemService(NsdManager::class.java), sdkInt,
    ),
    private val callbackExecutor: Executor = MainThreadExecutor,
) : NearbySshDiscoveryBoundary {
    private val serviceInfoById = linkedMapOf<String, NsdServiceInfo>()
    private val candidateIdByKey = linkedMapOf<String, String>()
    private var nextCandidateId = 0L
    private var activeDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var activeResolveListener: NsdManager.ResolveListener? = null
    private var closed = false

    override val pickerMode: NearbySshPickerMode = if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN) {
        NearbySshPickerMode.SYSTEM
    } else {
        NearbySshPickerMode.IN_APP
    }

    override fun start(listener: NearbySshDiscoveryBoundary.DiscoveryListener) {
        if (closed) return
        stopDiscovery()
        serviceInfoById.clear()
        candidateIdByKey.clear()
        val frameworkListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = dispatchDiscovery(this) {
                activeDiscoveryListener = null
                listener.onDiscoveryStopped()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) = dispatchDiscovery(this) {
                if (!serviceInfo.serviceType.isSshDnsSdType()) return@dispatchDiscovery
                val candidateId = candidateIdFor(serviceInfo)
                serviceInfoById[candidateId] = serviceInfo
                listener.onServiceFound(
                    NearbySshServiceCandidate(
                        id = candidateId,
                        displayName = serviceInfo.serviceName
                            .orEmpty()
                            .trim()
                            .filterNot(Char::isISOControl)
                            .take(96)
                            .ifBlank { "SSH service" },
                    ),
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = dispatchDiscovery(this) {
                if (!serviceInfo.serviceType.isSshDnsSdType()) return@dispatchDiscovery
                val key = serviceKey(serviceInfo)
                val candidateId = candidateIdByKey.remove(key) ?: return@dispatchDiscovery
                serviceInfoById.remove(candidateId)
                listener.onServiceLost(candidateId)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = dispatchDiscovery(this) {
                activeDiscoveryListener = null
                listener.onFailure(errorCode.toBoundaryFailure(resolve = false))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = dispatchDiscovery(this) {
                activeDiscoveryListener = null
                listener.onFailure(errorCode.toBoundaryFailure(resolve = false))
            }
        }
        activeDiscoveryListener = frameworkListener
        try {
            platform.discover(callbackExecutor, frameworkListener)
        } catch (failure: Exception) {
            if (activeDiscoveryListener === frameworkListener) activeDiscoveryListener = null
            throw failure
        }
    }

    override fun stopDiscovery() {
        val listener = activeDiscoveryListener ?: return
        activeDiscoveryListener = null
        runCatching { platform.stopDiscovery(listener) }
    }

    override fun resolve(
        candidateId: String,
        listener: NearbySshDiscoveryBoundary.ResolutionListener,
    ) {
        if (closed) return
        stopResolution()
        val candidate = serviceInfoById[candidateId]
        if (candidate == null) {
            listener.onFailure(NearbySshBoundaryFailure.RESOLVE_FAILED)
            return
        }
        val frameworkListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) = dispatchResolution(this) {
                activeResolveListener = null
                listener.onResolved(serviceInfo.toResolvedNearbySshService())
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = dispatchResolution(this) {
                activeResolveListener = null
                listener.onFailure(errorCode.toBoundaryFailure(resolve = true))
            }
        }
        activeResolveListener = frameworkListener
        try {
            platform.resolve(candidate, frameworkListener)
        } catch (failure: Exception) {
            if (activeResolveListener === frameworkListener) activeResolveListener = null
            throw failure
        }
    }

    override fun stopResolution() {
        val listener = activeResolveListener ?: return
        activeResolveListener = null
        runCatching { platform.stopResolution(listener) }
    }

    override fun close() {
        if (closed) return
        closed = true
        stopDiscovery()
        stopResolution()
        serviceInfoById.clear()
        candidateIdByKey.clear()
    }

    private fun dispatchDiscovery(owner: NsdManager.DiscoveryListener, callback: () -> Unit) {
        callbackExecutor.execute {
            if (!closed && activeDiscoveryListener === owner) callback()
        }
    }

    private fun dispatchResolution(owner: NsdManager.ResolveListener, callback: () -> Unit) {
        callbackExecutor.execute {
            if (!closed && activeResolveListener === owner) callback()
        }
    }

    private fun candidateIdFor(serviceInfo: NsdServiceInfo): String {
        val key = serviceKey(serviceInfo)
        return candidateIdByKey.getOrPut(key) {
            if (candidateIdByKey.size >= MAX_DISCOVERED_SSH_SERVICES) {
                val oldestKey = candidateIdByKey.keys.first()
                serviceInfoById.remove(candidateIdByKey.remove(oldestKey))
            }
            nextCandidateId += 1
            "nearby-ssh-$nextCandidateId"
        }
    }

    @Suppress("NewApi")
    private fun serviceKey(serviceInfo: NsdServiceInfo): String = buildString {
        append(serviceInfo.serviceName.orEmpty())
        append('\u0000')
        append(SSH_DNS_SD_SERVICE_TYPE)
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            append('\u0000')
            append(serviceInfo.network?.networkHandle ?: 0L)
        }
    }

    @Suppress("DEPRECATION", "NewApi")
    private fun NsdServiceInfo.toResolvedNearbySshService(): ResolvedNearbySshService {
        val addresses = if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hostAddresses.mapNotNull { address -> address.hostAddress }
        } else {
            listOfNotNull(host?.hostAddress)
        }
        return ResolvedNearbySshService(
            serviceName = serviceName.orEmpty(),
            hostname = if (sdkInt >= Build.VERSION_CODES.BAKLAVA) hostname else null,
            numericAddresses = addresses,
            port = port,
        )
    }
}

internal fun androidNearbySshDiscoveryControllerFactory(
    context: Context,
): NearbySshDiscoveryControllerFactory {
    val applicationContext = context.applicationContext
    return NearbySshDiscoveryControllerFactory {
        DefaultNearbySshDiscoveryController(
            boundary = AndroidNearbySshDiscoveryBoundary(applicationContext),
        )
    }
}

private fun String?.isSshDnsSdType(): Boolean = this
    ?.trim()
    ?.removeSuffix(".")
    ?.equals(SSH_DNS_SD_SERVICE_TYPE, ignoreCase = true) == true

@Suppress("NewApi")
private fun Int.toBoundaryFailure(resolve: Boolean): NearbySshBoundaryFailure = when {
    this == NsdManager.FAILURE_PERMISSION_DENIED -> NearbySshBoundaryFailure.PERMISSION_DENIED
    resolve -> NearbySshBoundaryFailure.RESOLVE_FAILED
    else -> NearbySshBoundaryFailure.START_FAILED
}

private object MainThreadExecutor : Executor {
    private val handler = Handler(Looper.getMainLooper())

    override fun execute(command: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) command.run() else handler.post(command)
    }
}
