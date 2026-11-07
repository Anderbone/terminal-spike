package com.yanjiyu.terminalspike.ui.browser

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.view.ViewGroup
import com.yanjiyu.terminalspike.ui.project.projectRevision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class BrowserTab(
    val endpoint: String,
    val title: String,
    val projectPath: String?,
    var address: String,
    var desktop: Boolean,
    val codeServer: Boolean = true,
) {
    // Created with application context. Retained while returning to the terminal or rotating;
    // detached clients never retain an Activity, file picker, or composable callback.
    var view: WebView? = null
    val menuVisible = MutableStateFlow(false)
    fun detachUi(attached: WebView) {
        attached.webChromeClient = WebChromeClient()
        attached.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                if (this@BrowserTab.view === view) this@BrowserTab.view = null
                return true
            }
        }
        attached.onPause()
    }
    fun destroy() {
        view?.let { detachUi(it); (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        view = null
    }
}

internal class BrowserController(context: Context) {
    val passwords = BrowserPasswordVault(context)
    private val preferences = context.getSharedPreferences("workspace-browser", Context.MODE_PRIVATE)
    private val tabs = linkedMapOf<String, BrowserTab>()
    private val mutableActive = MutableStateFlow<BrowserTab?>(null)
    val active = mutableActive.asStateFlow()
    private val mutableCurrent = MutableStateFlow<BrowserTab?>(null)
    val current = mutableCurrent.asStateFlow()

    fun open(endpoint: String, title: String, projectPath: String? = null, codeServer: Boolean = true) {
        val key = projectRevision(endpoint)
        val tab = tabs[endpoint]?.takeIf { projectPath == null || it.projectPath == projectPath }
            ?: BrowserTab(endpoint, title, projectPath, preferences.getString("url-$key", "").orEmpty(),
                preferences.getBoolean("desktop-$key", false), codeServer).also {
                tabs.remove(endpoint)?.destroy()
                tabs[endpoint] = it
            }
        // Bound retained renderers; switching tabs keeps the current page alive.
        if (tabs.size > 3) tabs.remove(tabs.keys.first { it != endpoint })?.destroy()
        mutableCurrent.value = tab
        mutableActive.value = tab
    }

    fun configure(tab: BrowserTab, input: String) {
        val address = browserAddress(input)
        tab.address = address
        preferences.edit().putString("url-${projectRevision(tab.endpoint)}", address).apply()
    }

    fun desktop(tab: BrowserTab, enabled: Boolean) {
        tab.desktop = enabled
        preferences.edit().putBoolean("desktop-${projectRevision(tab.endpoint)}", enabled).apply()
    }

    fun select() { mutableActive.value = mutableCurrent.value }
    fun showMenu() { mutableCurrent.value?.menuVisible?.value = true }
    fun hide() { mutableActive.value?.menuVisible?.value = false; mutableActive.value = null }
    fun closeTab() {
        val current = mutableCurrent.value ?: return
        hide(); mutableCurrent.value = null
        tabs.remove(current.endpoint)?.destroy()
    }
    fun close() { mutableActive.value = null; mutableCurrent.value = null; tabs.values.forEach { it.destroy() }; tabs.clear() }
}
