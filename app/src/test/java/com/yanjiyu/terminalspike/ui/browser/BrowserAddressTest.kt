package com.yanjiyu.terminalspike.ui.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserAddressTest {
    @Test fun pastedTailscaleAddressDiscardsInvisibleClipboardFormatting() {
        val url = "https://epee.tail719241.ts.net:9443"
        assertEquals(url, browserAddress(url))
        assertEquals(url, browserAddress("\u2068$url\u2069"))
        assertEquals(url, browserAddress("\uFEFF$url\u200B\u2060"))
        assertEquals(url, browserAddress("https://\u200Eepee.tail719241.ts.net:9443"))
        assertThrows(IllegalArgumentException::class.java) { browserAddress("https://host:not-a-port") }
    }

    @Test fun acceptsTailscaleHttpsHttpIpv6AndProxyPaths() {
        assertEquals("https://laptop.tailnet.ts.net:9443/code/", browserAddress(" laptop.tailnet.ts.net:9443/code/ "))
        assertEquals("http://100.64.1.2:8080/", browserAddress("http://100.64.1.2:8080/"))
        assertEquals("http://[::1]:8080/", browserAddress("http://[::1]:8080/"))
        for (bad in listOf("file:///etc/passwd", "javascript:alert(1)", "https://user:secret@host/", "https://host:0", "https://host:99999", "https://host/with space", "https:///")) {
            assertThrows(bad, IllegalArgumentException::class.java) { browserAddress(bad) }
        }
    }

    @Test fun projectLinksEncodeOpaquePathsAndReplaceExistingWorkspace() {
        val link = browserProjectAddress("https://host/code/?theme=dark&folder=old&workspace=old#view", "/a space/项目&x")
        assertEquals("https://host/code/?theme=dark&folder=%2Fa+space%2F%E9%A1%B9%E7%9B%AE%26x#view", link)
        assertEquals("https://host/code/", browserProjectAddress("https://host/code/", null))
    }

    @Test fun navigationAllowsHttpsAndOnlyExplicitPlaintextOrigin() {
        val entry = "http://100.64.1.2:8080/code/"
        assertTrue(browserNavigationAllowed("http://100.64.1.2:8080/login", entry))
        assertTrue(browserNavigationAllowed("https://identity.example/login", entry))
        assertFalse(browserNavigationAllowed("http://100.64.1.2:8081/login", entry))
        assertFalse(browserNavigationAllowed("http://other/login", entry))
        assertFalse(browserNavigationAllowed("http://host/", "https://host/"))
        assertFalse(browserNavigationAllowed("intent://host/", entry))
        assertFalse(browserNavigationAllowed("content://provider/file", entry))
    }

    @Test fun desktopAgentKeepsChromiumVersionAndRemovesMobileMarkers() {
        val mobile = "Mozilla/5.0 (Linux; Android 16; Pixel Build/X; wv) AppleWebKit/537.36 Version/4.0 Chrome/140.0.0.0 Mobile Safari/537.36"
        val desktop = desktopBrowserAgent(mobile)
        assertTrue(desktop.contains("X11; Linux x86_64"))
        assertTrue(desktop.contains("Chrome/140.0.0.0"))
        assertFalse(desktop.contains("Android"))
        assertFalse(desktop.contains("Mobile"))
        assertFalse(desktop.contains("; wv"))
    }
}
