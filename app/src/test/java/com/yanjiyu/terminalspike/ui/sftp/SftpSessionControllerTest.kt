package com.yanjiyu.terminalspike.ui.sftp

import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SftpSessionControllerTest {
    @Test
    fun authenticationRequestCarriesOnlyNonSecretMetadataAndCloseClearsIt() {
        val controller = SftpSessionController(TestScope()) { error("No client expected") }

        controller.requestAuthentication(
            hostId = "host-id",
            hostName = "Production",
            kind = SftpSecretKind.PASSWORD,
        )

        assertEquals(
            SftpUiState.AuthenticationRequired("host-id", "Production", SftpSecretKind.PASSWORD),
            controller.state.value,
        )
        controller.close()
        assertSame(SftpUiState.Closed, controller.state.value)
    }
}
