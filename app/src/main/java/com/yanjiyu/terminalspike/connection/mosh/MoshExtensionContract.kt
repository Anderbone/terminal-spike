package com.yanjiyu.terminalspike.connection.mosh

import android.content.ComponentName

internal object MoshExtensionContract {
    const val PACKAGE_NAME = "com.yanjiyu.terminalspike"
    const val SERVICE_CLASS_NAME = "com.yanjiyu.terminalspike.mosh.MoshExtensionService"
    const val BIND_ACTION = "com.yanjiyu.terminalspike.mosh.BIND"
    const val BINDER_DESCRIPTOR = "com.yanjiyu.terminalspike.mosh.api.IMoshPlugin"

    val component: ComponentName = ComponentName(PACKAGE_NAME, SERVICE_CLASS_NAME)
}
