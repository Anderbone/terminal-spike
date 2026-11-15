package com.yanjiyu.terminalspike.connection

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SftpPathExistenceTest {
    @Test
    fun onlyMissingFilesAllowAllocatingANewDestination() {
        assertTrue(sftpPathExists { })
        assertFalse(sftpPathExists {
            throw SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "Missing")
        })
    }

    @Test
    fun permissionAndTransportFailuresStopDestinationAllocation() {
        listOf(
            SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "Denied"),
            SftpException(ChannelSftp.SSH_FX_CONNECTION_LOST, "Disconnected"),
            IOException("I/O failed"),
            IllegalStateException("Channel closed"),
        ).forEach { failure ->
            assertSame(failure, assertThrows(failure.javaClass) {
                sftpPathExists { throw failure }
            })
        }
    }
}
