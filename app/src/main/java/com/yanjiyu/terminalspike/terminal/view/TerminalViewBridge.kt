package com.yanjiyu.terminalspike.terminal.view

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import com.yanjiyu.terminalspike.terminal.TerminalController

@Composable
fun TerminalViewBridge(
    controller: TerminalController,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            FastTerminalView(context).apply { attachController(controller) }
        },
        update = { view -> view.attachController(controller) },
        modifier = modifier
            .fillMaxSize()
            .testTag("terminal_container")
            .semantics { contentDescription = "Terminal renderer" },
    )
}
