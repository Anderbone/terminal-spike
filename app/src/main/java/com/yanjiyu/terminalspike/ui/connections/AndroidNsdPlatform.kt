package com.yanjiyu.terminalspike.ui.connections

import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.util.concurrent.Executor

/** Platform calls only; lifecycle ownership and callback delivery belong to the boundary. */
internal interface NearbySshNsdPlatform {
    fun discover(executor: Executor, listener: NsdManager.DiscoveryListener)
    fun stopDiscovery(listener: NsdManager.DiscoveryListener)
    fun resolve(service: NsdServiceInfo, listener: NsdManager.ResolveListener)
    fun stopResolution(listener: NsdManager.ResolveListener)
}

internal class AndroidNsdPlatform(
    private val manager: NsdManager,
    private val sdkInt: Int,
) : NearbySshNsdPlatform {
    @Suppress("NewApi", "DEPRECATION")
    override fun discover(executor: Executor, listener: NsdManager.DiscoveryListener) {
        if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN) {
            val request = DiscoveryRequest.Builder(SSH_DNS_SD_SERVICE_TYPE)
                .setFlags(DiscoveryRequest.FLAG_SHOW_PICKER)
                .build()
            manager.discoverServices(request, executor, listener)
        } else {
            manager.discoverServices(SSH_DNS_SD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    override fun stopDiscovery(listener: NsdManager.DiscoveryListener) {
        manager.stopServiceDiscovery(listener)
    }

    @Suppress("DEPRECATION")
    override fun resolve(service: NsdServiceInfo, listener: NsdManager.ResolveListener) {
        // The executor overload needs an extension level not guaranteed by the API number.
        manager.resolveService(service, listener)
    }

    @Suppress("NewApi")
    override fun stopResolution(listener: NsdManager.ResolveListener) {
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) manager.stopServiceResolution(listener)
    }
}
