package com.yanjiyu.terminalspike

import android.app.Application
import android.os.StrictMode

class TerminalSpikeApplication : Application() {
    lateinit var container: AppContainer
        private set

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
        container = AppContainer(this)
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
}
