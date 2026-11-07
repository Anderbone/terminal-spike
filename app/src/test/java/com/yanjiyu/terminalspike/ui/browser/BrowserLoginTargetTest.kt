package com.yanjiyu.terminalspike.ui.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserLoginTargetTest {
    @Test fun credentialsAreScopedToOriginPortAndProxyInstallation() {
        val target = BrowserLoginTarget.from("https://HOST:443/code/?folder=%2Frepo")
        assertEquals("https://host/code/", target.scope)
        assertTrue(target.isLoginPage("https://host/code/login?to=%2Fcode%2F"))
        assertTrue(target.accepts("https://host/code/?folder=%2Frepo"))
        for (page in listOf("http://host/code/login", "https://other/code/login", "https://host:9443/code/login",
            "https://host/code-evil/login", "https://host/other/login")) assertFalse(page, target.accepts(page))
        assertEquals("https://host/code/", BrowserLoginTarget.from("https://host/code/login").scope)
        assertFalse(target.isLoginPage("https://host/code/"))
    }
}
