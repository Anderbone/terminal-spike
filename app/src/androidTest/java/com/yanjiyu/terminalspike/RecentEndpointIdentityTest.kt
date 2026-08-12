package com.yanjiyu.terminalspike

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.security.AndroidKeystoreRecentEndpointHmacProvider
import com.yanjiyu.terminalspike.core.security.RecentEndpointIdentityProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentEndpointIdentityTest {
    @Test
    fun androidKeystoreTokenIsStableAndDoesNotRevealEndpoint() {
        val provider = RecentEndpointIdentityProvider(AndroidKeystoreRecentEndpointHmacProvider())

        val first = provider.create(
            protocol = ConnectionProtocol.SSH,
            host = "device-under-test.example",
            port = 22,
            username = "private-user",
        )
        val second = provider.create(
            protocol = ConnectionProtocol.SSH,
            host = "DEVICE-UNDER-TEST.EXAMPLE.",
            port = 22,
            username = "private-user",
        )

        assertEquals(first.value, second.value)
        assertEquals(64, first.value.length)
        assertFalse(first.value.contains("device", ignoreCase = true))
        assertFalse(first.value.contains("private-user", ignoreCase = true))
    }
}
