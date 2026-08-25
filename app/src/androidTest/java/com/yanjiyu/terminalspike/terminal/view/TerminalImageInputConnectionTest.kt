package com.yanjiyu.terminalspike.terminal.view

import android.content.ClipDescription
import android.net.Uri
import android.view.View
import android.view.inputmethod.InputContentInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yanjiyu.terminalspike.terminal.TerminalInputSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalImageInputConnectionTest {
    @Test
    fun supportedImeImageContentIsDeliveredWithoutEnteringTheTerminalByteStream() {
        val uri = Uri.parse("content://terminal-spike-test/clipboard/image.png")
        val requests = mutableListOf<TerminalImageContentRequest>()
        val sent = mutableListOf<ByteArray>()
        var accepted = false

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = View(ApplicationProvider.getApplicationContext())
            val connection = TerminalInputConnection(
                targetView = view,
                sink = object : TerminalInputSink {
                    override fun send(bytes: ByteArray) {
                        sent += bytes
                    }
                },
                imageContentCallback = TerminalImageContentCallback { request ->
                    requests += request
                    true
                },
            )
            accepted = connection.commitContent(
                InputContentInfo(
                    uri,
                    ClipDescription("phone screenshot", arrayOf("image/png")),
                    null,
                ),
                0,
                null,
            )
        }

        assertTrue(accepted)
        assertEquals(uri, requests.single().uri)
        assertEquals("image/png", requests.single().mimeType)
        assertTrue(sent.isEmpty())
    }
}
