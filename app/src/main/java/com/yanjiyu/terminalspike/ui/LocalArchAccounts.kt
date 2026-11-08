package com.yanjiyu.terminalspike.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.localarch.ArchLoginProvider

@Composable
internal fun LocalArchAccounts() {
    val context = LocalContext.current
    val repository = remember(context) { (context.applicationContext as TerminalSpikeApplication).container.localSessionRepository }
    val browser = remember(context) { (context.applicationContext as TerminalSpikeApplication).container.localBrowser }
    val chooseBrowser by browser.chooseBrowser.collectAsStateWithLifecycle()
    val environment by repository.environment.state.collectAsStateWithLifecycle()
    val runtime by repository.runtime.collectAsStateWithLifecycle()
    val login by repository.login.collectAsStateWithLifecycle()
    var browserFailed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.local_arch_accounts_detail))
        Row(
            Modifier.fillMaxWidth().toggleable(value = chooseBrowser, role = Role.Switch, onValueChange = browser::setChooseBrowser),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.local_arch_choose_browser_always), Modifier.weight(1f))
            Switch(checked = chooseBrowser, onCheckedChange = null)
        }
        ArchLoginProvider.entries.forEach { provider ->
            OutlinedButton(
                onClick = { browserFailed = false; repository.startLogin(provider) },
                enabled = environment.starterToolsInstalled && !environment.busy && !runtime.installationActive && !login.active,
                modifier = Modifier.testTag("local-arch-login-${provider.name.lowercase()}"),
            ) { Text(stringResource(R.string.local_arch_sign_in, provider.label)) }
        }
        if (login.active) {
            val code = login.code
            if (login.provider == ArchLoginProvider.CLAUDE) {
                Text(stringResource(R.string.local_arch_claude_login_waiting))
                val input = remember { WipeableSecretInputState() }
                WipeableSecretInput(
                    state = input,
                    label = stringResource(R.string.local_arch_login_return_code),
                    testTag = "local-arch-login-return-code",
                    maxCharacters = 4096,
                )
                TextButton(
                    onClick = { repository.submitLoginCode(input.takeChars()) },
                    enabled = input.hasValue,
                ) { Text(stringResource(R.string.local_arch_login_submit_code)) }
            } else if (code == null) Text(stringResource(R.string.local_arch_login_starting))
            else {
                Text(stringResource(R.string.local_arch_login_code, code), Modifier.testTag("local-arch-login-code"))
                OutlinedButton(onClick = {
                    browserFailed = runCatching {
                        openLocalBrowser(context, login.provider!!.verificationUrl, choose = chooseBrowser, code = code)
                    }.isFailure
                }) { Text(stringResource(R.string.local_arch_login_browser)) }
                TextButton(onClick = {
                    browserFailed = runCatching {
                        openLocalBrowser(context, login.provider!!.verificationUrl, choose = true, code = code)
                    }.isFailure
                }) { Text(stringResource(R.string.local_arch_login_choose_browser)) }
            }
            TextButton(onClick = repository::cancelLogin) { Text(stringResource(R.string.cancel)) }
        }
        if (login.succeeded) Text(stringResource(R.string.local_arch_login_success, login.provider!!.label))
        if (login.failed) Text(stringResource(R.string.local_arch_login_failed))
        if (browserFailed) Text(stringResource(R.string.local_arch_login_browser_failed))
    }
}
