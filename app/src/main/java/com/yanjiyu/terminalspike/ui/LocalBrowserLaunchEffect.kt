package com.yanjiyu.terminalspike.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanjiyu.terminalspike.localarch.LocalBrowserBridge

/** Never launch over the app lock or from a background terminal process. */
@Composable
internal fun LocalBrowserLaunchEffect(bridge: LocalBrowserBridge, enabled: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val request by bridge.request.collectAsStateWithLifecycle()
    LaunchedEffect(request, state, enabled) {
        val pending = request ?: return@LaunchedEffect
        if (!enabled || state != Lifecycle.State.RESUMED || pending.result.isCompleted) return@LaunchedEffect
        pending.result.complete(runCatching {
            openLocalBrowser(context, pending.url, pending.choose, pending.code)
        }.isSuccess)
    }
}

internal fun openLocalBrowser(context: Context, url: String, choose: Boolean = false, code: String? = null) {
    com.yanjiyu.terminalspike.localarch.requireLocalBrowserUrl(url)
    code?.let {
        val clip = ClipData.newPlainText("Device login code", it)
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url).normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE)
    // Resolve browsers using a hostless web URL, so installed GitHub/other apps do not
    // capture an OAuth link through Android App Links instead of opening a browser.
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE)
    val packages = context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo.packageName }.distinct()
    if (packages.isEmpty()) throw ActivityNotFoundException("Enable a phone browser")
    val defaultPackage = context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName?.takeIf { it in packages }
    val selected = if (choose) null else defaultPackage ?: packages.singleOrNull()
    if (selected != null) context.startActivity(intent.setPackage(selected))
    else {
        val choices = packages.map { Intent(intent).setPackage(it) }
        context.startActivity(Intent.createChooser(choices.first(), null).putExtra(
            Intent.EXTRA_INITIAL_INTENTS, choices.drop(1).toTypedArray(),
        ))
    }
}
