package com.yanjiyu.terminalspike

import android.app.ActivityManager
import android.content.Context

internal fun interface LocalAppDataResetter {
    /**
     * Asks Android to erase this package's complete user-data area. An accepted request stops the
     * app process; the next launch starts from the same state as Settings > Clear storage.
     */
    fun requestReset(): Boolean
}

/** Platform-owned last-resort reset for data that cannot be safely opened by app-owned stores. */
internal class AndroidLocalAppDataResetter private constructor(
    private val clearApplicationUserData: () -> Boolean,
) : LocalAppDataResetter {
    constructor(context: Context) : this(
        clearApplicationUserData = {
            context.applicationContext
                .getSystemService(ActivityManager::class.java)
                ?.clearApplicationUserData() == true
        },
    )

    override fun requestReset(): Boolean = clearApplicationUserData()

    internal companion object {
        fun forTest(clearApplicationUserData: () -> Boolean): LocalAppDataResetter =
            AndroidLocalAppDataResetter(clearApplicationUserData)
    }
}
