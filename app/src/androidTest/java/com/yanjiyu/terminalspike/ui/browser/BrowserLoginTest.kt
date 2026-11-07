package com.yanjiyu.terminalspike.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BrowserLoginTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val address = "http://127.0.0.1:${server.localPort}/"
    private val endpoint = "browser-login-fixture-${server.localPort}"
    private val target = BrowserLoginTarget.from(address)
    private val cookie = "browser_fixture_${server.localPort}"
    private val secret = "correct\"'; window.injected=1; //密码"
    private val posts = AtomicInteger()
    private var controller by mutableStateOf<BrowserController?>(null)

    private fun startServer() {
        executor.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                executor.submit {
                    try { socket.use {
                        it.soTimeout = 3000
                        val input = it.getInputStream().bufferedReader(Charsets.UTF_8)
                        val request = input.readLine()?.split(' ') ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val length = headers["content-length"]?.toIntOrNull() ?: 0
                        val data = CharArray(length)
                        var offset = 0
                        while (offset < length) { val count = input.read(data, offset, length - offset); if (count < 0) break; offset += count }
                        val password = String(data).split('&').firstOrNull { field -> field.startsWith("password=") }
                            ?.substringAfter('=')?.let { encoded -> URLDecoder.decode(encoded, "UTF-8") }
                        val post = request[0] == "POST"
                        if (post) posts.incrementAndGet()
                        val accepted = password == secret
                        val login = request[1].substringBefore('?') == "/login"
                        val authenticated = headers["cookie"].orEmpty().split(';').any { value -> value.trim() == "$cookie=ready" }
                        val redirect = accepted || (!login && !authenticated)
                        val body = when {
                            redirect -> ""
                            login -> """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                                <title>code-server login</title><form class="login-form" method="post">
                                <input type="password" name="password"><button type="submit">Sign in</button></form>""".trimIndent()
                            else -> "<!doctype html><title>Workspace</title><div id='workspace'>Authenticated workspace</div>"
                        }.toByteArray()
                        val output = it.getOutputStream()
                        val extra = if (redirect) "Location: ${if (accepted) "/" else "/login"}\r\n" +
                            if (accepted) "Set-Cookie: $cookie=ready; Path=/; Max-Age=3600; SameSite=Strict\r\n" else "" else ""
                        output.write(("HTTP/1.1 ${if (redirect) "303 See Other" else "200 OK"}\r\n" + extra +
                            "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        output.write(body); output.flush()
                    } } catch (_: java.io.IOException) { /* A closed browser/preconnect socket. */ }
                }
            }
        }
    }

    private fun reopen(): BrowserTab {
        var tab: BrowserTab? = null
        compose.runOnIdle { controller?.close(); controller = null }
        compose.waitForIdle()
        compose.runOnIdle {
            controller = BrowserController(context).also { next ->
                next.open(endpoint, "Login fixture")
                tab = requireNotNull(next.active.value)
                next.configure(requireNotNull(tab), address)
            }
        }
        return requireNotNull(tab)
    }

    private fun waitForPassword() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("browser-password-input").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun cleanup() {
        compose.runOnIdle { controller?.close(); controller = null }
        runBlocking { BrowserPasswordVault(context).forget(target) }
        context.getSharedPreferences("workspace-browser", 0).edit()
            .remove("url-${com.yanjiyu.terminalspike.ui.project.projectRevision(endpoint)}")
            .remove("desktop-${com.yanjiyu.terminalspike.ui.project.projectRevision(endpoint)}").commit()
        server.close(); executor.shutdownNow()
    }

    @Test fun loginRejectsWrongPasswordAndEncryptedPasswordSurvivesBrowserRecreation() {
        startServer()
        compose.setContent {
            MaterialTheme {
                controller?.let { current ->
                    val tab by current.current.collectAsState()
                    val active by current.active.collectAsState()
                    tab?.let { key(it) { BrowserTestShell(current, it, active != null, current::hide, current::select) } }
                }
            }
        }
        val first = reopen()
        waitForPassword()
        compose.onNodeWithTag("browser-password-input").performTextInput("wrong-password")
        compose.onNodeWithTag("browser-password-confirm").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Sign-in failed. Check the password and try again.").fetchSemanticsNodes().isNotEmpty() }
        assertNull(runBlocking { BrowserPasswordVault(context).read(target) })
        compose.onNodeWithTag("browser-password-input").performTextReplacement(secret)
        compose.onNodeWithTag("browser-password-confirm").performClick()
        compose.waitUntil(10_000) { runBlocking { BrowserPasswordVault(context).read(target) } == secret }
        val ciphertext = context.getSharedPreferences("browser-passwords", 0).all.values.joinToString()
        assertFalse("Stored browser credentials must be encrypted", ciphertext.contains(secret))
        // Session expiry in the same live browser also gets one fresh automatic login.
        compose.runOnIdle { requireNotNull(first.view).loadUrl(address + "login") }
        compose.waitUntil(10_000) { posts.get() == 3 && compose.runOnIdle { first.view?.url == address && first.view?.progress == 100 } }

        // Force a fresh login page: a new controller/WebView must use the encrypted password,
        // independently of the successful session cookie and the old in-memory browser.
        val fresh = reopen()
        compose.runOnIdle { fresh.view?.loadUrl(address + "login") }
        compose.waitUntil(10_000) { posts.get() == 4 && compose.runOnIdle { fresh.view?.url == address && fresh.view?.progress == 100 } }
        compose.onNodeWithTag("browser-password-input").assertDoesNotExist()
        compose.onNodeWithTag("browser-tab-menu").performClick()
        compose.onNodeWithTag("browser-password").performScrollTo().performClick()
        waitForPassword()
        compose.onNodeWithTag("browser-forget-password").performClick()
        compose.waitUntil(10_000) { runBlocking { BrowserPasswordVault(context).read(target) } == null }
        assertEquals(4, posts.get())
    }
}
