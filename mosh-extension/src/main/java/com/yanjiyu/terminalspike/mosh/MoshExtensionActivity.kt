/*
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.yanjiyu.terminalspike.mosh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

public class MoshExtensionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MoshExtensionInformation()
                }
            }
        }
    }
}

@Composable
private fun MoshExtensionInformation() {
    val nativeVersion = remember { runCatching(MoshNativeBridge::version).getOrNull() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Mosh extension",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (nativeVersion == "mosh-1.4.0") {
                "Native engine ready"
            } else {
                "Native engine unavailable"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (nativeVersion == "mosh-1.4.0") {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = "Version ${BuildConfig.VERSION_NAME}. Open Terminal Spike to start a Mosh " +
                "connection; this companion application has no host or credential store.",
            style = MaterialTheme.typography.bodyLarge,
        )

        HorizontalDivider()
        InformationSection(
            title = "Transport",
            body = "Pinned official Mosh 1.4.0 client code. Supports IPv4, IPv6, UDP roaming, " +
                "terminal input/output pipes, resize, cancellation, and four process-isolated sessions.",
        )
        InformationSection(
            title = "Security boundary",
            body = "Terminal Spike performs SSH authentication and trusted server bootstrap. " +
                "Only the numeric UDP endpoint and a one-shot ephemeral Mosh key enter this extension. " +
                "Passwords, private keys, passphrases, and terminal transcripts are never stored here.",
        )
        InformationSection(
            title = "Free software and source",
            body = "This extension is GPL-3.0-or-later software with no warranty. Complete pinned " +
                "source, checksums, modification patches, notices, and reproducible build scripts are " +
                "in the mosh-extension source module supplied with this build.",
        )
        InformationSection(
            title = "Non-affiliation",
            body = "Mosh is a registered trademark. This Mosh-compatible extension is not affiliated " +
                "with or endorsed by the Mosh project.",
        )
    }
}

@Composable
private fun InformationSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(2.dp))
    }
}
