package com.yanjiyu.terminalspike.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.Window
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.yanjiyu.terminalspike.BuildConfig
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import com.yanjiyu.terminalspike.connection.ConnectionState
import com.yanjiyu.terminalspike.connection.TmuxAvailability
import com.yanjiyu.terminalspike.connection.TmuxSessionCatalog
import com.yanjiyu.terminalspike.core.model.ConnectionProtocol
import com.yanjiyu.terminalspike.core.model.MoshFallbackPolicy
import com.yanjiyu.terminalspike.terminal.view.TerminalClipboardActionCallback
import com.yanjiyu.terminalspike.terminal.view.TerminalClipboardWriter
import com.yanjiyu.terminalspike.terminal.view.TerminalImageContentCallback
import com.yanjiyu.terminalspike.terminal.view.TerminalImagePasteSource
import com.yanjiyu.terminalspike.terminal.view.AccessoryModifierSnapshot
import com.yanjiyu.terminalspike.terminal.view.TerminalAccessoryAction
import com.yanjiyu.terminalspike.terminal.view.TerminalLocalAccessoryAction
import com.yanjiyu.terminalspike.terminal.view.TerminalViewBridge
import com.yanjiyu.terminalspike.terminal.view.accessoryModifierState
import com.yanjiyu.terminalspike.terminal.view.rememberTerminalInputFocusRequester
import com.yanjiyu.terminalspike.ui.connections.ConnectionsCallbacks
import com.yanjiyu.terminalspike.ui.connections.ConnectionsLoadState
import com.yanjiyu.terminalspike.ui.connections.ConnectionsScreen
import com.yanjiyu.terminalspike.ui.connections.ConnectionsTab
import com.yanjiyu.terminalspike.ui.connections.HostAuthenticationPromptDialog
import com.yanjiyu.terminalspike.ui.connections.HostConnectRequest
import com.yanjiyu.terminalspike.ui.connections.SavedConnectionPickerDialog
import com.yanjiyu.terminalspike.ui.connections.requiresConnectionPrompt
import com.yanjiyu.terminalspike.ui.terminal.TERMINAL_TRANSCRIPT_FILE_NAME
import com.yanjiyu.terminalspike.ui.terminal.TERMINAL_TRANSCRIPT_MIME_TYPE
import com.yanjiyu.terminalspike.ui.terminal.TerminalSessionActions
import com.yanjiyu.terminalspike.ui.terminal.TerminalBellEffect
import com.yanjiyu.terminalspike.ui.terminal.TerminalFindDialog
import com.yanjiyu.terminalspike.ui.terminal.TerminalSnippetPickerDialog
import com.yanjiyu.terminalspike.ui.terminal.writeTerminalTranscriptDocument
import com.yanjiyu.terminalspike.ui.sftp.SftpScreen
import com.yanjiyu.terminalspike.ui.sftp.SftpUiState
import com.yanjiyu.terminalspike.ui.theme.iconMetrics
import com.yanjiyu.terminalspike.ui.theme.spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class TerminalOwner(
    val destination: AppRoute,
    @StringRes val labelRes: Int,
) {
    WORKSPACE(AppRoute.WORKSPACE, R.string.navigation_connections),
    CONNECTIONS(AppRoute.CONNECTIONS, R.string.connections_title),
    SETTINGS(AppRoute.SETTINGS, R.string.navigation_settings),
}

internal const val TerminalEmptyStateTestTag = "terminal-empty-state"
private const val MAX_SELECTED_TERMINAL_IMAGES = 20

@Composable
internal fun TerminalEmptyState(
    canOpenConnection: Boolean,
    onOpenConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 360.dp)
            .padding(MaterialTheme.spacing.extraLarge)
            .testTag(TerminalEmptyStateTestTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        Surface(
            modifier = Modifier.size(80.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Box(contentAlignment = Alignment.Center) {
                AppGlyphIcon(
                    glyph = AppGlyph.TERMINAL,
                    modifier = Modifier.size(MaterialTheme.iconMetrics.prominent),
                )
            }
        }
        Text(
            text = stringResource(R.string.terminal_no_session_open),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.terminal_no_session_detail),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onOpenConnection,
            enabled = canOpenConnection,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Text(stringResource(R.string.terminal_saved_connection_picker_title))
        }
    }
}

internal fun terminalOwnerForEntry(
    source: AppRoute,
    currentOwner: TerminalOwner,
): TerminalOwner = when (source) {
    AppRoute.CONNECTIONS -> TerminalOwner.CONNECTIONS
    AppRoute.SETTINGS -> TerminalOwner.SETTINGS
    AppRoute.TERMINAL_DETAIL -> currentOwner
    AppRoute.WORKSPACE -> TerminalOwner.WORKSPACE
}

internal fun catalogReturnDestinationForEntry(source: AppRoute): AppRoute = when (source) {
    AppRoute.CONNECTIONS -> AppRoute.WORKSPACE
    else -> source
}

internal fun shellBackDestination(
    current: AppRoute,
    terminalOwner: TerminalOwner,
    catalogReturnDestination: AppRoute = AppRoute.WORKSPACE,
    settingsReturnDestination: AppRoute = AppRoute.WORKSPACE,
): AppRoute? = when (current) {
    AppRoute.WORKSPACE -> null
    AppRoute.CONNECTIONS -> catalogReturnDestination
    AppRoute.SETTINGS -> settingsReturnDestination
    AppRoute.TERMINAL_DETAIL -> terminalOwner.destination
}

internal enum class ShellBackSwipeEdge {
    LEFT,
    RIGHT,
}

internal data class ShellBackPreview(
    val target: AppRoute,
    val progress: Float,
    val translationFraction: Float,
    val scale: Float,
    val destinationAlpha: Float,
)

internal fun shouldOfferFreshSshFallback(
    session: SessionTabUi,
    dismissedSessionId: Long?,
): Boolean = session.id != dismissedSessionId &&
    session.protocol == ConnectionProtocol.MOSH &&
    session.moshFallbackPolicy != MoshFallbackPolicy.NEVER &&
    (session.connectionState as? ConnectionState.Failed)?.moshFallbackFailure != null

internal fun SshConnectionSeed.toFreshSshFallbackSeed(): SshConnectionSeed = copy(
    sourceProfileId = null,
    connectionOptions = RemoteConnectionOptions.SSH,
)

internal data class ShellBackGesture(
    val origin: AppRoute,
    val target: AppRoute,
    val preview: ShellBackPreview? = null,
)

internal data class ShellBackGestureResolution(
    val destination: AppRoute,
    val preview: ShellBackPreview?,
)

internal fun startShellBackGesture(
    current: AppRoute,
    terminalOwner: TerminalOwner,
    catalogReturnDestination: AppRoute = AppRoute.WORKSPACE,
    settingsReturnDestination: AppRoute = AppRoute.WORKSPACE,
): ShellBackGesture? = shellBackDestination(
    current = current,
    terminalOwner = terminalOwner,
    catalogReturnDestination = catalogReturnDestination,
    settingsReturnDestination = settingsReturnDestination,
)?.let { target ->
    ShellBackGesture(origin = current, target = target)
}

internal fun progressShellBackGesture(
    gesture: ShellBackGesture,
    rawProgress: Float,
    edge: ShellBackSwipeEdge,
): ShellBackGesture {
    val progress = when {
        rawProgress.isNaN() || rawProgress <= 0f -> 0f
        rawProgress >= 1f -> 1f
        else -> rawProgress
    }
    val easedProgress = progress * progress * (3f - 2f * progress)
    val direction = if (edge == ShellBackSwipeEdge.LEFT) 1f else -1f
    return gesture.copy(
        preview = ShellBackPreview(
            target = gesture.target,
            progress = progress,
            translationFraction = direction * 0.08f * easedProgress,
            scale = 1f - 0.04f * easedProgress,
            destinationAlpha = easedProgress,
        ),
    )
}

internal fun finishShellBackGesture(
    gesture: ShellBackGesture,
    completed: Boolean,
): ShellBackGestureResolution = ShellBackGestureResolution(
    destination = if (completed) gesture.target else gesture.origin,
    preview = null,
)

