package com.yanjiyu.terminalspike.ui.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.ui.isClearlyLocalNetworkEndpoint
import com.yanjiyu.terminalspike.ui.resolvedEndpointIsLocalNetwork
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A browser surface, independent of the SSH project reader and its connection lifetime. */
@Composable
internal fun BrowserWorkspace(controller: BrowserController, tab: BrowserTab, onNative: (() -> Unit)? = null,
    modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize()) { BrowserContent(controller, tab, onNative) }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled") // Required by the explicitly opened remote IDE; no JS bridge.
@Composable
private fun BrowserContent(controller: BrowserController, tab: BrowserTab, onNative: (() -> Unit)?) {
    val context = LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val menuVisible by tab.menuVisible.collectAsStateWithLifecycle()
    var configure by rememberSaveable(tab.endpoint) { mutableStateOf(tab.codeServer && tab.address.isEmpty()) }
    var address by remember(tab) { mutableStateOf(tab.address) }
    var desktop by remember(tab) { mutableStateOf(tab.desktop) }
    var generation by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(tab.view) }
    var progress by remember { mutableIntStateOf(100) }
    var currentUrl by remember { mutableStateOf(web?.url.orEmpty()) }
    var canBack by remember { mutableStateOf(web?.canGoBack() == true) }
    var canForward by remember { mutableStateOf(web?.canGoForward() == true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var navigationRequest by remember { mutableIntStateOf(0) }
    var loadedRequest by remember { mutableIntStateOf(0) }
    var pageVersion by remember { mutableIntStateOf(0) }
    var loginRequest by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var networkReady by remember(address) { mutableStateOf(android.os.Build.VERSION.SDK_INT < 37 ||
        context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val permissionMessage = stringResource(R.string.browser_network_permission)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        networkReady = granted
        if (!granted) failure = permissionMessage
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val files = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            ?.filter { it.scheme == "content" }?.toTypedArray()
        fileCallback?.onReceiveValue(files)
        fileCallback = null
    }
    val failedMessage = stringResource(R.string.browser_load_failed)
    val tlsMessage = stringResource(R.string.browser_tls_failed)
    val rendererMessage = stringResource(R.string.browser_renderer_failed)
    val blockedMessage = stringResource(R.string.browser_navigation_blocked)
    val uploadMessage = stringResource(R.string.browser_upload_failed)
    val addressSaveFailed = stringResource(R.string.browser_address_save_failed)

    LaunchedEffect(address, navigationRequest, networkReady) {
        if (address.isEmpty()) return@LaunchedEffect
        if (!networkReady) {
            val host = URI(address).host
            val local = isClearlyLocalNetworkEndpoint(host) || withContext(Dispatchers.IO) {
                resolvedEndpointIsLocalNetwork(host) { value -> InetAddress.getAllByName(value).map { it.hostAddress.orEmpty() } } == true
            }
            if (local) permission.launch("android.permission.ACCESS_LOCAL_NETWORK")
            else networkReady = true
        }
        if (networkReady && navigationRequest > loadedRequest) {
            web?.let {
                it.loadUrl(browserProjectAddress(address, tab.projectPath))
                loadedRequest = navigationRequest
            }
        }
    }

    fun updateNavigation(view: WebView) {
        currentUrl = view.url.orEmpty()
        canBack = view.canGoBack()
        canForward = view.canGoForward()
    }
    fun load() {
        failure = null
        web?.loadUrl(browserProjectAddress(address, tab.projectPath))
    }
    BackHandler {
        if (web?.canGoBack() == true) web?.goBack() else controller.hide()
    }
    DisposableEffect(web, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> web?.onResume()
                Lifecycle.Event.ON_STOP -> { web?.onPause(); CookieManager.getInstance().flush() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(tab) {
        onDispose {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
        }
    }
    var location by remember(currentUrl, address) { mutableStateOf(currentUrl.ifEmpty { address }) }
    var locationInvalid by remember { mutableStateOf(false) }
    fun configureAddress(input: String): Boolean {
        val parsed = runCatching { browserAddress(input) }.getOrNull() ?: return false
        if (runCatching { controller.configure(tab, parsed) }.isFailure) notice = addressSaveFailed
        address = parsed
        failure = null
        navigationRequest++
        return true
    }
    fun navigateLocation() {
        locationInvalid = !configureAddress(location)
        if (!locationInvalid) focusManager.clearFocus()
    }
    Column(Modifier.fillMaxSize().imePadding().testTag("workspace-browser")) {
        if (!tab.codeServer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(location, { location = it; locationInvalid = false },
                    singleLine = true, isError = locationInvalid,
                    placeholder = { Text(stringResource(R.string.web_browser_address)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { navigateLocation() }),
                    modifier = Modifier.weight(1f).testTag("web-browser-address"))
                TextButton(onClick = { navigateLocation() }, enabled = location.isNotBlank(),
                    modifier = Modifier.testTag("web-browser-go")) { Text(stringResource(R.string.web_browser_go)) }
            }
            if (locationInvalid) Text(stringResource(R.string.browser_invalid_url),
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp))
        }
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxSize())
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        if (failure != null) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(failure.orEmpty(), color = MaterialTheme.colorScheme.error)
                Text(stringResource(if (tab.codeServer) R.string.browser_connection_hint else R.string.web_browser_connection_hint), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { if (web == null) { generation++; failure = null } else load() }) {
                        Text(stringResource(R.string.browser_reload))
                    }
                    TextButton(onClick = { configure = true }) { Text(stringResource(R.string.browser_address)) }
                }
            }
        }
        if (address.isNotEmpty() && networkReady) {
            key(tab, generation) {
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("browser-webview"),
                    factory = {
                        val retained = tab.view
                        val view = retained ?: WebView(context.applicationContext).also { tab.view = it }
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.outlineProvider = android.view.ViewOutlineProvider.BOUNDS
                        view.clipToOutline = true
                        view.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = false
                            allowContentAccess = true // Only system-picker content URIs are granted.
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(true)
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            mediaPlaybackRequiresUserGesture = true
                            val mobile = WebSettings.getDefaultUserAgent(context)
                            userAgentString = if (desktop) desktopBrowserAgent(mobile) else mobile
                        }
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
                        view.webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress; updateNavigation(view)
                            }
                            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                                params: FileChooserParams): Boolean {
                                fileCallback?.onReceiveValue(null)
                                fileCallback = callback
                                try { picker.launch(params.createIntent()) }
                                catch (_: Exception) { callback.onReceiveValue(null); fileCallback = null; failure = uploadMessage }
                                return true
                            }
                        }
                        view.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val blocked = !browserNavigationAllowed(request.url.toString(), address)
                                if (blocked && request.isForMainFrame) failure = blockedMessage
                                return blocked
                            }
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                // Permit plaintext only for the exact HTTP endpoint entered by the user.
                                return if (request.url.scheme == "http" && !browserNavigationAllowed(request.url.toString(), address))
                                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf())) else null
                            }
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                failure = null; progress = 0; updateNavigation(view)
                            }
                            override fun onPageFinished(view: WebView, url: String?) {
                                progress = 100; updateNavigation(view); pageVersion++
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) { failure = failedMessage; progress = 100 }
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                val loginRejected = tab.codeServer && response.statusCode == 401 &&
                                    BrowserLoginTarget.from(address).isLoginPage(request.url.toString())
                                if (request.isForMainFrame && response.statusCode >= 400 && !loginRejected) {
                                    failure = "$failedMessage (HTTP ${response.statusCode})"; progress = 100
                                }
                            }
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                handler.cancel(); failure = tlsMessage; progress = 100
                            }
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                (view.parent as? ViewGroup)?.removeView(view)
                                view.destroy(); tab.view = null; web = null
                                failure = rendererMessage; progress = 100
                                return true
                            }
                        }
                        web = view
                        view.onResume()
                        if (retained == null) {
                            view.loadUrl(browserProjectAddress(address, tab.projectPath))
                            loadedRequest = navigationRequest
                        }
                        view
                    },
                    onRelease = { view ->
                        // Detach Activity/composition callbacks; keep the editor and websocket alive.
                        if (tab.view === view) {
                            tab.detachUi(view)
                        }
                    })
            }
        } else if (address.isEmpty()) {
            Column(Modifier.weight(1f).padding(24.dp)) {
                if (tab.codeServer) {
                    Text(stringResource(R.string.browser_setup_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.browser_setup_detail), modifier = Modifier.padding(vertical = 12.dp))
                    Button(onClick = { configure = true }) { Text(stringResource(R.string.browser_address)) }
                } else Text(stringResource(R.string.web_browser_intro))
            }
        }
    }
    if (menuVisible) {
        fun action(block: () -> Unit) { tab.menuVisible.value = false; block() }
        ModalBottomSheet(onDismissRequest = { tab.menuVisible.value = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(stringResource(if (tab.codeServer) R.string.browser_code_server else R.string.browser_title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                Text(currentUrl.ifEmpty { address.ifEmpty { tab.title } }, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
                TextButton(onClick = { action { web?.goBack() } }, enabled = canBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.browser_back)) }
                TextButton(onClick = { action { web?.goForward() } }, enabled = canForward, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.browser_forward)) }
                TextButton(onClick = { action {
                    if (web == null) { generation++; failure = null } else { failure = null; web?.reload() }
                } }, enabled = address.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.browser_reload)) }
                TextButton(onClick = { action { load() } }, enabled = address.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (tab.codeServer) R.string.browser_home else R.string.web_browser_home)) }
                TextButton(onClick = { action { configure = true } }, modifier = Modifier.fillMaxWidth().testTag("browser-address")) { Text(stringResource(R.string.browser_address)) }
                if (tab.codeServer) TextButton(onClick = { action { loginRequest++ } }, enabled = web != null,
                    modifier = Modifier.fillMaxWidth().testTag("browser-password")) { Text(stringResource(R.string.browser_password)) }
                TextButton(onClick = { action {
                    desktop = !desktop; controller.desktop(tab, desktop)
                    web?.let { view ->
                        val mobile = WebSettings.getDefaultUserAgent(context)
                        view.settings.userAgentString = if (desktop) desktopBrowserAgent(mobile) else mobile
                        view.reload()
                    }
                } }, modifier = Modifier.fillMaxWidth().testTag("browser-layout")) {
                    Text(stringResource(if (desktop) R.string.browser_desktop else R.string.browser_mobile))
                }
                onNative?.let { native ->
                    TextButton(onClick = { action { controller.hide(); native() } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.browser_native)) }
                }
            }
        }
    }
    if (tab.codeServer && address.isNotEmpty()) {
        BrowserLogin(controller.passwords, web, address, pageVersion, loginRequest) { notice = it }
    }
    if (configure) {
        var draft by remember { mutableStateOf(address) }
        var invalid by remember { mutableStateOf(false) }
        fun open() {
            invalid = !configureAddress(draft)
            if (!invalid) configure = false
        }
        AlertDialog(onDismissRequest = { configure = false },
            title = { Text(stringResource(if (tab.codeServer) R.string.browser_setup_title else R.string.browser_title)) },
            text = { Column {
                if (tab.codeServer) Text(stringResource(R.string.browser_setup_detail), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(draft, { draft = it; invalid = false },
                    label = { Text(stringResource(if (tab.codeServer) R.string.browser_server_url else R.string.web_browser_address)) },
                    placeholder = { Text("https://computer.tailnet.ts.net") },
                    isError = invalid, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { open() }),
                    modifier = Modifier.fillMaxWidth().testTag("browser-url-input"))
                if (invalid) Text(stringResource(R.string.browser_invalid_url), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = { open() }, enabled = draft.isNotBlank()) { Text(stringResource(R.string.browser_open)) } },
            dismissButton = { TextButton(onClick = { configure = false }) { Text(stringResource(R.string.cancel)) } })
    }
}
