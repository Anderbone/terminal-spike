package com.yanjiyu.terminalspike.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A real WebView and loopback server; no external server or credentials needed. */
class BrowserWorkspaceTest {
    @Test fun exactTailscaleHttpsAddressIsAcceptedOnAndroid() {
        val address = "https://epee.tail719241.ts.net:9443"
        val uri = java.net.URI(address)
        assertEquals("host=${uri.host}, port=${uri.port}, authority=${uri.rawAuthority}", address, browserAddress(address))
        assertEquals(address, browserAddress("\u2068$address\u2069\u200B"))
    }

    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val controller = BrowserController(context)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newSingleThreadExecutor()
    private var visible by mutableStateOf(true)
    private val endpoint = "browser-fixture-${server.localPort}"
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun open(configureInUi: Boolean = false, general: Boolean = false): BrowserTab {
        executor.submit {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader()
                    val path = input.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                    requests.add(path)
                    while (!input.readLine().isNullOrEmpty()) { /* Consume headers. */ }
                    val page = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                        <title>Workspace fixture</title><body><h1 id="page">${if (path.startsWith("/second")) "Second" else "Project"}</h1>
                        <div contenteditable id="editor">Original code</div><a href="/second" id="next">Next file</a>
                        <script>window.boot=1;sessionStorage.setItem('workspace','ready');</script></body>""".trimIndent().toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${page.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(page); flush()
                    }
                }
            } catch (_: SocketException) { /* Server closed after test. */ }
        }
        controller.open(endpoint, "Fixture", codeServer = !general)
        val tab = requireNotNull(controller.active.value)
        val url = "http://127.0.0.1:${server.localPort}/"
        if (!configureInUi) controller.configure(tab, url)
        compose.setContent {
            MaterialTheme {
                BrowserTestShell(controller, tab, visible,
                    onTerminal = { controller.hide(); visible = false },
                    onBrowser = { controller.select(); visible = true })
            }
        }
        if (configureInUi) {
            compose.onNodeWithTag("browser-url-input").performClick().performTextInput(url)
            compose.onNodeWithTag("browser-url-input").performImeAction()
        }
        compose.waitUntil(15_000) { compose.runOnIdle { tab.view?.progress == 100 && tab.view?.url?.startsWith(tab.address) == true } }
        compose.waitUntil(15_000) { script(tab, "document.getElementById('editor')?.textContent") == "\"Original code\"" }
        return tab
    }

    private fun script(tab: BrowserTab, code: String): String? {
        val result = AtomicReference<String?>()
        compose.runOnIdle { tab.view?.evaluateJavascript(code) { result.set(it) } }
        val deadline = System.nanoTime() + 3_000_000_000L
        while (result.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
        return result.get()
    }

    @After fun close() {
        compose.runOnIdle { controller.close() }
        context.getSharedPreferences("workspace-browser", 0).edit()
            .remove("url-${com.yanjiyu.terminalspike.ui.project.projectRevision(endpoint)}")
            .remove("desktop-${com.yanjiyu.terminalspike.ui.project.projectRevision(endpoint)}").commit()
        server.close(); executor.shutdownNow()
    }

    @Test fun browserRetainsLiveEditorWhenReturningToTerminal() {
        val tab = open()
        val originalView = tab.view
        assertEquals("\"ready\"", script(tab, "sessionStorage.getItem('workspace')"))
        script(tab, "document.getElementById('editor').textContent='Unsaved editor text'; window.boot=7")
        val browserBarHeight = compose.onNodeWithTag("terminal-session-strip").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithText("Address").assertDoesNotExist()
        compose.onNodeWithTag("terminal-session-tab-1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("browser-test-terminal").assertIsDisplayed()
        assertEquals(browserBarHeight, compose.onNodeWithTag("terminal-session-strip").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.onNodeWithTag("browser-session-tab").performClick()
        compose.waitForIdle()
        assertSame(originalView, tab.view)
        assertEquals("\"Unsaved editor text\"", script(tab, "document.getElementById('editor').textContent"))
        assertEquals("7", script(tab, "window.boot"))
        compose.runOnIdle { controller.hide(); visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            val view = requireNotNull(tab.view)
            val handled = view.webViewClient.onRenderProcessGone(view, object : android.webkit.RenderProcessGoneDetail() {
                override fun didCrash() = false
                override fun rendererPriorityAtExit() = android.webkit.WebView.RENDERER_PRIORITY_BOUND
            })
            assertTrue(handled)
            assertNull(tab.view)
            controller.select()
            visible = true
        }
        compose.waitUntil(10_000) { compose.runOnIdle { tab.view != null && tab.view !== originalView && tab.view?.progress == 100 } }
        assertEquals("\"Original code\"", script(tab, "document.getElementById('editor').textContent"))
    }

    @Test fun ordinaryBrowserHasAnAddressBarAndKeepsCodeServerIndependent() {
        val tab = open(general = true)
        val originalView = tab.view
        val code = BrowserController(context)
        try {
            code.open("$endpoint-code", "Code server")
            val codeTab = requireNotNull(code.current.value)
            code.configure(codeTab, "https://computer.example:9443/")
            val second = "http://127.0.0.1:${server.localPort}/second"
            compose.onNodeWithTag("web-browser-address").performTextReplacement(second)
            compose.onNodeWithTag("web-browser-go").performClick()
            compose.waitUntil(10_000) { compose.runOnIdle { tab.view?.url == second && tab.view?.progress == 100 } }
            assertSame(originalView, tab.view)
            assertEquals("https://computer.example:9443/", codeTab.address)
            compose.onNodeWithTag("web-browser-tab-menu").performClick()
            compose.onNodeWithTag("browser-password").assertDoesNotExist()
            compose.onNodeWithText("Code server").assertDoesNotExist()
        } finally {
            compose.runOnIdle { code.close() }
            context.getSharedPreferences("workspace-browser", 0).edit()
                .remove("url-${com.yanjiyu.terminalspike.ui.project.projectRevision("$endpoint-code")}").commit()
        }
    }

    @Test fun browserNavigationAndLayoutWorkInsideTheApp() {
        val tab = open(configureInUi = true)
        val firstView = tab.view
        // Chromium intentionally skips back-history entries navigated without user activation.
        // Tap the real link so this exercises the same history as a person's touch.
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val position = org.json.JSONArray(requireNotNull(script(tab, """(function(){
            const r=document.getElementById('next').getBoundingClientRect();
            const scale=window.devicePixelRatio*(window.visualViewport?.scale || 1);
            return [(r.x+r.width/2)*scale,(r.y+r.height/2)*scale];
        })()""")))
        val origin = compose.runOnIdle {
            IntArray(2).also { requireNotNull(tab.view).getLocationOnScreen(it) }
        }
        assertTrue(device.click(origin[0] + position.getDouble(0).toInt(), origin[1] + position.getDouble(1).toInt()))
        compose.waitUntil(10_000) { compose.runOnIdle { tab.view?.url?.endsWith("/second") == true && tab.view?.progress == 100 } }
        assertEquals("\"Second\"", script(tab, "document.getElementById('page').textContent"))
        compose.runOnIdle {
            val view = requireNotNull(tab.view)
            val history = view.copyBackForwardList()
            assertSame(firstView, view)
            assertTrue("url=${view.url} size=${history.size} history=${(0 until history.size).map { history.getItemAtIndex(it).url }} index=${history.currentIndex} requests=$requests",
                view.canGoBack())
            view.goBack()
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            context.cacheDir.resolve("workspace-browser.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        try {
            compose.waitUntil(10_000) { compose.runOnIdle { tab.view?.url == tab.address && tab.view?.progress == 100 } }
        } catch (error: Exception) {
            val info = compose.runOnIdle {
                val view = requireNotNull(tab.view)
                val history = view.copyBackForwardList()
                "url=${view.url} progress=${view.progress} history=${(0 until history.size).map { history.getItemAtIndex(it).url }} index=${history.currentIndex} requests=$requests"
            }
            throw AssertionError(info, error)
        }
        compose.onNodeWithTag("browser-tab-menu").performClick()
        compose.onNodeWithTag("browser-layout").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(tab.desktop)
            assertFalse(requireNotNull(tab.view).settings.userAgentString.contains("Mobile"))
            assertFalse(requireNotNull(tab.view).settings.allowFileAccess)
            assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW, requireNotNull(tab.view).settings.mixedContentMode)
        }
    }
}
