package com.yanjiyu.terminalspike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.yanjiyu.terminalspike.ui.TerminalSpikeScreen
import com.yanjiyu.terminalspike.ui.TerminalSpikeViewModel
import com.yanjiyu.terminalspike.ui.theme.TerminalSpikeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: TerminalSpikeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TerminalSpikeTheme {
                TerminalSpikeScreen(viewModel = viewModel)
            }
        }
    }
}
