package com.yanjiyu.terminalspike.ui.browser

import java.net.URI

/** A code-server installation, scoped to its exact origin and reverse-proxy base path. */
internal data class BrowserLoginTarget(val origin: String, val basePath: String) {
    val scope: String get() = origin + basePath
    fun accepts(page: String): Boolean = runCatching {
        val uri = URI(browserAddress(page))
        browserOrigin(uri) == origin && uri.rawPath.orEmpty().ifEmpty { "/" }.startsWith(basePath)
    }.getOrDefault(false)

    fun isLoginPage(page: String): Boolean = accepts(page) &&
        URI(page).rawPath == basePath + "login"

    companion object {
        fun from(address: String): BrowserLoginTarget {
            val uri = URI(browserAddress(address))
            val path = uri.rawPath.orEmpty().removeSuffix("/login").trimEnd('/')
            return BrowserLoginTarget(browserOrigin(uri), "$path/")
        }
    }
}

private fun browserOrigin(uri: URI): String {
    val scheme = uri.scheme.lowercase()
    val host = uri.host.lowercase().let { if (':' in it && !it.startsWith('[')) "[$it]" else it }
    val port = uri.port.takeIf { it != -1 && it != if (scheme == "https") 443 else 80 }
    return "$scheme://$host" + (port?.let { ":$it" } ?: "")
}
