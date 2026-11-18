package com.yanjiyu.terminalspike

import android.app.Application
import android.os.StrictMode
import com.yanjiyu.terminalspike.core.security.AppLogEvent
import com.yanjiyu.terminalspike.core.security.credential.createAndroidIdentityImportRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TerminalSpikeApplication : Application() {
    lateinit var container: AppContainer
        private set
    private lateinit var applicationScope: CoroutineScope

    internal val identityImportRecovery by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createAndroidIdentityImportRecovery(this) { issue ->
            // Provider exceptions can contain private document URIs; retain only the issue code.
            container.logger.warning(AppLogEvent.SETTINGS_WRITE_FAILED) {
                "Identity import recovery requires attention: ${issue.name}."
            }
        }
    }

    override fun getResources(): android.content.res.Resources =
        if (android.os.Build.VERSION.SDK_INT < 33 && baseContext != null) {
            com.yanjiyu.terminalspike.settings.AppLanguage.wrap(baseContext).resources
        } else {
            super.getResources()
        }

    override fun onCreate() {
        super.onCreate()
        // Native transport services run in private processes. They must not initialize the
        // workspace, credential store, startup migrations or another Mosh client.
        val processName = if (android.os.Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000')
        }
        if (processName != packageName) return
        if (BuildConfig.DEBUG) {
            com.yanjiyu.terminalspike.core.security.InteractionDiagnostics.initialize(this)
        }
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        container = AppContainer(this, applicationScope)
        applicationScope.launch { identityImportRecovery }
        container.resolveStartup()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectActivityLeaks()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build(),
            )
        }
    }

    override fun onTerminate() {
        if (::applicationScope.isInitialized) applicationScope.cancel()
        super.onTerminate()
    }
}
