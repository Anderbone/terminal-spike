package com.yanjiyu.terminalspike.ui

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.core.data.repository.InvalidSshPrivateKeyException
import com.yanjiyu.terminalspike.core.data.repository.JschPrivateKeyMetadataInspector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SshIdentityImportFailureTest {
    @Test fun ordinaryTextEmptyFilesAndPublicKeysExplainTheFormatFailure() {
        for (bytes in listOf("hello-terminal".toByteArray(), byteArrayOf(), "ssh-rsa AAAA example".toByteArray())) {
            val error = assertThrows(InvalidSshPrivateKeyException::class.java) {
                JschPrivateKeyMetadataInspector.inspect(bytes)
            }
            assertEquals(R.string.notice_key_import_invalid_format, sshIdentityImportFailureMessage(error))
        }
    }

    @Test fun validPrivateKeyStillImports() {
        val key = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        val bytes = ByteArrayOutputStream().also { key.writePrivateKey(it) }.toByteArray()
        try {
            assertFalse(JschPrivateKeyMetadataInspector.inspect(bytes).isPassphraseProtected)
        } finally { bytes.fill(0); key.dispose() }
    }

    @Test fun sizeAndReadFailuresHaveDifferentExplanations() {
        val large = assertThrows(SensitiveInputTooLargeException::class.java) {
            readSshIdentityBytes(32) { ByteArrayInputStream(ByteArray(33)) }
        }
        assertEquals(R.string.notice_key_import_too_large, sshIdentityImportFailureMessage(large))
        for (open in listOf<() -> java.io.InputStream?>({ null }, { throw SecurityException("private detail") }, { throw IOException("private detail") })) {
            val read = assertThrows(SshIdentityReadException::class.java) { readSshIdentityBytes(32, open) }
            assertEquals(R.string.notice_key_import_read_failed, sshIdentityImportFailureMessage(read))
        }
        assertEquals(R.string.notice_key_import_failed, sshIdentityImportFailureMessage(IllegalStateException("storage unavailable")))
    }
}
