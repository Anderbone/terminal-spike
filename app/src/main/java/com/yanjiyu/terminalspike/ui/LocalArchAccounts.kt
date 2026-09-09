package com.yanjiyu.terminalspike.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    val environment by repository.environment.state.collectAsStateWithLifecycle()
    val runtime by repository.runtime.collectAsStateWithLifecycle()
    val login by repository.login.collectAsStateWithLifecycle()
    var browserFailed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.local_arch_accounts_detail))
        ArchLoginProvider.entries.forEach { provider ->
            OutlinedButton(
                onClick = { browserFailed = false; repository.startLogin(provider) },
                enabled = environment.starterToolsInstalled && !environment.busy && !runtime.installationActive && !login.active,
                modifier = Modifier.testTag("local-arch-login-${provider.name.lowercase()}"),
            ) { Text(stringResource(R.string.local_arch_sign_in, provider.label)) }
        }
        if (login.active) {
            val code = login.code
            if (code == null) Text(stringResource(R.string.local_arch_login_starting))
            else {
                Text(stringResource(R.string.local_arch_login_code, code), Modifier.testTag("local-arch-login-code"))
                OutlinedButton(onClick = {
                    val clip = ClipData.newPlainText("Device login code", code)
                    clip.description.extras = android.os.PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    browserFailed = runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(login.provider!!.verificationUrl)))
                    }.isFailure
                }) { Text(stringResource(R.string.local_arch_login_browser)) }
            }
            TextButton(onClick = repository::cancelLogin) { Text(stringResource(R.string.cancel)) }
        }
        if (login.succeeded) Text(stringResource(R.string.local_arch_login_success, login.provider!!.label))
        if (login.failed) Text(stringResource(R.string.local_arch_login_failed))
        if (browserFailed) Text(stringResource(R.string.local_arch_login_browser_failed))
    }
}
