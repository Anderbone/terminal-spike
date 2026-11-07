package com.yanjiyu.terminalspike.ui.browser

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.ui.SessionChrome
import com.yanjiyu.terminalspike.ui.SessionTabUi

/** Use the actual shared terminal tab bar when exercising the embedded browser. */
@Composable
internal fun BrowserTestShell(controller: BrowserController, tab: BrowserTab, visible: Boolean,
    onTerminal: () -> Unit, onBrowser: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SessionChrome(
            sessions = listOf(SessionTabUi(1, "Terminal", ConnectionState.Connected)),
            activeSessionId = 1, notice = null, canAddSession = false, settingsReady = true,
            onSelectSession = { onTerminal() }, onDuplicateSession = {}, onCloseSession = {}, onDisconnect = {},
            onHostIdentityAnswer = { _, _, _ -> }, onNavigateBack = {}, onNewSession = {}, onOpenConnections = {},
            browserTitle = "Code server".takeIf { tab.codeServer }, browserSelected = visible && tab.codeServer, onSelectBrowser = onBrowser,
            webBrowserTitle = "Browser".takeIf { !tab.codeServer }, webBrowserSelected = visible && !tab.codeServer,
            onSelectWebBrowser = onBrowser, onCloseWebBrowser = { controller.closeTab(); onTerminal() },
            onWebBrowserActions = controller::showMenu,
            onCloseBrowser = { controller.closeTab(); onTerminal() }, onBrowserActions = controller::showMenu,
        )
        if (visible) BrowserWorkspace(controller, tab, modifier = Modifier.weight(1f))
        else Text("Terminal test surface", modifier = Modifier.weight(1f).testTag("browser-test-terminal"))
    }
}
