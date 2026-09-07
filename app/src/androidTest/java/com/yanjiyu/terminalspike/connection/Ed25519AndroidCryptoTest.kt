package com.yanjiyu.terminalspike.connection

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Ed25519AndroidCryptoTest {
    @Test
    fun androidRuntimeUsesTheSupportedProviderAndCanSign() {
        assertEquals(
            "com.jcraft.jsch.bc.SignatureEd25519",
            JSch.getConfig("ssh-ed25519"),
        )

        val keyPair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        try {
            assertNotNull(keyPair.publicKeyBlob)
            val signature = keyPair.getSignature("terminal-spike-ed25519-proof".encodeToByteArray())
            assertNotNull(signature)
            assertTrue(requireNotNull(signature).isNotEmpty())
        } finally {
            keyPair.dispose()
        }
    }
}
