package com.yanjiyu.terminalspike.connection.mosh

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoshManifestContractInstrumentedTest {
    @Test
    fun appRequestsExtensionBindPermissionAndUsesExactComponentIdentity() {
        // Retain the contract's test identity while replacing the obsolete two-APK requirement.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val broker = manager.getServiceInfo(MoshExtensionContract.component, 0)
        assertEquals(context.packageName, MoshExtensionContract.component.packageName)
        assertFalse(broker.exported)
        assertEquals(context.applicationInfo.uid, broker.applicationInfo.uid)
        assertEquals("${context.packageName}:mosh_broker", broker.processName)
        for (slot in 0 until 10) {
            @Suppress("DEPRECATION")
            val worker = manager.getServiceInfo(
                ComponentName(context.packageName, "com.yanjiyu.terminalspike.mosh.MoshWorker${slot}Service"), 0,
            )
            assertFalse(worker.exported)
            assertEquals(context.applicationInfo.uid, worker.applicationInfo.uid)
            assertEquals("${context.packageName}:mosh_session_$slot", worker.processName)
        }
        assertTrue(AndroidMoshExtensionPlatform(context).discover() is MoshDiscoveryDecision.Trusted)
        @Suppress("DEPRECATION")
        val activities = manager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES).activities.orEmpty()
        assertFalse(activities.any { it.name.endsWith("MoshExtensionActivity") })
    }
}
