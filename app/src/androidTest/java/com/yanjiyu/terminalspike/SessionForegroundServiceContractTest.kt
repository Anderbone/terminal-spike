package com.yanjiyu.terminalspike

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yanjiyu.terminalspike.connection.SessionForegroundStartResult
import com.yanjiyu.terminalspike.connection.SessionNotificationVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionForegroundServiceContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun deniedNotificationPermissionStillStartsAndReportsLimitedVisibility() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        assumeTrue(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_DENIED,
        )

        val result = AndroidSessionForegroundStarter(context).startFromVisibleUserAction()
        assertEquals(
            SessionForegroundStartResult.Started(
                SessionNotificationVisibility.LIMITED_BY_PERMISSION,
            ),
            result,
        )

        // The Service runs in this instrumentation process. Give onCreate/onStartCommand time to
        // promote and stop its idle instance; an uncaught permission failure terminates this test.
        SystemClock.sleep(500)
    }

    @Test
    fun manifestDeclaresPrivateSpecialUseServiceAndRequiredPermissions() {
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val serviceInfo = packageManager.getServiceInfo(
            ComponentName(context, SessionForegroundService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertFalse(serviceInfo.exported)
        assertEquals(0, serviceInfo.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                serviceInfo.foregroundServiceType,
            )
            assertEquals(
                "active user-started terminal sessions and local Linux installation",
                packageManager.getProperty(
                    "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
                    ComponentName(context, SessionForegroundService::class.java),
                ).getString(),
            )
        }

        @Suppress("DEPRECATION")
        val packageInfo = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in requested)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE in requested)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in requested)
        assertTrue(Manifest.permission.ACCESS_NETWORK_STATE in requested)
        assertTrue(Manifest.permission.WAKE_LOCK in requested)
    }

    @Test
    fun notificationIsImmediatePrivateOngoingAndOffersExplicitTeardown() {
        val factory = SessionNotificationFactory(context)
        factory.ensureChannel()
        val notification = factory.build(
            SessionNotificationState(activeSessionCount = 2, connectedSessionCount = 1),
        )

        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(SessionNotificationFactory.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertFalse(channel.canShowBadge())

        assertEquals(NotificationCompat.CATEGORY_SERVICE, notification.category)
        assertEquals(NotificationCompat.VISIBILITY_SECRET, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertNotNull(notification.contentIntent)
        assertEquals(1, notification.actions.size)
        assertEquals(
            context.getString(R.string.session_notification_disconnect_all),
            notification.actions.single().title.toString(),
        )
        assertNotNull(notification.actions.single().actionIntent)
        assertTrue(notification.actions.single().actionIntent.isActivity)
        assertEquals(
            context.getString(R.string.session_notification_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            context.resources.getQuantityString(R.plurals.session_notification_active_count, 2, 2),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test
    fun privacyOffMayShowOnlyAConservativeFriendlyName() {
        val factory = SessionNotificationFactory(context)
        factory.ensureChannel()

        val named = factory.build(
            SessionNotificationState(
                activeSessionCount = 1,
                connectedSessionCount = 1,
                activeFriendlyName = "Production API",
            ),
            privacyEnabled = false,
        )
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, named.visibility)
        assertEquals(
            context.getString(R.string.session_notification_active_named, "Production API"),
            named.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )

        val redacted = factory.build(
            SessionNotificationState(
                activeSessionCount = 1,
                connectedSessionCount = 1,
                activeFriendlyName = "user@192.168.1.10",
            ),
            privacyEnabled = false,
        )
        assertEquals(
            context.resources.getQuantityString(R.plurals.session_notification_active_count, 1, 1),
            redacted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test
    fun specialUseSubtypeCheckRunsOnPlatformThatEnforcesTheType() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)

        @Suppress("DEPRECATION")
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, SessionForegroundService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            serviceInfo.foregroundServiceType,
        )
    }
}
