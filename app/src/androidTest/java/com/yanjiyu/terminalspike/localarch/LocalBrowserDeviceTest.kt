package com.yanjiyu.terminalspike.localarch

import android.os.Build
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** No real accounts: verifies the external phone browser can return to a local CLI-style listener. */
class LocalBrowserDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun externalBrowserReturnsAuthorizationToThePhoneLoopbackListener() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("localBrowser") == "true")
        assertTrue("Use an authorized old phone", Build.MODEL in setOf("SM-G9708", "SM-S911B"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bridge = (context.applicationContext as TerminalSpikeApplication).container.localBrowser
        val previous = bridge.chooseBrowser.value
        bridge.setChooseBrowser(false)
        val executor = Executors.newFixedThreadPool(2)
        try {
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 20_000
                val callback = executor.submit<String> {
                    while (true) {
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val reader = socket.getInputStream().bufferedReader()
                            val request = reader.readLine()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            val done = request.startsWith("GET /callback?code=fixture-only ")
                            val response = if (done) {
                                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                            } else {
                                "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${server.localPort}/callback?code=fixture-only\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            }
                            socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                            socket.getOutputStream().flush()
                            if (done) return@submit request
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") ""
                }
                val launch = executor.submit<Boolean> { runBlocking {
                    bridge.open("http://127.0.0.1:${server.localPort}/authorize")
                } }
                // Drive Compose's test clock while the IPC-style caller waits for the UI.
                compose.waitUntil(15_000) { launch.isDone }
                assertTrue("App must be unlocked and browser enabled", launch.get())
                assertEquals("GET /callback?code=fixture-only HTTP/1.1", callback.get(25, TimeUnit.SECONDS))
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
                compose.waitForIdle()
                assertNull(bridge.request.value)
            }
        } finally {
            bridge.setChooseBrowser(previous)
            executor.shutdownNow()
        }
    }
}
