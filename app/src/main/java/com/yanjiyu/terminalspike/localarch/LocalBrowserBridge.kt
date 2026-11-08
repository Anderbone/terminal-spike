package com.yanjiyu.terminalspike.localarch

import com.yanjiyu.terminalspike.localarch.control.controlError
import com.yanjiyu.terminalspike.localarch.control.parseControlRequest
import com.yanjiyu.terminalspike.localarch.control.requireFields
import com.yanjiyu.terminalspike.localarch.control.string
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Same-UID local shells only. URLs live in memory until a foreground, unlocked UI opens them. */
internal class LocalBrowserBridge(
    initialChooseBrowser: Boolean = false,
    private val saveChooseBrowser: (Boolean) -> Unit = {},
) {
    internal class Request(val url: String, val choose: Boolean, val code: String?) {
        val result = CompletableDeferred<Boolean>()
    }

    private val lock = Mutex()
    private val mutableChooseBrowser = MutableStateFlow(initialChooseBrowser)
    val chooseBrowser = mutableChooseBrowser.asStateFlow()
    private val mutableRequest = MutableStateFlow<Request?>(null)
    val request = mutableRequest.asStateFlow()

    suspend fun open(url: String, choose: Boolean = false, code: String? = null): Boolean {
        requireLocalBrowserUrl(url)
        if (!lock.tryLock()) return false
        val pending = Request(url, choose || chooseBrowser.value, code)
        try {
            mutableRequest.value = pending
            return withTimeoutOrNull(8_000) { pending.result.await() } == true
        } finally {
            mutableRequest.value = null
            pending.result.cancel()
            lock.unlock()
        }
    }

    fun setChooseBrowser(value: Boolean) {
        saveChooseBrowser(value)
        mutableChooseBrowser.value = value
    }

    suspend fun handle(frame: String): String = try {
        val (method, params) = parseControlRequest(frame)
        require(method == "browser.open")
        params.requireFields("url", "choose")
        val choose = params["choose"]?.let {
            require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null)
            it.booleanOrNull!!
        } ?: false
        if (open(params.string("url"), choose)) {
            buildJsonObject { put("api", 1); put("ok", true) }.toString()
        } else {
            controlError("BROWSER_UNAVAILABLE", "Keep the app unlocked and visible, then retry. Check that a browser is enabled.").toString()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        controlError("INVALID_ARGUMENT", "Expected one HTTP or HTTPS URL.").toString()
    }
}

internal fun requireLocalBrowserUrl(value: String) {
    require(value.length in 1..16_384 && value.none { it.isWhitespace() || it.isISOControl() })
    val uri = URI.create(value)
    require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null)
    require(uri.port in -1..65535)
}
