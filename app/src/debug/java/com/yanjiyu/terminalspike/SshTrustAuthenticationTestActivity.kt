package com.yanjiyu.terminalspike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf

/** Debug-only Activity host used to verify prompt ownership across real Activity recreation. */
class SshTrustAuthenticationTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SshTrustAuthenticationTestContent.content.value?.invoke()
        }
    }
}

internal object SshTrustAuthenticationTestContent {
    val content = mutableStateOf<(@Composable () -> Unit)?>(null)
}
