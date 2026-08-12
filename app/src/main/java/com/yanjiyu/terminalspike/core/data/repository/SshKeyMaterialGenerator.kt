package com.yanjiyu.terminalspike.core.data.repository

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream

internal enum class SshKeyGenerationAlgorithm {
    ED25519,
    RSA_4096,
}

/** A newly generated OpenSSH-compatible private-key document owned by the caller. */
internal class GeneratedSshKeyMaterial internal constructor(
    val privateKey: ByteArray,
) {
    fun wipe() = privateKey.fill(0)
}

internal fun interface SshKeyMaterialGenerator {
    fun generate(
        algorithm: SshKeyGenerationAlgorithm,
        passphrase: ByteArray?,
    ): GeneratedSshKeyMaterial
}

/**
 * Uses the already-reviewed SSH engine instead of adding another cryptographic dependency.
 * Generation runs off the main thread at the call site and every mutable passphrase copy is wiped.
 */
internal object JschSshKeyMaterialGenerator : SshKeyMaterialGenerator {
    override fun generate(
        algorithm: SshKeyGenerationAlgorithm,
        passphrase: ByteArray?,
    ): GeneratedSshKeyMaterial {
        val output = WipingByteArrayOutputStream()
        var keyPair: KeyPair? = null
        try {
            require(passphrase == null || passphrase.isNotEmpty()) {
                "A key passphrase must not be empty when supplied."
            }
            keyPair = when (algorithm) {
                SshKeyGenerationAlgorithm.ED25519 ->
                    KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
                SshKeyGenerationAlgorithm.RSA_4096 ->
                    KeyPair.genKeyPair(JSch(), KeyPair.RSA, RSA_BITS)
            }
            when (algorithm) {
                // EdDSA has no legacy PEM representation in JSch. Its legacy writer deliberately
                // throws UnsupportedOperationException, so emit the interoperable OpenSSH v1
                // container that JSch can subsequently inspect and use for authentication.
                SshKeyGenerationAlgorithm.ED25519 ->
                    keyPair.writeOpenSSHv1PrivateKey(output, passphrase)
                SshKeyGenerationAlgorithm.RSA_4096 ->
                    keyPair.writePrivateKey(output, passphrase)
            }
            val encoded = output.toByteArray()
            require(encoded.isNotEmpty()) { "SSH key generation produced an empty document." }
            return GeneratedSshKeyMaterial(encoded)
        } finally {
            keyPair?.dispose()
            passphrase?.fill(0)
            output.wipe()
        }
    }

    private const val RSA_BITS = 4_096
}

private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
    fun wipe() {
        buf.fill(0)
        reset()
    }
}
