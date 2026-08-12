package com.yanjiyu.terminalspike.ui.connections

import android.content.Context
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/** Android-only NSD adapter. It browses `_ssh._tcp` and never performs address-range probes. */
internal class AndroidNearbySshDiscoveryBoundary(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java),
    private val callbackExecutor: Executor = MainThreadExecutor,
) : NearbySshDiscoveryBoundary {
    private val serviceInfoById = linkedMapOf<String, NsdServiceInfo>()
    private val candidateIdByKey = linkedMapOf<String, String>()
    private var nextCandidateId = 0L
    private var activeDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var activeResolveListener: NsdManager.ResolveListener? = null

    override val pickerMode: NearbySshPickerMode = if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN) {
        NearbySshPickerMode.SYSTEM
    } else {
        NearbySshPickerMode.IN_APP
    }

    @Suppress("NewApi")
    override fun start(listener: NearbySshDiscoveryBoundary.DiscoveryListener) {
        stopDiscovery()
        serviceInfoById.clear()
        candidateIdByKey.clear()
        val frameworkListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) {
                if (activeDiscoveryListener === this) activeDiscoveryListener = null
                listener.onDiscoveryStopped()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.isSshDnsSdType()) return
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

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val key = serviceKey(serviceInfo)
                val candidateId = candidateIdByKey.remove(key) ?: return
                serviceInfoById.remove(candidateId)
                listener.onServiceLost(candidateId)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (activeDiscoveryListener === this) activeDiscoveryListener = null
                listener.onFailure(errorCode.toBoundaryFailure(resolve = false))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (activeDiscoveryListener === this) activeDiscoveryListener = null
                listener.onFailure(errorCode.toBoundaryFailure(resolve = false))
            }
        }
        activeDiscoveryListener = frameworkListener
        if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN) {
            val request = DiscoveryRequest.Builder(SSH_DNS_SD_SERVICE_TYPE)
                .setFlags(DiscoveryRequest.FLAG_SHOW_PICKER)
                .build()
            nsdManager.discoverServices(request, callbackExecutor, frameworkListener)
        } else {
            @Suppress("DEPRECATION")
            nsdManager.discoverServices(
                SSH_DNS_SD_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                frameworkListener,
            )
        }
    }

    override fun stopDiscovery() {
        val listener = activeDiscoveryListener ?: return
        activeDiscoveryListener = null
        runCatching { nsdManager.stopServiceDiscovery(listener) }
    }

    @Suppress("DEPRECATION")
    override fun resolve(
        candidateId: String,
        listener: NearbySshDiscoveryBoundary.ResolutionListener,
    ) {
        stopResolution()
        val candidate = serviceInfoById[candidateId]
        if (candidate == null) {
            listener.onFailure(NearbySshBoundaryFailure.RESOLVE_FAILED)
            return
        }
        val frameworkListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (activeResolveListener === this) activeResolveListener = null
                listener.onResolved(serviceInfo.toResolvedNearbySshService())
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (activeResolveListener === this) activeResolveListener = null
                listener.onFailure(errorCode.toBoundaryFailure(resolve = true))
            }
        }
        activeResolveListener = frameworkListener
        // The executor overload also depends on a T extension level that is not guaranteed by
        // the platform API number. The original callback overload is available across our full
        // API 26+ range and also resolves the service selected by the API 37 system picker.
        nsdManager.resolveService(candidate, frameworkListener)
    }

    @Suppress("NewApi")
    override fun stopResolution() {
        val listener = activeResolveListener ?: return
        activeResolveListener = null
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { nsdManager.stopServiceResolution(listener) }
        }
        // API 26-33 has no cancellation API. Clearing the active listener makes its late
        // callback inert at the controller operation boundary.
    }

    override fun close() {
        stopDiscovery()
        stopResolution()
        serviceInfoById.clear()
        candidateIdByKey.clear()
    }

    private fun candidateIdFor(serviceInfo: NsdServiceInfo): String {
        val key = serviceKey(serviceInfo)
        return candidateIdByKey.getOrPut(key) {
            nextCandidateId += 1
            "nearby-ssh-$nextCandidateId"
        }
    }

    @Suppress("NewApi")
    private fun serviceKey(serviceInfo: NsdServiceInfo): String = buildString {
        append(serviceInfo.serviceName.orEmpty())
        append('\u0000')
        append(serviceInfo.serviceType.orEmpty())
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
