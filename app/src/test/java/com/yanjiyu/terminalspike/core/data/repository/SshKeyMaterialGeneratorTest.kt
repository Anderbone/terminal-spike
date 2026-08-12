package com.yanjiyu.terminalspike.core.data.repository

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyMaterialGeneratorTest {
    @Test
    fun ed25519GenerationProducesInspectableOpenSshMetadataAndWipesPassphrase() {
        val passphrase = "correct horse battery staple".toByteArray()
        val material = JschSshKeyMaterialGenerator.generate(
            SshKeyGenerationAlgorithm.ED25519,
            passphrase,
        )
        try {
            val metadata = JschPrivateKeyMetadataInspector.inspect(material.privateKey)

            assertEquals("ssh-ed25519", metadata.algorithm)
            assertTrue(metadata.fingerprintSha256.startsWith("SHA256:"))
            assertTrue(metadata.openSshPublicKey.startsWith("ssh-ed25519 "))
            assertTrue(metadata.isPassphraseProtected)
            assertArrayEquals(ByteArray(passphrase.size), passphrase)
        } finally {
            material.wipe()
        }
        assertFalse(material.privateKey.any { it.toInt() != 0 })
    }
}
