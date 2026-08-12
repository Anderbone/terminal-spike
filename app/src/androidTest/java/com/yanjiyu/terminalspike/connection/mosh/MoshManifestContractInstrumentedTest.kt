package com.yanjiyu.terminalspike.connection.mosh

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoshManifestContractInstrumentedTest {
    @Test
    fun appRequestsExtensionBindPermissionAndUsesExactComponentIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )

        assertTrue(
            packageInfo.requestedPermissions.orEmpty().contains(MoshExtensionContract.BIND_PERMISSION),
        )
        assertEquals(MoshExtensionContract.PACKAGE_NAME, MoshExtensionContract.component.packageName)
        assertEquals(
            MoshExtensionContract.SERVICE_CLASS_NAME,
            MoshExtensionContract.component.className,
        )
        assertEquals(
            PackageManager.SIGNATURE_MATCH,
            context.packageManager.checkSignatures(context.packageName, context.packageName),
        )
    }
}
