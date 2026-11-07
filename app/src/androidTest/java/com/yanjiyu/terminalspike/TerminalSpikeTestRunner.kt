package com.yanjiyu.terminalspike

import androidx.test.espresso.IdlingPolicies
import androidx.test.runner.AndroidJUnitRunner
import java.util.concurrent.TimeUnit

/** Gives slow, software-rendered API boundary emulators time to reach a genuinely idle UI. */
class TerminalSpikeTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        // Existing app/terminal suites start at the workspace; guide tests open the tour explicitly.
        targetContext.getSharedPreferences("quick-start", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("acknowledged", true).commit()
        IdlingPolicies.setMasterPolicyTimeout(IDLING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        IdlingPolicies.setIdlingResourceTimeout(IDLING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        super.onStart()
    }

    private companion object {
        const val IDLING_TIMEOUT_SECONDS = 90L
    }
}
