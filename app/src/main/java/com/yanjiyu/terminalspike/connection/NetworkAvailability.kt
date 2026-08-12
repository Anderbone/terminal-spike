package com.yanjiyu.terminalspike.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.yanjiyu.terminalspike.mosh.api.MoshAddressFamily
import java.net.Inet4Address
import java.net.Inet6Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Non-identifying facts about the process default network. */
internal data class NetworkAvailabilitySnapshot(
    val isOnline: Boolean,
    val connectivityGeneration: Long,
    val addressFamily: Int,
    val isMetered: Boolean,
) {
    init {
        require(connectivityGeneration >= 0L)
        require(
            addressFamily == MoshAddressFamily.UNSPECIFIED ||
                addressFamily == MoshAddressFamily.IPV4 ||
                addressFamily == MoshAddressFamily.IPV6,
        )
    }
}

/** Process-scoped connectivity used for retry gating and Mosh roaming hints. */
internal interface NetworkAvailability {
    val state: StateFlow<NetworkAvailabilitySnapshot>
}

internal object AlwaysOnlineNetworkAvailability : NetworkAvailability {
    private val mutableState = MutableStateFlow(
        NetworkAvailabilitySnapshot(
            isOnline = true,
            connectivityGeneration = 0L,
            addressFamily = MoshAddressFamily.UNSPECIFIED,
            isMetered = false,
        ),
    )
    override val state: StateFlow<NetworkAvailabilitySnapshot> = mutableState.asStateFlow()
}

/**
 * Deduplicates platform callback noise while keeping generation monotonic. The retained token is an
 * opaque Android [Network] handle in production; it is never published, persisted, or logged.
 */
internal class NetworkAvailabilityPublisher(
    initialNetworkToken: Any?,
    initialOnline: Boolean,
    initialAddressFamily: Int,
    initialMetered: Boolean,
) {
    private val lock = Any()
    private var networkToken: Any? = initialNetworkToken
    private val mutableState = MutableStateFlow(
        NetworkAvailabilitySnapshot(
            isOnline = initialOnline,
            connectivityGeneration = 0L,
            addressFamily = initialAddressFamily,
            isMetered = initialMetered,
        ),
    )
    val state: StateFlow<NetworkAvailabilitySnapshot> = mutableState.asStateFlow()

    fun update(
        networkToken: Any?,
        isOnline: Boolean,
        addressFamily: Int,
        isMetered: Boolean,
    ) {
        synchronized(lock) {
            val current = mutableState.value
            if (
                this.networkToken == networkToken &&
                current.isOnline == isOnline &&
                current.addressFamily == addressFamily &&
                current.isMetered == isMetered
            ) {
                return
            }
            this.networkToken = networkToken
            mutableState.value = NetworkAvailabilitySnapshot(
                isOnline = isOnline,
                connectivityGeneration = current.connectivityGeneration.nextConnectivityGeneration(),
                addressFamily = addressFamily,
                isMetered = isMetered,
            )
        }
    }
}

/**
 * Uses Android's default-network callback without exposing SSIDs, addresses, carrier, VPN owner, or
 * traffic. Link addresses are inspected only for their Java address class and are never retained.
 */
internal class AndroidNetworkAvailability(context: Context) : NetworkAvailability {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val publisher = connectivity.currentNonIdentifyingNetworkState().let { initial ->
        NetworkAvailabilityPublisher(
            initialNetworkToken = initial.network,
            initialOnline = initial.isOnline,
            initialAddressFamily = initial.addressFamily,
            initialMetered = initial.isMetered,
        )
    }
    override val state: StateFlow<NetworkAvailabilitySnapshot> = publisher.state

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()

        override fun onLost(network: Network) = refresh()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = refresh()

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refresh()

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = refresh()
    }

    init {
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onFailure {
                publisher.update(
                    networkToken = null,
                    isOnline = true,
                    addressFamily = MoshAddressFamily.UNSPECIFIED,
                    isMetered = true,
                )
            }
    }

    private fun refresh() {
        val current = connectivity.currentNonIdentifyingNetworkState()
        publisher.update(
            networkToken = current.network,
            isOnline = current.isOnline,
            addressFamily = current.addressFamily,
            isMetered = current.isMetered,
        )
    }
}

private data class AndroidDefaultNetworkState(
    val network: Network?,
    val isOnline: Boolean,
    val addressFamily: Int,
    val isMetered: Boolean,
)

private fun ConnectivityManager.currentNonIdentifyingNetworkState(): AndroidDefaultNetworkState =
    runCatching {
        val network = activeNetwork
        val capabilities = getNetworkCapabilities(network)
        AndroidDefaultNetworkState(
            network = network,
            isOnline = capabilities?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true,
            addressFamily = classifyNetworkAddressFamily(
                getLinkProperties(network)?.linkAddresses?.map { it.address }.orEmpty(),
            ),
            isMetered = isActiveNetworkMetered,
        )
    }.getOrElse {
        AndroidDefaultNetworkState(
            network = null,
            isOnline = true,
            addressFamily = MoshAddressFamily.UNSPECIFIED,
            isMetered = true,
        )
    }

internal fun classifyNetworkAddressFamily(addresses: Iterable<java.net.InetAddress>): Int {
    var hasIpv4 = false
    var hasIpv6 = false
    addresses.forEach { address ->
        when (address) {
            is Inet4Address -> hasIpv4 = true
            is Inet6Address -> hasIpv6 = true
        }
    }
    return when {
        hasIpv4 && !hasIpv6 -> MoshAddressFamily.IPV4
        hasIpv6 && !hasIpv4 -> MoshAddressFamily.IPV6
        else -> MoshAddressFamily.UNSPECIFIED
    }
}

private fun Long.nextConnectivityGeneration(): Long =
    if (this == Long.MAX_VALUE) Long.MAX_VALUE else this + 1L
