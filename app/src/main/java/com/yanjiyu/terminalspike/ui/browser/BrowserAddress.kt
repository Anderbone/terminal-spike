package com.yanjiyu.terminalspike.ui.browser

import java.net.URI
import java.net.URLEncoder

/** Addresses are user supplied; SSH credentials are never embedded in browser URLs. */
internal fun browserAddress(input: String): String {
    // Chat/clipboard text can surround URLs with invisible direction or word-joining marks.
    // Preserve meaningful joiners in paths, while discarding presentation-only copy markers.
    val value = input.filterNot { it in COPIED_URL_MARKERS }.trim()
    require(value.isNotEmpty() && value.length <= 4096 && value.none { it.isWhitespace() || it.isISOControl() })
    val uri = try { URI(if ("://" in value) value else "https://$value").parseServerAuthority() }
        catch (error: java.net.URISyntaxException) { throw IllegalArgumentException("Invalid server address.", error) }
    require(uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank())
    require(uri.rawUserInfo == null && uri.port in -1..65535 && uri.port != 0)
    return uri.toASCIIString()
}

private val COPIED_URL_MARKERS = setOf('\u200B', '\u200E', '\u200F', '\u202A', '\u202B', '\u202C',
    '\u202D', '\u202E', '\u2060', '\u2066', '\u2067', '\u2068', '\u2069', '\uFEFF')

internal fun browserProjectAddress(address: String, directory: String?): String {
    if (directory.isNullOrBlank()) return address
    require(directory.startsWith('/') && directory.none(Char::isISOControl))
    val uri = URI(browserAddress(address))
    val query = uri.rawQuery.orEmpty().split('&').filter {
        it.isNotEmpty() && it.substringBefore('=') !in setOf("folder", "workspace")
    } + "folder=${URLEncoder.encode(directory, Charsets.UTF_8.name())}"
    return address.substringBefore('#').substringBefore('?') + "?" + query.joinToString("&") +
        (uri.rawFragment?.let { "#$it" } ?: "")
}

internal fun browserNavigationAllowed(address: String, entryAddress: String): Boolean = runCatching {
    val target = URI(browserAddress(address))
    val entry = URI(browserAddress(entryAddress))
    target.scheme.equals("https", true) ||
        (target.scheme.equals(entry.scheme, true) && target.host.equals(entry.host, true) &&
            (if (target.port == -1) 80 else target.port) == (if (entry.port == -1) 80 else entry.port))
}.getOrDefault(false)

internal fun desktopBrowserAgent(mobile: String): String = mobile
    .replace("; wv", "")
    .replace(Regex("Linux; Android[^)]*"), "X11; Linux x86_64")
    .replace(" Mobile", "")
