package com.yanjiyu.terminalspike.ui.connections

import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedConnectionPickerTest {
    @Test
    fun savedPasswordConnectsWithoutAnotherPrompt() {
        val host = host(savedSecretAvailable = true)

        assertFalse(
            host.requiresConnectionPrompt(
                catalog = ConnectionsEditorCatalog(hosts = listOf(host)),
                moshAvailable = true,
            ),
        )
    }

    @Test
    fun missingPasswordRequiresAuthenticationPrompt() {
        val host = host(savedSecretAvailable = false)

        assertTrue(
            host.requiresConnectionPrompt(
                catalog = ConnectionsEditorCatalog(hosts = listOf(host)),
                moshAvailable = true,
            ),
        )
    }

    @Test
    fun unavailableMoshOffersTheExistingFallbackPrompt() {
        val host = host(
            savedSecretAvailable = true,
            protocol = ConnectionProtocol.MOSH,
        )

        assertTrue(
            host.requiresConnectionPrompt(
                catalog = ConnectionsEditorCatalog(hosts = listOf(host)),
                moshAvailable = false,
            ),
        )
    }

    private fun host(
        savedSecretAvailable: Boolean,
        protocol: ConnectionProtocol = ConnectionProtocol.SSH,
    ) = HostEditorSeed(
        draft = HostEditorDraft(
            persistentId = "host-id",
            displayName = "Production",
            protocol = protocol,
            hostname = "prod.example",
            username = "deploy",
        ),
        savedSecretAvailable = savedSecretAvailable,
    )
}
