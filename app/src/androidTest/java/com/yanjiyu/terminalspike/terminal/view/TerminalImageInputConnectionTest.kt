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
            accepted = listOf("image/png", "image/heic", "video/mp4", "application/pdf", "application/octet-stream").all { mime ->
                connection.commitContent(
                    InputContentInfo(uri, ClipDescription("attachment", arrayOf(mime)), null),
                    0, null,
                )
            }
        }

        assertTrue(accepted)
        assertEquals(5, requests.size)
        assertTrue(requests.all { it.uri == uri })
        assertEquals(listOf("image/png", "image/heic", "video/mp4", "application/pdf", "application/octet-stream"),
            requests.map { it.mimeType })
        assertTrue(sent.isEmpty())
    }
}
