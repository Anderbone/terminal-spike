package com.yanjiyu.terminalspike.ui.browser

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/** Network policy survives UI detachment; the address provider must not retain an Activity. */
internal open class BrowserNavigationClient(
    private val entryAddress: () -> String,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val blocked = !browserNavigationAllowed(request.url.toString(), entryAddress())
        if (blocked && request.isForMainFrame) onBlockedNavigation()
        return blocked
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // Plaintext remains limited to the exact HTTP origin entered by the user.
        return if (request.url.scheme.equals("http", ignoreCase = true) &&
            !browserNavigationAllowed(request.url.toString(), entryAddress())) {
            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
        } else null
    }

    protected open fun onBlockedNavigation() = Unit
}
