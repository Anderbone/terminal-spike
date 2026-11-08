package com.yanjiyu.terminalspike.localarch

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalBrowserBridgeTest {
    @Test fun defaultBrowserAndChooserReturnActualLaunchResultWithoutRetainingUrls() = runBlocking {
        val bridge = LocalBrowserBridge()
        for (choose in listOf(false, true)) {
            val operation = async(start = CoroutineStart.UNDISPATCHED) {
                bridge.open("https://example.com/authorize?state=one-use", choose)
            }
            val request = requireNotNull(bridge.request.value)
            assertEquals(choose, request.choose)
            request.result.complete(!choose)
            assertEquals(!choose, operation.await())
            assertNull(bridge.request.value)
        }
    }

    @Test fun settingsPreferenceAppliesToCliOpenersAndPersistsOnlyTheChoice() = runBlocking {
        var saved: Boolean? = null
        val bridge = LocalBrowserBridge(saveChooseBrowser = { saved = it })
        bridge.setChooseBrowser(true)
        assertEquals(true, saved)
        val operation = async(start = CoroutineStart.UNDISPATCHED) { bridge.open("http://127.0.0.1:9876/callback") }
        assertTrue(bridge.request.value!!.choose)
        operation.cancel()
        operation.join()
        assertNull(bridge.request.value)
        val next = async(start = CoroutineStart.UNDISPATCHED) { bridge.open("https://example.com") }
        bridge.request.value!!.result.complete(true)
        assertTrue(next.await())
    }

    @Test fun concurrentRequestCannotReplaceThePendingLoginAndInvalidInputsNeverReachUi() = runBlocking {
        val bridge = LocalBrowserBridge()
        val operation = async(start = CoroutineStart.UNDISPATCHED) { bridge.open("https://example.com/first") }
        assertFalse(bridge.open("https://example.com/second"))
        assertEquals("https://example.com/first", bridge.request.value!!.url)
        bridge.request.value!!.result.complete(true)
        assertTrue(operation.await())
        for (url in listOf("file:///etc/passwd", "intent://login", "javascript:alert(1)",
            "https://user:secret@example.com", "https://example.com/\nlogin", "https://example.com:99999", "http://[invalid", "https:///no-host")) {
            try { bridge.open(url); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
            assertNull(bridge.request.value)
        }
        assertTrue(bridge.handle("""{"api":1,"method":"settings.update","params":{}}""").contains("INVALID_ARGUMENT"))
    }

    @Test fun wireProtocolRejectsUnknownFieldsAndDoesNotEchoSensitiveInput() = runBlocking {
        val bridge = LocalBrowserBridge()
        for (params in listOf(
            """{"url":"https://example.com?secret=token","choose":"true"}""",
            """{"url":"https://example.com?secret=token","unknown":true}""",
            """{"url":"file:///secret=token"}""",
        )) {
            val result = bridge.handle("""{"api":1,"method":"browser.open","params":$params}""")
            assertTrue(result.contains("INVALID_ARGUMENT"))
            assertFalse(result.contains("secret=token"))
            assertNull(bridge.request.value)
        }
    }
}
