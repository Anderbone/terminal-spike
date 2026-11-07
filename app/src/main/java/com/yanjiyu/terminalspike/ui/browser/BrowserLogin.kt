package com.yanjiyu.terminalspike.ui.browser

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

private data class PendingBrowserLogin(val password: String, val remember: Boolean)

/** No JS bridge or page-wide password capture: fill only the verified code-server POST form. */
@Composable
internal fun BrowserLogin(
    vault: BrowserPasswordVault,
    view: WebView?,
    entryAddress: String,
    pageVersion: Int,
    request: Int,
    onNotice: (String) -> Unit,
) {
    val target = remember(entryAddress) { BrowserLoginTarget.from(entryAddress) }
    val scope = rememberCoroutineScope()
    // Secrets deliberately never enter rememberSaveable, Activity saved state, URLs or logs.
    var password by remember(target) { mutableStateOf("") }
    var rememberPassword by remember(target) { mutableStateOf(true) }
    var dialog by remember(target) { mutableStateOf(false) }
    var loginForm by remember(target) { mutableStateOf(false) }
    var saved by remember(target) { mutableStateOf(false) }
    var attemptedAutoLogin by remember(target) { mutableStateOf(false) }
    var pending by remember(target) { mutableStateOf<PendingBrowserLogin?>(null) }
    var busy by remember(target) { mutableStateOf(false) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    var handledRequest by remember { mutableIntStateOf(0) }
    val rejected = stringResource(R.string.browser_password_rejected)
    val unavailable = stringResource(R.string.browser_password_unavailable)
    val saveFailed = stringResource(R.string.browser_password_save_failed)
    val formUnavailable = stringResource(R.string.browser_password_form_unavailable)

    suspend fun submit(current: WebView, secret: String): Boolean {
        if (!target.isLoginPage(current.url.orEmpty())) return false
        return current.browserEvaluate(codeServerFormScript(target, secret)) == "true"
    }

    LaunchedEffect(view, target, pageVersion, request) {
        val current = view ?: run { dialog = false; pending = null; password = ""; return@LaunchedEffect }
        val explicitRequest = request > handledRequest
        handledRequest = request
        val page = current.url.orEmpty()
        val attempt = pending
        if (attempt != null && target.accepts(page) && !target.isLoginPage(page)) {
            pending = null; password = ""; dialog = false
            attemptedAutoLogin = false
            try {
                if (attempt.remember) { vault.save(target, attempt.password); saved = true }
                else { vault.forget(target); saved = false }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onNotice(saveFailed) }
            withContext(Dispatchers.IO) { CookieManager.getInstance().flush() }
            return@LaunchedEffect
        }
        if (!target.accepts(page)) { pending = null; password = ""; dialog = false; return@LaunchedEffect }
        val form = target.isLoginPage(page) && current.browserEvaluate(codeServerFormScript(target)) == "true"
        if (current.url != page) return@LaunchedEffect
        loginForm = form
        if (attempt != null && form) {
            pending = null; password = attempt.password; rememberPassword = attempt.remember
            error = rejected; dialog = true; return@LaunchedEffect
        }
        if (!form && !explicitRequest) return@LaunchedEffect
        if (!explicitRequest && attemptedAutoLogin) return@LaunchedEffect
        attemptedAutoLogin = true
        var stored: String? = null
        try { stored = vault.read(target); saved = stored != null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { saved = true; error = unavailable }
        if (current.url != page) return@LaunchedEffect
        if (form && stored != null && !explicitRequest) {
            pending = PendingBrowserLogin(stored, true)
            if (!submit(current, stored)) { pending = null; error = formUnavailable; dialog = true }
        } else {
            password = stored.orEmpty(); rememberPassword = true; dialog = true
        }
    }

    DisposableEffect(target) {
        onDispose { pending = null; password = "" }
    }

    fun confirm() {
        val current = view ?: return
        scope.launch {
            busy = true; error = null
            try {
                val secret = password
                if (loginForm) {
                    pending = PendingBrowserLogin(secret, rememberPassword)
                    if (submit(current, secret)) { dialog = false; password = "" }
                    else { pending = null; error = formUnavailable }
                } else {
                    if (rememberPassword) { vault.save(target, secret); saved = true }
                    else { vault.forget(target); saved = false }
                    dialog = false; password = ""
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { pending = null; error = saveFailed }
            finally { busy = false }
        }
    }

    if (dialog) {
        AlertDialog(onDismissRequest = { if (!busy) { dialog = false; password = "" } },
            title = { Text(stringResource(if (loginForm) R.string.browser_sign_in else R.string.browser_password)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(target.scope, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(password, { if (it.toByteArray(Charsets.UTF_8).size <= 4096) password = it },
                    label = { Text(stringResource(R.string.browser_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (password.isNotEmpty() && !busy) confirm() }),
                    modifier = Modifier.fillMaxWidth().testTag("browser-password-input"))
                Row {
                    Checkbox(rememberPassword, { rememberPassword = it }, enabled = !busy,
                        modifier = Modifier.testTag("browser-remember-password"))
                    Text(stringResource(R.string.browser_remember_password), modifier = Modifier.padding(top = 12.dp))
                }
                Text(stringResource(R.string.browser_password_storage), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (saved) TextButton(onClick = {
                    scope.launch {
                        try { vault.forget(target); saved = false; password = ""; error = null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = saveFailed }
                    }
                }, enabled = !busy, modifier = Modifier.testTag("browser-forget-password")) {
                    Text(stringResource(R.string.browser_forget_password))
                }
            } },
            confirmButton = { TextButton(onClick = ::confirm, enabled = !busy && password.isNotEmpty(),
                modifier = Modifier.testTag("browser-password-confirm")) {
                Text(stringResource(if (loginForm) R.string.browser_sign_in else R.string.browser_save_password))
            } },
            dismissButton = { TextButton(onClick = { dialog = false; password = "" }, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            } })
    }
}

private suspend fun WebView.browserEvaluate(script: String): String = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it) }
}

private fun codeServerFormScript(target: BrowserLoginTarget, password: String? = null): String = """
    (function(){
      if(location.origin !== ${JSONObject.quote(target.origin)} || location.pathname !== ${JSONObject.quote(target.basePath + "login")}) return false;
      if(!document.title.toLowerCase().includes('code-server')) return false;
      const field=document.querySelector('form.login-form input[type="password"][name="password"]');
      const form=field && field.form;
      if(!form || form.method.toLowerCase() !== 'post' || new URL(form.action).origin !== location.origin || new URL(form.action).pathname !== location.pathname) return false;
      ${if (password == null) "" else """
      field.value=${JSONObject.quote(password)};
      field.dispatchEvent(new Event('input',{bubbles:true}));
      field.dispatchEvent(new Event('change',{bubbles:true}));
      if(form.requestSubmit) form.requestSubmit(); else form.submit();
      """.trimIndent()}
      return true;
    })()
""".trimIndent()
