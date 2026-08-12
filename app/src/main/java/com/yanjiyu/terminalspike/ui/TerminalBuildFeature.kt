package com.yanjiyu.terminalspike.ui

import kotlinx.coroutines.flow.StateFlow

internal interface TerminalBuildFeature {
    val keepScreenOn: StateFlow<Boolean>

    fun stop()

    fun onAppVisible(visible: Boolean)
}