internal const val PredictiveBackDestinationPreviewTestTag = "predictive-back-destination-preview"

@StringRes
private fun AppRoute.shellLabelRes(): Int = when (this) {
    AppRoute.WORKSPACE -> R.string.navigation_connections
    AppRoute.CONNECTIONS -> R.string.connections_title
    AppRoute.SETTINGS -> R.string.navigation_settings
    AppRoute.TERMINAL_DETAIL -> R.string.navigation_terminal
}

@Composable
internal fun PredictiveBackDestinationPreview(
    preview: ShellBackPreview,
    modifier: Modifier = Modifier,
) {
    val alignment = if (preview.translationFraction >= 0f) Alignment.CenterStart else Alignment.CenterEnd
    val targetLabel = stringResource(preview.target.shellLabelRes())
    val previewDescription = stringResource(R.string.terminal_predictive_back_preview, targetLabel)
    Box(
        modifier = modifier.semantics {
            contentDescription = previewDescription
        },
    ) {
        Surface(
            modifier = Modifier
                .align(alignment)
                .padding(12.dp)
                .graphicsLayer { alpha = preview.destinationAlpha }
                .testTag(PredictiveBackDestinationPreviewTestTag),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 6.dp,
        ) {
            Text(
                text = stringResource(R.string.terminal_predictive_back_destination, targetLabel),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
internal fun ShellPredictiveBackHandler(
    currentRoute: AppRoute,
    terminalOwner: TerminalOwner,
    catalogReturnDestination: AppRoute = AppRoute.WORKSPACE,
    settingsReturnDestination: AppRoute = AppRoute.WORKSPACE,
    enabled: Boolean = true,
    onPreviewChanged: (ShellBackPreview?) -> Unit,
    onNavigate: (AppRoute) -> Unit,
) {
    PredictiveBackHandler(enabled = enabled && currentRoute != AppRoute.WORKSPACE) { events ->
        val gesture = startShellBackGesture(
            current = currentRoute,
            terminalOwner = terminalOwner,
            catalogReturnDestination = catalogReturnDestination,
            settingsReturnDestination = settingsReturnDestination,
        )
            ?: return@PredictiveBackHandler
        var latestGesture = gesture
        var completed = false
        try {
            events.collect { event ->
                latestGesture = progressShellBackGesture(
                    gesture = latestGesture,
                    rawProgress = event.progress,
                    edge = if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) {
                        ShellBackSwipeEdge.RIGHT
                    } else {
                        ShellBackSwipeEdge.LEFT
                    },
                )
                onPreviewChanged(latestGesture.preview)
            }
            completed = true
        } finally {
            val resolution = finishShellBackGesture(latestGesture, completed)
            onPreviewChanged(resolution.preview)
            if (completed && currentRoute == gesture.origin) {
                onNavigate(resolution.destination)
            }
        }
    }
}

@Composable
private fun TerminalPriorityBackHandler(
    currentRoute: AppRoute,
    terminalOwner: TerminalOwner,
    catalogReturnDestination: AppRoute,
    settingsReturnDestination: AppRoute,
    focusMode: Boolean,
    onPreviewChanged: (ShellBackPreview?) -> Unit,
    onExitFocusMode: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    TerminalPriorityBackHandlerApi33(
        currentRoute = currentRoute,
        terminalOwner = terminalOwner,
        catalogReturnDestination = catalogReturnDestination,
        settingsReturnDestination = settingsReturnDestination,
        focusMode = focusMode,
        onPreviewChanged = onPreviewChanged,
        onExitFocusMode = onExitFocusMode,
        onNavigate = onNavigate,
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun TerminalPriorityBackHandlerApi33(
    currentRoute: AppRoute,
    terminalOwner: TerminalOwner,
    catalogReturnDestination: AppRoute,
    settingsReturnDestination: AppRoute,
    focusMode: Boolean,
    onPreviewChanged: (ShellBackPreview?) -> Unit,
    onExitFocusMode: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
) {
    val view = LocalView.current
    val currentOnPreviewChanged by rememberUpdatedState(onPreviewChanged)
    val currentOnExitFocusMode by rememberUpdatedState(onExitFocusMode)
    val currentOnNavigate by rememberUpdatedState(onNavigate)

    DisposableEffect(
        view,
        currentRoute,
        terminalOwner,
        catalogReturnDestination,
        settingsReturnDestination,
        focusMode,
    ) {
        if (currentRoute != AppRoute.TERMINAL_DETAIL) return@DisposableEffect onDispose {}
        val dispatcher = view.findOnBackInvokedDispatcher()
            ?: return@DisposableEffect onDispose {}
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            terminalBackAnimationCallbackApi34(
                terminalOwner = terminalOwner,
                catalogReturnDestination = catalogReturnDestination,
                settingsReturnDestination = settingsReturnDestination,
                focusMode = focusMode,
                onPreviewChanged = { currentOnPreviewChanged(it) },
                onExitFocusMode = { currentOnExitFocusMode() },
                onNavigate = { currentOnNavigate(it) },
            )
        } else {
            OnBackInvokedCallback {
                if (focusMode) {
                    currentOnExitFocusMode()
                } else {
                    shellBackDestination(
                        current = AppRoute.TERMINAL_DETAIL,
                        terminalOwner = terminalOwner,
                        catalogReturnDestination = catalogReturnDestination,
                        settingsReturnDestination = settingsReturnDestination,
                    )?.let { currentOnNavigate(it) }
                }
            }
        }
        dispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        onDispose {
            dispatcher.unregisterOnBackInvokedCallback(callback)
            currentOnPreviewChanged(null)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun terminalBackAnimationCallbackApi34(
    terminalOwner: TerminalOwner,
    catalogReturnDestination: AppRoute,
    settingsReturnDestination: AppRoute,
    focusMode: Boolean,
    onPreviewChanged: (ShellBackPreview?) -> Unit,
    onExitFocusMode: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
): OnBackAnimationCallback = object : OnBackAnimationCallback {
    private var gesture: ShellBackGesture? = null

    override fun onBackStarted(backEvent: BackEvent) {
        if (!focusMode) {
            gesture = startShellBackGesture(
                current = AppRoute.TERMINAL_DETAIL,
                terminalOwner = terminalOwner,
                catalogReturnDestination = catalogReturnDestination,
                settingsReturnDestination = settingsReturnDestination,
            )
        }
    }

    override fun onBackProgressed(backEvent: BackEvent) {
        val startedGesture = gesture ?: return
        gesture = progressShellBackGesture(
            gesture = startedGesture,
            rawProgress = backEvent.progress,
            edge = if (backEvent.swipeEdge == BackEvent.EDGE_RIGHT) {
                ShellBackSwipeEdge.RIGHT
            } else {
                ShellBackSwipeEdge.LEFT
            },
        ).also { onPreviewChanged(it.preview) }
    }

    override fun onBackCancelled() {
        gesture = null
        onPreviewChanged(null)
    }

    override fun onBackInvoked() {
        if (focusMode) {
            onExitFocusMode()
        } else {
            val target = gesture?.let { finishShellBackGesture(it, completed = true).destination }
                ?: shellBackDestination(
                    current = AppRoute.TERMINAL_DETAIL,
                    terminalOwner = terminalOwner,
                    catalogReturnDestination = catalogReturnDestination,
                    settingsReturnDestination = settingsReturnDestination,
                )
            target?.let(onNavigate)
        }
        gesture = null
        onPreviewChanged(null)
    }
}

@Composable
internal fun TerminalDetailInsetContainer(
    modifier: Modifier = Modifier,
    safeContentInsets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(
                safeContentInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .imePadding(),
    ) {
        content()
    }
}

internal fun interface TerminalSystemBarsController {
    fun setTerminalImmersive(enabled: Boolean)
}

private class AndroidTerminalSystemBarsController(
    private val window: Window,
    private val view: View,
) : TerminalSystemBarsController {
    override fun setTerminalImmersive(enabled: Boolean) {
        WindowCompat.getInsetsController(window, view).apply {
            if (enabled) {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.statusBars())
                show(WindowInsetsCompat.Type.navigationBars())
            } else {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@Composable
internal fun TerminalSystemBarsEffect(
    immersive: Boolean,
    controller: TerminalSystemBarsController?,
) {
    DisposableEffect(controller, immersive) {
        controller?.setTerminalImmersive(immersive)
        onDispose {
            if (immersive) controller?.setTerminalImmersive(false)
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.findActivity()
    else -> null
}

/** Primary destinations share one visual footer, so swapping their content must not animate it. */
internal fun keepsPrimaryNavigationStable(initial: AppRoute, target: AppRoute): Boolean =
    initial != AppRoute.TERMINAL_DETAIL && target != AppRoute.TERMINAL_DETAIL

@Composable
fun TerminalSpikeScreen(
    viewModel: TerminalSpikeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val connectionsState by viewModel.connectionsUiState.collectAsStateWithLifecycle()
    val sftpState by viewModel.sftp.state.collectAsStateWithLifecycle()
    val activeController = viewModel.controllerFor(state.activeSessionId)
    val performance by activeController.performance.collectAsStateWithLifecycle()
    val terminalBuildKeepsScreenOn by viewModel.terminalBuildFeature.keepScreenOn.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val rootView = LocalView.current
    val clipboardManager = remember(rootView) {
        rootView.context.getSystemService(ClipboardManager::class.java)
    }
    val terminalClipboardWriter = remember(rootView) {
        val scheduler = (rootView.context.applicationContext as? TerminalSpikeApplication)
            ?.container
            ?.terminalClipboardClearScheduler
        TerminalClipboardWriter(rootView.context, scheduler)
    }
    val terminalClipboardAction = remember(terminalClipboardWriter) {
        TerminalClipboardActionCallback { request -> terminalClipboardWriter.write(request) != null }
    }
    val systemBarsController = remember(rootView) {
        rootView.context.findActivity()?.window?.let { window ->
            AndroidTerminalSystemBarsController(window, rootView)
        }
    }
    val terminalInputFocusRequester = rememberTerminalInputFocusRequester()
    val snackbarHostState = remember { SnackbarHostState() }
    val screenScope = rememberCoroutineScope()
    val transcriptExportedMessage = stringResource(R.string.terminal_transcript_exported)
    val transcriptExportFailedMessage = stringResource(R.string.terminal_transcript_export_failed)
    val transcriptUnavailableMessage = stringResource(R.string.terminal_transcript_unavailable)
    val documentPickerFailedMessage = stringResource(R.string.terminal_document_picker_failed)
    val sshPublicKeyClipboardLabel = stringResource(R.string.terminal_clipboard_ssh_public_key)
    val commandSnippetClipboardLabel = stringResource(R.string.terminal_clipboard_command_snippet)
    val tmuxSwitchFailedMessage = stringResource(R.string.tmux_session_switcher_switch_failed)
    var toolsSection by rememberSaveable { mutableStateOf(ToolSection.PROFILES) }
    var connectDialogRequest by remember { mutableStateOf<SshConnectDialogRequest?>(null) }
    var pendingWorkspaceAuthenticationHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var savedConnectionPickerVisible by rememberSaveable { mutableStateOf(false) }
    var pendingTerminalAuthenticationHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var destination by rememberSaveable { mutableStateOf(AppRoute.WORKSPACE) }
    var terminalOwner by rememberSaveable { mutableStateOf(TerminalOwner.WORKSPACE) }
    var catalogReturnDestination by rememberSaveable { mutableStateOf(AppRoute.WORKSPACE) }
    var settingsReturnDestination by rememberSaveable { mutableStateOf(AppRoute.WORKSPACE) }
    var settingsTerminalProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsKeyboardProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var catalogTargetSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var catalogEditHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var rendererLabVisible by rememberSaveable { mutableStateOf(false) }
    var backPreview by remember { mutableStateOf<ShellBackPreview?>(null) }
    var reimportIdentityToken by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionActionsTargetId by remember { mutableStateOf<Long?>(null) }
    var dismissedMoshFallbackSessionId by remember { mutableStateOf<Long?>(null) }
    var focusSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var findSessionId by remember { mutableStateOf<Long?>(null) }
    var snippetPickerTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var restoreTerminalFocusAfterSnippetPicker by remember { mutableStateOf(false) }
    var pendingAccessoryPaste by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var imagePickerTargetSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var tmuxSwitcherTargetId by remember { mutableStateOf<Long?>(null) }
    var tmuxSwitcherCatalog by remember { mutableStateOf<TmuxSessionCatalog?>(null) }
    var tmuxDeleteTargetId by remember { mutableStateOf<String?>(null) }
    val identityImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val recoveryToken = reimportIdentityToken
        reimportIdentityToken = null
        if (uri != null) viewModel.importSshIdentity(uri, recoveryToken)
    }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_SELECTED_TERMINAL_IMAGES),
    ) { uris ->
        val targetSessionId = imagePickerTargetSessionId
        imagePickerTargetSessionId = null
        if (targetSessionId != null && uris.isNotEmpty()) {
            val resolver = rootView.context.contentResolver
            val images = uris.mapNotNull { uri ->
                resolver.getType(uri)?.let { mimeType ->
                    TerminalImagePasteSource(
                        mimeType = mimeType,
                        open = { resolver.openInputStream(uri) },
                    )
                }
            }
            if (images.isNotEmpty()) viewModel.pasteImages(targetSessionId, images)
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }
    val transcriptExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TERMINAL_TRANSCRIPT_MIME_TYPE),
    ) { uri ->
        val lines = viewModel.consumeTerminalTranscriptExport()
        if (uri != null && lines != null) {
            screenScope.launch {
                val result = withContext(Dispatchers.IO) {
                    writeTerminalTranscriptDocument(
                        lines = lines,
                        openOutput = {
                            rootView.context.contentResolver.openOutputStream(uri, "wt")
                        },
                    )
                }
                snackbarHostState.showSnackbar(
                    message = if (result.isSuccess) {
                        transcriptExportedMessage
                    } else {
                        transcriptExportFailedMessage
                    },
                )
            }
        } else if (uri != null) {
            screenScope.launch {
                snackbarHostState.showSnackbar(transcriptUnavailableMessage)
            }
        }
    }

    fun launchIdentityImport(recoveryToken: String? = null) {
        reimportIdentityToken = recoveryToken
        identityImportLauncher.launch(
            arrayOf(
                "application/x-pem-file",
                "application/octet-stream",
                "text/plain",
            ),
        )
    }

    fun showConnectionsCatalog(
        source: AppRoute = destination,
        targetSessionId: Long? = state.activeSessionId,
        editHostId: String? = null,
    ) {
        catalogReturnDestination = catalogReturnDestinationForEntry(source)
        catalogTargetSessionId = targetSessionId
        catalogEditHostId = editHostId
        destination = AppRoute.CONNECTIONS
    }

    fun showSettingsCatalog(tab: ConnectionsTab) {
        viewModel.selectConnectionsTab(tab)
        catalogReturnDestination = AppRoute.SETTINGS
        catalogTargetSessionId = state.activeSessionId
        catalogEditHostId = null
        destination = AppRoute.CONNECTIONS
    }

    fun copyCatalogText(label: String, value: String?) {
        value ?: return
        clipboardManager?.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    fun pasteClipboardInto(sessionId: Long) {
        if (!rootView.hasWindowFocus()) return
        val images = runCatching {
            val clip = clipboardManager?.primaryClip ?: return@runCatching null
            if (clip.itemCount == 0) return@runCatching null
            val describedMimeType = (0 until clip.description.mimeTypeCount)
                .map(clip.description::getMimeType)
                .firstOrNull { it.startsWith("image/", ignoreCase = true) }
            val resolver = rootView.context.contentResolver
            (0 until clip.itemCount).mapNotNull { index ->
                val uri = clip.getItemAt(index).uri ?: return@mapNotNull null
                val mimeType = resolver.getType(uri) ?: describedMimeType
                mimeType?.let {
                    TerminalImagePasteSource(
                        mimeType = it,
                        open = { resolver.openInputStream(uri) },
                    )
                }
            }.takeIf { it.isNotEmpty() }
        }.getOrNull()
        if (images != null) {
            viewModel.pasteImages(sessionId, images)
            return
        }
        val text = runCatching {
            val clip = clipboardManager?.primaryClip ?: return@runCatching null
            if (clip.itemCount == 0) return@runCatching null
            clip.getItemAt(0)
                .coerceToText(rootView.context)
                ?.toString()
                ?.take(MAX_BUFFERED_INPUT_CHARACTERS + 1)
        }.getOrNull()?.takeIf(String::isNotEmpty) ?: return
        if (state.multilinePasteConfirmationEnabled && text.requiresMultilineConfirmation()) {
            pendingAccessoryPaste = sessionId to text
        } else {
            viewModel.sendBufferedInput(sessionId, text)
        }
    }

    fun openTmuxSessionSwitcher(sessionId: Long) {
        if (state.sessions.firstOrNull { it.id == sessionId }?.connectionState !is ConnectionState.Connected) {
            return
        }
        tmuxSwitcherTargetId = sessionId
        tmuxSwitcherCatalog = null
        screenScope.launch {
            val result = viewModel.queryActiveTmuxSessions(sessionId)
            if (tmuxSwitcherTargetId != sessionId) return@launch
            tmuxSwitcherCatalog = result
            if (result.availability == TmuxAvailability.AVAILABLE && result.sessions.isNotEmpty()) {
                val withPreviews = viewModel.queryActiveTmuxSessionPreviews(sessionId)
                if (
                    tmuxSwitcherTargetId == sessionId &&
                    withPreviews.availability == TmuxAvailability.AVAILABLE
                ) {
                    tmuxSwitcherCatalog = withPreviews
                }
            }
        }
    }

    fun showSettings(
        section: ToolSection,
        returnDestination: AppRoute,
        sessionId: Long? = null,
    ) {
        val session = sessionId?.let { targetId -> state.sessions.firstOrNull { it.id == targetId } }
        toolsSection = section
        settingsReturnDestination = returnDestination
        settingsTerminalProfileId = session?.terminalProfileId
        settingsKeyboardProfileId = session?.keyboardProfileId
        destination = AppRoute.SETTINGS
    }

    fun showSettingsCategories(returnDestination: AppRoute) {
        // Connection-only sections map to no Settings category, which opens the category index.
        showSettings(ToolSection.PROFILES, returnDestination)
    }

    fun showTools(section: ToolSection, sessionId: Long? = null) {
        toolsSection = section
        when (section) {
            ToolSection.PROFILES,
            ToolSection.IDENTITIES,
            -> showConnectionsCatalog(targetSessionId = sessionId ?: state.activeSessionId)
            ToolSection.SNIPPETS -> if (sessionId != null) {
                if (state.sessions.any { it.id == sessionId }) {
                    viewModel.selectSession(sessionId)
                    snippetPickerTargetId = sessionId
                }
            } else {
                showConnectionsCatalog(targetSessionId = state.activeSessionId)
            }
            ToolSection.TERMINAL,
            ToolSection.KEYS,
            ToolSection.SECURITY,
            ToolSection.MOSH,
            ToolSection.ABOUT,
            ToolSection.DEVELOPER,
            -> showSettings(
                section = section,
                returnDestination = if (sessionId != null) {
                    AppRoute.TERMINAL_DETAIL
                } else {
                    AppRoute.WORKSPACE
                },
                sessionId = sessionId,
            )
        }
    }

    fun showNewConnection(profileId: Long? = null) {
        val profile = profileId?.let(viewModel::profileById)
        connectDialogRequest = SshConnectDialogRequest(
            purpose = SshConnectPurpose.NEW,
            seed = profile?.connectionSeed(),
        )
    }

    fun showSessionConnection(sessionId: Long, purpose: SshConnectPurpose) {
        val seed = viewModel.connectionSeedForSession(sessionId) ?: return
        connectDialogRequest = SshConnectDialogRequest(
            purpose = purpose,
            seed = seed,
            replacementSessionId = sessionId.takeIf { purpose == SshConnectPurpose.RECONNECT },
        )
    }

    fun showFreshSshFallback(sessionId: Long) {
        val seed = viewModel.connectionSeedForSession(sessionId) ?: return
        connectDialogRequest = SshConnectDialogRequest(
            purpose = SshConnectPurpose.RECONNECT,
            seed = seed.toFreshSshFallbackSeed(),
            replacementSessionId = sessionId,
        )
    }

    fun exportTranscript(sessionId: Long) {
        screenScope.launch {
            if (!viewModel.prepareTerminalTranscriptExport(sessionId)) return@launch
            runCatching { transcriptExportLauncher.launch(TERMINAL_TRANSCRIPT_FILE_NAME) }
                .onFailure {
                    viewModel.cancelTerminalTranscriptExport()
                    snackbarHostState.showSnackbar(documentPickerFailedMessage)
                }
        }
    }

    fun showTerminal(
        source: AppRoute = destination,
        rendererLab: Boolean = false,
    ) {
        if (!rendererLab) viewModel.selectLastActiveRemoteSessionForTerminalEntry()
        rendererLabVisible = rendererLab
        terminalOwner = terminalOwnerForEntry(source, terminalOwner)
        destination = AppRoute.TERMINAL_DETAIL
    }

    fun launchWorkspaceProfile(profileId: Long) {
        when (val launch = viewModel.launchWorkspaceProfile(profileId)) {
            WorkspaceProfileLaunchResult.Started -> showTerminal(AppRoute.WORKSPACE)
            is WorkspaceProfileLaunchResult.AuthenticationRequired -> {
                if (launch.persistentHostId != null) {
                    pendingWorkspaceAuthenticationHostId = launch.persistentHostId
                } else {
                    showNewConnection(launch.profileId)
                }
            }
            WorkspaceProfileLaunchResult.Rejected -> Unit
        }
    }

    fun duplicateTerminalSession(sessionId: Long) {
        when (viewModel.duplicateSession(sessionId)) {
            SessionDuplicateResult.Started -> showTerminal()
            SessionDuplicateResult.AuthenticationRequired ->
                showSessionConnection(sessionId, SshConnectPurpose.DUPLICATE)
            SessionDuplicateResult.Rejected -> Unit
        }
    }

    fun navigateBack() {
        shellBackDestination(
            current = destination,
            terminalOwner = terminalOwner,
            catalogReturnDestination = catalogReturnDestination,
            settingsReturnDestination = settingsReturnDestination,
        )?.let { destination = it }
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

    val terminalSurfaceVisible = !state.activeSession.isLocalTerminal ||
        (BuildConfig.DEBUG && rendererLabVisible)
    val focusMode = destination == AppRoute.TERMINAL_DETAIL &&
        focusSessionId == state.activeSessionId
    LaunchedEffect(focusSessionId, state.sessions) {
        val focusedId = focusSessionId
        if (focusedId != null && state.sessions.none { it.id == focusedId }) {
            focusSessionId = null
        }
    }
    LaunchedEffect(
        state.activeSession.id,
        state.activeSession.protocol,
        state.activeSession.moshFallbackPolicy,
        state.activeSession.connectionState,
    ) {
        val active = state.activeSession
        if (
            dismissedMoshFallbackSessionId == active.id &&
            !shouldOfferFreshSshFallback(active, dismissedSessionId = null)
        ) {
            dismissedMoshFallbackSessionId = null
        }
    }
    val shouldKeepTerminalScreenOn = destination == AppRoute.TERMINAL_DETAIL &&
        terminalSurfaceVisible &&
        (terminalBuildKeepsScreenOn || state.keepScreenOnWhileTerminalVisible)
    DisposableEffect(rootView, shouldKeepTerminalScreenOn) {
        rootView.keepScreenOn = shouldKeepTerminalScreenOn
        onDispose { rootView.keepScreenOn = false }
    }

    // The terminal uses overlay priority so an active IME cannot consume the first system Back.
    // The AndroidX handlers remain the compatibility and programmatic-dispatch path.
    BackHandler(enabled = focusMode) { focusSessionId = null }
    TerminalPriorityBackHandler(
        currentRoute = destination,
        terminalOwner = terminalOwner,
        catalogReturnDestination = catalogReturnDestination,
        settingsReturnDestination = settingsReturnDestination,
        focusMode = focusMode,
        onPreviewChanged = { backPreview = it },
        onExitFocusMode = { focusSessionId = null },
        onNavigate = { destination = it },
    )
    ShellPredictiveBackHandler(
        currentRoute = destination,
        terminalOwner = terminalOwner,
        catalogReturnDestination = catalogReturnDestination,
        settingsReturnDestination = settingsReturnDestination,
        enabled = !focusMode,
        onPreviewChanged = { backPreview = it },
        onNavigate = { destination = it },
    )
    TerminalSystemBarsEffect(
        immersive = destination == AppRoute.TERMINAL_DETAIL && terminalSurfaceVisible,
        controller = systemBarsController,
    )
    TerminalBellEffect(
        sessionId = state.activeSessionId,
        controller = activeController,
        settings = viewModel.bellSettingsForSession(state.activeSessionId),
        foregroundUiOwnsSession = destination == AppRoute.TERMINAL_DETAIL &&
            terminalSurfaceVisible,
        terminalView = terminalInputFocusRequester,
    )
    LaunchedEffect(
        snippetPickerTargetId,
        state.pendingSnippetSendId,
        restoreTerminalFocusAfterSnippetPicker,
        destination,
    ) {
        if (
            restoreTerminalFocusAfterSnippetPicker &&
            snippetPickerTargetId == null &&
            state.pendingSnippetSendId == null &&
            destination == AppRoute.TERMINAL_DETAIL
        ) {
            terminalInputFocusRequester.requestFocus()
            restoreTerminalFocusAfterSnippetPicker = false
        }
    }
    LaunchedEffect(snippetPickerTargetId, state.sessions) {
        val targetId = snippetPickerTargetId ?: return@LaunchedEffect
        if (state.sessions.none { it.id == targetId }) {
            snippetPickerTargetId = null
            restoreTerminalFocusAfterSnippetPicker = true
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = destination,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val preview = backPreview
                    translationX = size.width * (preview?.translationFraction ?: 0f)
                    scaleX = preview?.scale ?: 1f
                    scaleY = preview?.scale ?: 1f
                },
            transitionSpec = {
                if (keepsPrimaryNavigationStable(initialState, targetState)) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (fadeIn() + slideInHorizontally(initialOffsetX = { it / 10 })).togetherWith(
                        fadeOut() + slideOutHorizontally(targetOffsetX = { -it / 10 }),
                    )
                }
            },
            label = "primary-workspace",
        ) { target ->
            when (target) {
                AppRoute.WORKSPACE,
                AppRoute.CONNECTIONS,
                -> ConnectionsScreen(
                    state = connectionsState,
                    initialHostEditorId = catalogEditHostId.takeIf {
                        target == AppRoute.CONNECTIONS
                    },
                    callbacks = ConnectionsCallbacks(
                        onTabSelected = viewModel::selectConnectionsTab,
                        onSearchQueryChanged = viewModel::setConnectionsSearchQuery,
                        onHostSortSelected = viewModel::setConnectionsHostSort,
                        onFavouritesOnlyChanged = viewModel::setConnectionsFavouritesOnly,
                        onHostGroupSelected = viewModel::setConnectionsHostGroup,
                        onClearHostFilters = viewModel::clearConnectionsHostFilters,
                        onRetry = viewModel::retryConnectionsCatalog,
                        onImportKey = { launchIdentityImport() },
                        onCopyPublicKey = { id ->
                            copyCatalogText(
                                sshPublicKeyClipboardLabel,
                                viewModel.connectionsPublicKey(id),
                            )
                        },
                        onDeleteHost = viewModel::deleteConnectionsHost,
                        onOpenSftp = viewModel::openSftp,
                        onDeleteKey = viewModel::deleteConnectionsKey,
                        onInsertSnippet = catalogTargetSessionId?.let { targetId ->
                            { id ->
                                if (viewModel.insertConnectionsSnippet(id, targetId)) {
                                    showTerminal(target)
                                }
                            }
                        },
                        onRunSnippet = catalogTargetSessionId?.let { targetId ->
                            { id ->
                                if (viewModel.runConnectionsSnippet(id, targetId)) {
                                    showTerminal(target)
                                }
                            }
                        },
                        onCopySnippet = { id ->
                            copyCatalogText(
                                commandSnippetClipboardLabel,
                                viewModel.connectionsSnippetCommand(id),
                            )
                        },
                        onDeleteSnippet = viewModel::deleteConnectionsSnippet,
                        onSaveHost = viewModel::saveConnectionsHost,
                        onResolveHostEditorDraft = viewModel::resolveConnectionsHostEditorDraft,
                        onRetainHostEditorDraft = viewModel::retainConnectionsHostEditorDraft,
                        onClearHostEditorDraft = viewModel::clearConnectionsHostEditorDraft,
                        onTestHost = viewModel::startConnectionsHostTest,
                        onAnswerTestHostIdentity = viewModel::answerConnectionsHostIdentity,
                        onAnswerTestKeyboardInteractive =
                            viewModel::answerConnectionsKeyboardInteractive,
                        onCancelTestKeyboardInteractive =
                            viewModel::cancelConnectionsKeyboardInteractive,
                        onCancelHostTest = viewModel::cancelConnectionsHostTest,
                        onConnectCatalogHost = { request ->
                            if (viewModel.connectConnectionsHost(request)) {
                                showTerminal(target)
                            }
                        },
                        onSaveSnippetModel = viewModel::saveConnectionsSnippet,
                        onGenerateKeyRequest = viewModel::generateConnectionsKey,
                        onRenameKeyMetadata = viewModel::renameConnectionsKey,
                        onOpenMoshStatus = {
                            showSettings(
                                section = ToolSection.MOSH,
                                returnDestination = target,
                            )
                        },
                    ),
                    onOpenWorkspace = { destination = AppRoute.WORKSPACE },
                    onOpenTerminal = { showTerminal(target) },
                    onOpenSettings = { showSettingsCategories(target) },
                    onNavigateBack = if (target == AppRoute.CONNECTIONS) {
                        { destination = catalogReturnDestination }
                    } else {
                        null
                    },
                    moshAvailable = state.moshExtension.kind == MoshExtensionUiKind.AVAILABLE,
                    displayedTab = if (target == AppRoute.WORKSPACE) {
                        ConnectionsTab.HOSTS
                    } else {
                        connectionsState.selectedTab
                    },
                    primaryDestination = if (
                        target == AppRoute.CONNECTIONS &&
                        catalogReturnDestination == AppRoute.SETTINGS
                    ) {
                        AppDestination.SETTINGS
                    } else {
                        AppDestination.CONNECTIONS
                    },
                )

                AppRoute.TERMINAL_DETAIL -> TerminalDetailInsetContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        if (!focusMode) {
                            SessionChrome(
                                sessions = state.sessions,
                                activeSessionId = state.activeSessionId,
                                notice = null,
                                canAddSession = state.canAddSshSession,
                                settingsReady = state.settingsReady,
                                onSelectSession = viewModel::selectSession,
                                onDuplicateSession = ::duplicateTerminalSession,
                                onCloseSession = viewModel::closeSession,
                                onDisconnect = viewModel::disconnectSsh,
                                onSessionActions = { sessionActionsTargetId = it },
                                previewLinesForSession = viewModel::terminalSwitcherPreviewLines,
                                onHostIdentityAnswer = viewModel::answerHostIdentityPrompt,
                                onNavigateBack = ::navigateBack,
                                backDestinationLabel = stringResource(terminalOwner.labelRes),
                                onNewSession = { savedConnectionPickerVisible = true },
                                onOpenConnections = { showTools(ToolSection.PROFILES) },
                                onKeyboardInteractiveAnswer =
                                    viewModel::answerKeyboardInteractiveChallenge,
                                onKeyboardInteractiveCancel =
                                    viewModel::cancelKeyboardInteractiveChallenge,
                                onTmuxSessionAnswer = viewModel::answerTmuxSessionPrompt,
                                onTmuxSessionDelete = viewModel::deleteTmuxSession,
                                showLocalTerminalSession = BuildConfig.DEBUG && rendererLabVisible,
                            )
                        }
                        if (rendererLabVisible && !focusMode) {
                            TerminalBuildTopSlot(
                                state = state,
                                feature = viewModel.terminalBuildFeature,
                                autoFollow = performance.autoFollow,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background),
                        ) {
                            if (terminalSurfaceVisible) {
                                TerminalViewBridge(
                                    controller = activeController,
                                    inputFocusRequester = terminalInputFocusRequester,
                                    onPreImeBack = {
                                        if (focusMode) {
                                            focusSessionId = null
                                        } else {
                                            navigateBack()
                                        }
                                    },
                                    clipboardActionCallback = terminalClipboardAction,
                                    imageContentCallback = TerminalImageContentCallback { request ->
                                        val accepted = viewModel.pasteImage(
                                            sessionId = state.activeSessionId,
                                            mimeType = request.mimeType,
                                            open = {
                                                rootView.context.contentResolver
                                                    .openInputStream(request.uri)
                                            },
                                            onFinished = request.releasePermission,
                                        )
                                        if (!accepted) request.releasePermission()
                                        accepted
                                    },
                                )
                            } else {
                                TerminalEmptyState(
                                    canOpenConnection = state.canAddSshSession,
                                    onOpenConnection = { savedConnectionPickerVisible = true },
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                            if (rendererLabVisible) {
                                TerminalBuildCanvasSlot(
                                    state = state,
                                    snapshot = performance,
                                    feature = viewModel.terminalBuildFeature,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                                )
                            }
                            JumpToLatestAction(
                                visible = terminalSurfaceVisible && !performance.autoFollow,
                                onJumpToLatest = viewModel::jumpToBottom,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                            )
                        }
                        if (terminalSurfaceVisible && !focusMode) {
                            TerminalAccessoryBar(
                                actions = state.accessoryActions,
                                modifiers = AccessoryModifierSnapshot(
                                    control = accessoryModifierState(
                                        state.ctrlArmed,
                                        state.ctrlLocked,
                                    ),
                                    alt = accessoryModifierState(
                                        state.altArmed,
                                        state.altLocked,
                                    ),
                                    shift = accessoryModifierState(
                                        state.shiftArmed,
                                        state.shiftLocked,
                                    ),
                                ),
                                layout = state.keyboardLayout,
                                inputMode = state.terminalInputMode,
                                hapticFeedbackEnabled = state.keyboardHapticsEnabled,
                                keyRepeatEnabled = state.keyRepeatEnabled,
                                multilinePasteConfirmationEnabled =
                                    state.multilinePasteConfirmationEnabled,
                                voiceInputLanguageTag = state.voiceInputLanguageTag,
                                customizationEnabled = state.settingsReady,
                                inputTargetId = state.activeSessionId,
                                bufferedInputSendEnabled = state.canSendTerminalInput,
                                bufferedInputDraftState = viewModel.bufferedInputDraftState,
                                onAction = { action ->
                                    if (action is TerminalAccessoryAction.Local) {
                                        when (action.action) {
                                            TerminalLocalAccessoryAction.PASTE ->
                                                pasteClipboardInto(state.activeSessionId)
                                            TerminalLocalAccessoryAction.SELECT_IMAGES -> {
                                                imagePickerTargetSessionId = state.activeSessionId
                                                imagePickerLauncher.launch(
                                                    PickVisualMediaRequest(
                                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                                    ),
                                                )
                                            }
                                            TerminalLocalAccessoryAction.SNIPPETS ->
                                                showTools(ToolSection.SNIPPETS, state.activeSessionId)
                                            TerminalLocalAccessoryAction.TMUX_SESSIONS ->
                                                openTmuxSessionSwitcher(state.activeSessionId)
                                            TerminalLocalAccessoryAction.KEYBOARD_SETTINGS ->
                                                showTools(ToolSection.KEYS, state.activeSessionId)
                                            TerminalLocalAccessoryAction.HIDE_KEYBOARD ->
                                                terminalInputFocusRequester.toggleSoftwareKeyboard()
                                        }
                                    } else {
                                        terminalInputFocusRequester.resetComposingInput()
                                        viewModel.activateTerminalAccessoryAction(action)
                                    }
                                },
                                onCustomize = { showTools(ToolSection.KEYS) },
                                onSendBufferedInput = viewModel::sendBufferedInput,
                                onBufferedInputModeChanged = { active ->
                                    terminalInputFocusRequester.setDirectInputEnabled(!active)
                                },
                                onDirectInputMode = terminalInputFocusRequester::requestFocus,
                            )
                        }
                        if (!terminalSurfaceVisible) {
                            WorkspaceBottomBar(
                                selected = AppDestination.TERMINAL,
                                onWorkspace = { destination = AppRoute.WORKSPACE },
                                onConnections = {},
                                onSettings = {
                                    showSettingsCategories(AppRoute.TERMINAL_DETAIL)
                                },
                                windowInsets = WindowInsets(0, 0, 0, 0),
                            )
                        }
                    }
                }

                AppRoute.SETTINGS -> LocalToolsScreen(
                    destination = AppDestination.SETTINGS,
                    profiles = state.profiles,
                    identities = state.identities,
                    knownHosts = state.knownHosts,
                    snippets = state.snippets,
                    extraKeys = state.extraKeys,
                    moshExtension = state.moshExtension,
                    onNavigateBack = { destination = settingsReturnDestination },
                    onOpenWorkspace = { destination = AppRoute.WORKSPACE },
                    // The second primary navigation item is the live terminal.
                    onOpenConnections = { showTerminal(target) },
                    onOpenSettings = { destination = AppRoute.SETTINGS },
                    onOpenSshKeys = { showSettingsCatalog(ConnectionsTab.KEYS) },
                    onOpenSnippets = { showSettingsCatalog(ConnectionsTab.SNIPPETS) },
                    onUseProfile = { profile ->
                        showNewConnection(profile.id)
                    },
                    onSaveProfile = viewModel::saveSshProfile,
                    onDeleteProfile = viewModel::deleteSshProfile,
                    onImportIdentity = { launchIdentityImport() },
                    onReimportIdentity = { token -> launchIdentityImport(token) },
                    onDeleteIdentity = viewModel::deleteSshIdentity,
                    onForgetKnownHost = viewModel::forgetKnownHost,
                    onSaveSnippet = viewModel::saveSnippet,
                    onDeleteSnippet = viewModel::deleteSnippet,
                    onSendSnippet = { id ->
                        viewModel.sendSnippet(id)
                        showTerminal(AppRoute.CONNECTIONS)
                    },
                    onSetKeyVisible = viewModel::setExtraKeyVisible,
                    onReplaceKey = viewModel::replaceExtraKey,
                    onMoveKey = viewModel::moveExtraKey,
                    onResetKeys = viewModel::resetExtraKeys,
                    onSaveKeys = {
                        viewModel.saveExtraKeys()
                    },
                    onRefreshMoshExtension = viewModel::refreshMoshExtension,
                    onOpenRendererLab = {
                        toolsSection = ToolSection.DEVELOPER
                        showTerminal(AppRoute.SETTINGS, rendererLab = true)
                    },
                    initialSection = toolsSection,
                    terminalProfileId = settingsTerminalProfileId,
                    keyboardProfileId = settingsKeyboardProfileId,
                )
            }
        }

        tmuxSwitcherTargetId?.let { targetId ->
            val targetSession = state.sessions.firstOrNull { it.id == targetId }
            if (targetSession == null) {
                LaunchedEffect(targetId) {
                    tmuxSwitcherTargetId = null
                    tmuxSwitcherCatalog = null
                }
            } else {
                ActiveTmuxSessionSwitcherDialog(
                    catalog = tmuxSwitcherCatalog,
                    onDismiss = {
                        tmuxSwitcherTargetId = null
                        tmuxSwitcherCatalog = null
                        tmuxDeleteTargetId = null
                    },
                    onRefresh = { openTmuxSessionSwitcher(targetId) },
                    onSelect = { tmuxSessionId ->
                        screenScope.launch {
                            if (viewModel.switchActiveTmuxSession(targetId, tmuxSessionId)) {
                                tmuxSwitcherTargetId = null
                                tmuxSwitcherCatalog = null
                                terminalInputFocusRequester.requestFocus()
                            } else {
                                snackbarHostState.showSnackbar(tmuxSwitchFailedMessage)
                            }
                        }
                    },
                    onDelete = { tmuxDeleteTargetId = it },
                )
            }
        }

        tmuxDeleteTargetId?.let { tmuxSessionId ->
            val selected = tmuxSwitcherCatalog?.sessions?.firstOrNull {
                it.id == tmuxSessionId
            }
            if (selected != null) {
                AlertDialog(
                    onDismissRequest = { tmuxDeleteTargetId = null },
                    title = { Text(stringResource(R.string.tmux_selector_delete_title)) },
                    text = {
                        Text(stringResource(R.string.tmux_selector_delete_message, selected.name))
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val targetId = tmuxSwitcherTargetId
                                tmuxDeleteTargetId = null
                                if (targetId != null) {
                                    tmuxSwitcherCatalog = null
                                    screenScope.launch {
                                        val result = viewModel.terminateActiveTmuxSession(
                                            targetId,
                                            tmuxSessionId,
                                        )
                                        if (tmuxSwitcherTargetId == targetId) {
                                            tmuxSwitcherCatalog = result
                                        }
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.tmux_selector_delete_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { tmuxDeleteTargetId = null }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }
        }

        sessionActionsTargetId?.let { targetId ->
            TerminalSessionActions(
                session = state.sessions.firstOrNull { it.id == targetId },
                endpoint = viewModel.connectionSeedForSession(targetId),
                canAddSession = state.canAddSshSession,
                onDismiss = { sessionActionsTargetId = null },
                onReconnect = { id ->
                    showSessionConnection(id, SshConnectPurpose.RECONNECT)
                },
                onDuplicate = ::duplicateTerminalSession,
                onDisconnect = viewModel::disconnectSsh,
                onClose = viewModel::closeSession,
                onFind = { id ->
                    if (state.sessions.any { it.id == id }) {
                        viewModel.selectSession(id)
                        findSessionId = id
                    }
                },
                onClearLocalScrollback = { id ->
                    viewModel.controllerForExistingSession(id)?.clearLocalScrollback()
                },
                onExportTranscript = ::exportTranscript,
                onEnterFocusMode = { id ->
                    if (state.sessions.any { it.id == id }) {
                        viewModel.selectSession(id)
                        focusSessionId = id
                    }
                },
                onOpenSnippets = { id ->
                    if (state.sessions.any { it.id == id }) {
                        viewModel.selectSession(id)
                        showTools(ToolSection.SNIPPETS, id)
                    }
                },
                onOpenTerminalSettings = { id ->
                    if (state.sessions.any { it.id == id }) {
                        viewModel.selectSession(id)
                        showTools(ToolSection.TERMINAL, id)
                    }
                },
                onOpenKeyboardSettings = { id ->
                    if (state.sessions.any { it.id == id }) {
                        viewModel.selectSession(id)
                        showTools(ToolSection.KEYS, id)
                    }
                },
            )
        }

        snippetPickerTargetId?.let { targetId ->
            state.sessions.firstOrNull { it.id == targetId }?.let { target ->
                TerminalSnippetPickerDialog(
                    sessionTitle = target.title.ifBlank {
                        stringResource(R.string.navigation_terminal)
                    },
                    snippets = state.snippets,
                    onDismiss = {
                        snippetPickerTargetId = null
                        restoreTerminalFocusAfterSnippetPicker = true
                    },
                    onSelect = { snippetId ->
                        snippetPickerTargetId = null
                        restoreTerminalFocusAfterSnippetPicker = true
                        viewModel.sendSnippet(snippetId, targetId)
                    },
                )
            }
        }

        findSessionId?.let { targetId ->
            TerminalFindDialog(
                sessionTitle = state.sessions.firstOrNull { it.id == targetId }?.title
                    ?: "terminal",
                controller = viewModel.controllerForExistingSession(targetId),
                onPresentResult = terminalInputFocusRequester::showFindResult,
                onClearResult = { terminalInputFocusRequester.clearFindResults() },
                onDismiss = {
                    terminalInputFocusRequester.clearFindResults()
                    findSessionId = null
                },
            )
        }

        pendingAccessoryPaste?.let { (targetId, text) ->
            AlertDialog(
                onDismissRequest = { pendingAccessoryPaste = null },
                title = { Text(stringResource(R.string.terminal_multiline_paste_title)) },
                text = {
                    Text(
                        stringResource(R.string.terminal_accessory_multiline_paste_message),
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            pendingAccessoryPaste = null
                            viewModel.sendBufferedInput(targetId, text)
                        },
                    ) { Text(stringResource(R.string.terminal_paste)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingAccessoryPaste = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        backPreview?.let { preview ->
            PredictiveBackDestinationPreview(
                preview = preview,
                modifier = Modifier.fillMaxSize(),
            )
        }

        val snackbarBottomPadding = when {
            destination == AppRoute.TERMINAL_DETAIL && terminalSurfaceVisible -> 112.dp
            destination == AppRoute.TERMINAL_DETAIL -> 12.dp
            else -> 80.dp
        }
        NoticeSnackbarHost(
            notice = state.notice,
            hostState = snackbarHostState,
            onNoticePresented = viewModel::consumeNotice,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.union(WindowInsets.ime).only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                )
                .padding(horizontal = 12.dp)
                .padding(bottom = snackbarBottomPadding),
        )

        if (sftpState !is SftpUiState.Closed) {
            SftpScreen(
                controller = viewModel.sftp,
                scope = screenScope,
                onSubmitAuthentication = viewModel::submitSftpAuthentication,
                onClose = viewModel.sftp::close,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (savedConnectionPickerVisible) {
        SavedConnectionPickerDialog(
            loadState = connectionsState.loadState,
            onDismiss = { savedConnectionPickerVisible = false },
            onOpenConnections = {
                savedConnectionPickerVisible = false
                viewModel.selectConnectionsTab(ConnectionsTab.HOSTS)
                destination = AppRoute.WORKSPACE
            },
            onSelectHost = { host ->
                savedConnectionPickerVisible = false
                val catalog = (connectionsState.loadState as? ConnectionsLoadState.Ready)
                    ?.editorCatalog
                    ?: return@SavedConnectionPickerDialog
                val hostId = host.draft.persistentId ?: return@SavedConnectionPickerDialog
                if (
                    host.requiresConnectionPrompt(
                        catalog = catalog,
                        moshAvailable = state.moshExtension.kind == MoshExtensionUiKind.AVAILABLE,
                    )
                ) {
                    pendingTerminalAuthenticationHostId = hostId
                } else {
                    viewModel.connectConnectionsHost(HostConnectRequest(hostId))
                }
            },
        )
    }

    val terminalConnectionCatalog =
        (connectionsState.loadState as? ConnectionsLoadState.Ready)?.editorCatalog
    val terminalAuthenticationHost = pendingTerminalAuthenticationHostId?.let { hostId ->
        terminalConnectionCatalog?.hosts?.firstOrNull { it.draft.persistentId == hostId }
    }
    LaunchedEffect(pendingTerminalAuthenticationHostId, terminalConnectionCatalog) {
        if (
            pendingTerminalAuthenticationHostId != null &&
            terminalConnectionCatalog != null &&
            terminalAuthenticationHost == null
        ) {
            pendingTerminalAuthenticationHostId = null
        }
    }
    if (terminalAuthenticationHost != null) {
        val terminalAuthenticationKey = terminalAuthenticationHost.draft.keyIdentityId
            ?.let { keyId ->
                terminalConnectionCatalog?.keys?.firstOrNull { it.persistentId == keyId }
            }
        val connectTerminalHost: (HostConnectRequest) -> Unit = { request ->
            viewModel.connectConnectionsHost(request)
            pendingTerminalAuthenticationHostId = null
        }
        HostAuthenticationPromptDialog(
            host = terminalAuthenticationHost,
            key = terminalAuthenticationKey,
            moshAvailable = state.moshExtension.kind == MoshExtensionUiKind.AVAILABLE,
            onDismiss = { pendingTerminalAuthenticationHostId = null },
            onConnect = connectTerminalHost,
            onConnectWithSsh = connectTerminalHost,
        )
    }

    val workspaceEditorCatalog =
        (connectionsState.loadState as? ConnectionsLoadState.Ready)?.editorCatalog
    val workspaceAuthenticationHost = pendingWorkspaceAuthenticationHostId?.let { hostId ->
        workspaceEditorCatalog?.hosts?.firstOrNull { it.draft.persistentId == hostId }
    }
    LaunchedEffect(pendingWorkspaceAuthenticationHostId, workspaceEditorCatalog) {
        if (
            pendingWorkspaceAuthenticationHostId != null &&
            workspaceEditorCatalog != null &&
            workspaceAuthenticationHost == null
        ) {
            pendingWorkspaceAuthenticationHostId = null
        }
    }
    if (workspaceAuthenticationHost != null) {
        val workspaceAuthenticationKey = workspaceAuthenticationHost.draft.keyIdentityId
            ?.let { keyId ->
                workspaceEditorCatalog?.keys?.firstOrNull { it.persistentId == keyId }
            }
        val connectWorkspaceHost: (HostConnectRequest) -> Unit = { request ->
            if (viewModel.connectConnectionsHost(request)) {
                pendingWorkspaceAuthenticationHostId = null
                showTerminal(AppRoute.WORKSPACE)
            }
        }
        HostAuthenticationPromptDialog(
            host = workspaceAuthenticationHost,
            key = workspaceAuthenticationKey,
            moshAvailable = state.moshExtension.kind == MoshExtensionUiKind.AVAILABLE,
            onDismiss = { pendingWorkspaceAuthenticationHostId = null },
            onConnect = connectWorkspaceHost,
            onConnectWithSsh = connectWorkspaceHost,
        )
    }

    val activeConnectDialogRequest = connectDialogRequest
    if (activeConnectDialogRequest != null && state.settingsRecoveryFailure == null) {
        val initialProfile = activeConnectDialogRequest.seed?.sourceProfileId
            ?.let(viewModel::profileById)
        SshConnectDialog(
            profiles = state.profiles,
            identities = state.identities,
            initialProfile = initialProfile,
            initialSeed = activeConnectDialogRequest.seed,
            purpose = activeConnectDialogRequest.purpose,
            settingsReady = state.settingsReady,
            onDismiss = {
                connectDialogRequest = null
            },
            onConnect = {
                    host, port, username, password, identityId, passphrase, saveProfile,
                    selectedProfileId, savePassword, connectionOptions, sessionName,
                ->
                val replacementSessionId = activeConnectDialogRequest.replacementSessionId
                connectDialogRequest = null
                showTerminal()
                viewModel.connectSsh(
                    host,
                    port,
                    username,
                    password,
                    identityId,
                    passphrase,
                    saveProfile,
                    selectedProfileId,
                    savePassword,
                    replacementSessionId,
                    connectionOptions,
                    sessionName,
                )
            },
            onForgetSavedPassword = viewModel::forgetSavedPassword,
            keyboardRuntimeCompatible = state.keyboardRuntimeCompatible,
            moshExtension = state.moshExtension,
        )
    }
    state.pendingSnippetSendId?.let { snippetId ->
        state.snippets.firstOrNull { it.id == snippetId }?.let { snippet ->
            AlertDialog(
                onDismissRequest = viewModel::cancelSnippetSend,
                title = { Text(stringResource(R.string.terminal_snippet_multiline_title)) },
                text = {
                    Text(
                        stringResource(R.string.terminal_snippet_multiline_message, snippet.label),
                    )
                },
                confirmButton = {
                    Button(onClick = { viewModel.confirmSnippetSend(snippetId) }) {
                        Text(stringResource(R.string.terminal_snippet_send_all_lines))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelSnippetSend) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
    state.remoteClipboardPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { viewModel.denyRemoteClipboardRequest(prompt) },
            title = { Text(stringResource(R.string.terminal_remote_clipboard_title)) },
            text = { Text(stringResource(R.string.terminal_remote_clipboard_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.allowRemoteClipboardRequest(prompt)
                            ?.let(terminalClipboardWriter::writeRemoteClipboard)
                    },
                ) {
                    Text(stringResource(R.string.terminal_remote_clipboard_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.denyRemoteClipboardRequest(prompt) }) {
                    Text(stringResource(R.string.terminal_remote_clipboard_deny))
                }
            },
        )
    }
    state.activeSession.takeIf { session ->
        shouldOfferFreshSshFallback(session, dismissedMoshFallbackSessionId)
    }?.let { failedMoshSession ->
        AlertDialog(
            onDismissRequest = { dismissedMoshFallbackSessionId = failedMoshSession.id },
            title = { Text(stringResource(R.string.mosh_fallback_title)) },
            text = { Text(stringResource(R.string.mosh_fallback_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        dismissedMoshFallbackSessionId = failedMoshSession.id
                        showFreshSshFallback(failedMoshSession.id)
                    },
                ) {
                    Text(stringResource(R.string.mosh_fallback_start_ssh))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { dismissedMoshFallbackSessionId = failedMoshSession.id },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (state.notificationPermissionPromptVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissNotificationPermissionPrompt,
            title = { Text(stringResource(R.string.terminal_notification_permission_title)) },
            text = {
                Text(
                    stringResource(R.string.terminal_notification_permission_message),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.onNotificationPermissionResult(granted = true)
                        }
                    },
                ) { Text(stringResource(R.string.terminal_notification_permission_continue)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissNotificationPermissionPrompt) {
                    Text(stringResource(R.string.not_now))
                }
            },
        )
    }
    state.settingsRecoveryFailure?.let { failure ->
        SettingsRecoveryDialog(
            failure = failure,
            inProgress = state.settingsRecoveryInProgress,
            onRetry = viewModel::retrySettingsRecovery,
            onResetConfirmed = viewModel::resetSettingsAfterRecoveryConfirmation,
        )
    }
}

@Composable
internal fun NoticeSnackbarHost(
    notice: UiText?,
    hostState: SnackbarHostState,
    onNoticePresented: (UiText) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnNoticePresented by rememberUpdatedState(onNoticePresented)
    val resolvedNotice = notice?.resolve()
    LaunchedEffect(notice, hostState) {
        val presentedNotice = notice ?: return@LaunchedEffect
        try {
            hostState.showSnackbar(
                message = requireNotNull(resolvedNotice),
                duration = SnackbarDuration.Short,
            )
        } finally {
            currentOnNoticePresented(presentedNotice)
        }
    }
    // Material's default Snackbar uses the neutral inverse-surface palette. Error, warning,
    // and success notices are not all misrepresented as terminal connection failures.
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
    )
}

@Composable
internal fun JumpToLatestAction(
    visible: Boolean,
    onJumpToLatest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val jumpDescription = stringResource(R.string.terminal_jump_to_latest_description)
    FilledTonalButton(
        onClick = onJumpToLatest,
        modifier = modifier.semantics {
            contentDescription = jumpDescription
        },
    ) {
        Text(stringResource(R.string.terminal_jump_to_latest))
    }
}
