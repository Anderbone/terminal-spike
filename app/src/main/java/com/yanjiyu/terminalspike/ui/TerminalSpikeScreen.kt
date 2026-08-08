package com.yanjiyu.terminalspike.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanjiyu.terminalspike.performance.PerformanceSnapshot
import com.yanjiyu.terminalspike.settings.SavedSshProfile
import com.yanjiyu.terminalspike.terminal.view.TerminalViewBridge
import java.util.Locale

@Composable
fun TerminalSpikeScreen(
    viewModel: TerminalSpikeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activeController = viewModel.controllerFor(state.activeSessionId)
    val performance by activeController.performance.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val rootView = LocalView.current
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    var toolsSection by remember { mutableStateOf(ToolSection.PROFILES) }
    var connectDialogVisible by remember { mutableStateOf(false) }
    var connectProfile by remember { mutableStateOf<SavedSshProfile?>(null) }
    var destination by rememberSaveable { mutableStateOf(AppDestination.WORKSPACE) }
    var toolsReturnDestination by rememberSaveable { mutableStateOf(AppDestination.WORKSPACE) }
    val identityImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSshIdentity(uri)
    }

    fun showTools(section: ToolSection) {
        toolsSection = section
        if (destination != AppDestination.TOOLS) toolsReturnDestination = destination
        destination = AppDestination.TOOLS
    }

    fun showConnect(profile: SavedSshProfile? = null) {
        connectProfile = profile
        connectDialogVisible = true
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAppVisible(true)
                Lifecycle.Event.ON_STOP -> viewModel.onAppVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.onAppVisible(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val activeConnection = state.activeSession.connectionState
    DisposableEffect(rootView, state.isRunning, state.isPreloading, activeConnection) {
        rootView.keepScreenOn =
            state.isRunning || state.isPreloading || activeConnection is com.yanjiyu.terminalspike.connection.ConnectionState.Connected
        onDispose { rootView.keepScreenOn = false }
    }

    BackHandler(enabled = destination != AppDestination.WORKSPACE) {
        destination = if (destination == AppDestination.TOOLS) toolsReturnDestination else AppDestination.WORKSPACE
    }

    AnimatedContent(
        targetState = destination,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            (fadeIn() + slideInHorizontally(initialOffsetX = { it / 10 })).togetherWith(
                fadeOut() + slideOutHorizontally(targetOffsetX = { -it / 10 }),
            )
        },
        label = "primary-workspace",
    ) { target ->
        when (target) {
            AppDestination.WORKSPACE -> LocalWorkspaceScreen(
                profiles = state.profiles,
                identities = state.identities,
                snippets = state.snippets,
                knownHostCount = state.knownHosts.size,
                visibleKeyCount = state.extraKeys.size,
                settingsReady = state.settingsReady,
                onOpenSection = ::showTools,
                onOpenTerminal = { destination = AppDestination.TERMINAL },
                onQuickConnect = {
                    destination = AppDestination.TERMINAL
                    showConnect()
                },
                onOpenTools = { showTools(ToolSection.PROFILES) },
            )
            AppDestination.TERMINAL -> Scaffold(
                modifier = Modifier.fillMaxSize().imePadding(),
                containerColor = MaterialTheme.colorScheme.background,
            ) { innerPadding ->
                Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    SessionChrome(
                        sessions = state.sessions,
                        activeSessionId = state.activeSessionId,
                        notice = state.notice,
                        canAddSession = state.canAddSshSession,
                        settingsReady = state.settingsReady,
                        onSelectSession = viewModel::selectSession,
                        onCloseSession = viewModel::closeSession,
                        onDisconnect = viewModel::disconnectSsh,
                        onHostKeyAnswer = viewModel::answerHostKeyPrompt,
                        onNavigateBack = { destination = AppDestination.WORKSPACE },
                        onNewSession = { showConnect() },
                        onShowTools = { showTools(ToolSection.PROFILES) },
                    )
                    if (!state.sshMode) {
                        TerminalControls(
                            state = state,
                            autoFollow = performance.autoFollow,
                            onModeSelected = viewModel::selectMode,
                            onStreamingRateSelected = viewModel::selectStreamingRate,
                            onFullScreenRateSelected = viewModel::selectFullScreenRate,
                            onPreloadSelected = viewModel::selectPreloadSize,
                            onStart = viewModel::start,
                            onStop = viewModel::stop,
                            onPreload = viewModel::preload,
                            onClear = viewModel::clear,
                            onJumpToBottom = viewModel::jumpToBottom,
                            onPerformanceVisible = viewModel::setPerformanceVisible,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        TerminalViewBridge(controller = activeController)
                        if (state.showPerformance && !state.sshMode) {
                            PerformanceOverlay(
                                snapshot = performance,
                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                            )
                        }
                    }
                    ExtraKeysBar(
                        keys = state.extraKeys,
                        ctrlArmed = state.ctrlArmed,
                        altArmed = state.altArmed,
                        customizationEnabled = state.settingsReady,
                        onKey = { key ->
                            if (key.isLocalAction) {
                                softwareKeyboardController?.hide()
                            } else {
                                viewModel.activateExtraKey(key)
                            }
                        },
                        onCustomize = { showTools(ToolSection.KEYS) },
                    )
                }
            }
            AppDestination.TOOLS -> LocalToolsScreen(
                profiles = state.profiles,
                identities = state.identities,
                knownHosts = state.knownHosts,
                snippets = state.snippets,
                extraKeys = state.extraKeys,
                onNavigateBack = { destination = toolsReturnDestination },
                onOpenWorkspace = { destination = AppDestination.WORKSPACE },
                onOpenTerminal = { destination = AppDestination.TERMINAL },
                onUseProfile = { profile ->
                    destination = AppDestination.TERMINAL
                    showConnect(profile)
                },
                onSaveProfile = viewModel::saveSshProfile,
                onDeleteProfile = viewModel::deleteSshProfile,
                onImportIdentity = {
                    identityImportLauncher.launch(
                        arrayOf("application/x-pem-file", "application/octet-stream", "text/plain"),
                    )
                },
                onDeleteIdentity = viewModel::deleteSshIdentity,
                onForgetKnownHost = viewModel::forgetKnownHost,
                onSaveSnippet = viewModel::saveSnippet,
                onDeleteSnippet = viewModel::deleteSnippet,
                onSendSnippet = { id ->
                    viewModel.sendSnippet(id)
                    destination = AppDestination.TERMINAL
                },
                onSetKeyVisible = viewModel::setExtraKeyVisible,
                onReplaceKey = viewModel::replaceExtraKey,
                onMoveKey = viewModel::moveExtraKey,
                onResetKeys = viewModel::resetExtraKeys,
                onSaveKeys = {
                    viewModel.saveExtraKeys()
                    destination = toolsReturnDestination
                },
                initialSection = toolsSection,
            )
        }
    }

    if (connectDialogVisible) {
        SshConnectDialog(
            profiles = state.profiles,
            identities = state.identities,
            initialProfile = connectProfile,
            settingsReady = state.settingsReady,
            onDismiss = {
                connectDialogVisible = false
                connectProfile = null
            },
            onConnect = {
                    host, port, username, password, identityId, passphrase, saveProfile,
                    savedPasswordProfileId, savePassword,
                ->
                connectDialogVisible = false
                connectProfile = null
                destination = AppDestination.TERMINAL
                viewModel.connectSsh(
                    host,
                    port,
                    username,
                    password,
                    identityId,
                    passphrase,
                    saveProfile,
                    savedPasswordProfileId,
                    savePassword,
                )
            },
            onForgetSavedPassword = viewModel::forgetSavedPassword,
        )
    }
}

@Composable
private fun PerformanceOverlay(
    snapshot: PerformanceSnapshot,
    modifier: Modifier = Modifier,
) {
    val timing = snapshot.timing
    val text = String.format(
        Locale.US,
        "Renderer %s\n%.1f draws/s  avg %.2f ms  p95 %.2f ms\nCPU >8.3 ms %d   >16.7 ms %d\nscrollback %,d  visible %d  pending %,d\nheap %.1f MiB  follow %s\n%s · %s",
        if (timing.idle) "idle" else "diagnostic",
        timing.drawsPerSecond,
        timing.averageDrawMs,
        timing.p95DrawMs,
        timing.drawsSlowerThan8Ms,
        timing.drawsSlowerThan16Ms,
        snapshot.scrollbackLines,
        snapshot.visibleLines,
        snapshot.pendingOutputLines,
        snapshot.heapMegabytes,
        if (snapshot.autoFollow) "on" else "off",
        snapshot.workload,
        snapshot.rate,
    )
    Surface(
        modifier = modifier.widthIn(max = 390.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
