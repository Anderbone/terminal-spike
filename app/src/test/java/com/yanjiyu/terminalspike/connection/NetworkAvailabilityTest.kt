package com.yanjiyu.terminalspike.connection

import com.yanjiyu.terminalspike.mosh.api.MoshAddressFamily
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkAvailabilityTest {
    @Test
    fun familyClassificationDoesNotExposeAddressesAndDualStackIsUnspecified() {
        val ipv4 = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
        val ipv6 = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })

        assertEquals(MoshAddressFamily.UNSPECIFIED, classifyNetworkAddressFamily(emptyList()))
        assertEquals(MoshAddressFamily.IPV4, classifyNetworkAddressFamily(listOf(ipv4)))
        assertEquals(MoshAddressFamily.IPV6, classifyNetworkAddressFamily(listOf(ipv6)))
        assertEquals(
            MoshAddressFamily.UNSPECIFIED,
            classifyNetworkAddressFamily(listOf(ipv4, ipv6)),
        )
    }

    @Test
    fun publisherCoalescesDuplicateCallbacksAndAdvancesForDefaultNetworkChanges() {
        val firstNetwork = Any()
        val secondNetwork = Any()
        val publisher = NetworkAvailabilityPublisher(
            initialNetworkToken = firstNetwork,
            initialOnline = true,
            initialAddressFamily = MoshAddressFamily.IPV4,
            initialMetered = false,
        )

        publisher.update(firstNetwork, true, MoshAddressFamily.IPV4, false)
        assertEquals(0L, publisher.state.value.connectivityGeneration)

        publisher.update(secondNetwork, true, MoshAddressFamily.IPV4, false)
        assertEquals(1L, publisher.state.value.connectivityGeneration)

        publisher.update(secondNetwork, true, MoshAddressFamily.IPV6, true)
        assertEquals(
            NetworkAvailabilitySnapshot(
                isOnline = true,
                connectivityGeneration = 2L,
                addressFamily = MoshAddressFamily.IPV6,
                isMetered = true,
            ),
            publisher.state.value,
        )
    }
}
