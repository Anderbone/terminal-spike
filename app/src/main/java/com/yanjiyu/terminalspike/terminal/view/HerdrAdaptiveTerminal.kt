package com.yanjiyu.terminalspike.terminal.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.connection.HerdrSidebarLayout

@Composable
internal fun HerdrAdaptiveTerminal(
    layout: HerdrSidebarLayout?,
    sessionKey: Any,
    modifier: Modifier = Modifier,
    content: @Composable (hiddenSidebarColumns: Int) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val compact = maxWidth < 600.dp
        val available = layout != null && layout.sidebarColumns > 0
        var expanded by remember(sessionKey, compact, available) { mutableStateOf(false) }
        val showToggle = compact && available
        Column(Modifier.fillMaxSize()) {
            if (showToggle) {
                val description = stringResource(
                    if (expanded) R.string.herdr_hide_sidebar else R.string.herdr_show_sidebar,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.testTag("herdr_sidebar_toggle")
                            .semantics { contentDescription = description },
                    ) {
                        Text(if (expanded) "‹" else "›")
                    }
                    Text(stringResource(R.string.herdr_sidebar_label))
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                content(if (showToggle && !expanded) layout.sidebarColumns else 0)
            }
        }
    }
}
