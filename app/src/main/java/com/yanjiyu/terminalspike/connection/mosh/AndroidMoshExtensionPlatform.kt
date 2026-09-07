package com.yanjiyu.terminalspike.connection.mosh

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

internal interface MoshExtensionPlatform {
    fun discover(): MoshDiscoveryDecision

    fun bind(connection: ServiceConnection): Boolean

    fun unbind(connection: ServiceConnection)
}

internal class AndroidMoshExtensionPlatform(
    context: Context,
) : MoshExtensionPlatform {
    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager

    override fun discover(): MoshDiscoveryDecision {
        val extensionInfo = try {
            packageInfo(MoshExtensionContract.PACKAGE_NAME)
        } catch (_: PackageManager.NameNotFoundException) {
            return MoshDiscoveryDecision.Absent
        } catch (_: RuntimeException) {
            return MoshDiscoveryDecision.QueryFailed
        }

        return try {
            val serviceInfo = try {
                serviceInfo(MoshExtensionContract.component)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            val facts = MoshInstalledPackageFacts(
                version = extensionInfo.toVersion(),
                applicationEnabled = extensionInfo.applicationInfo?.let { applicationInfo ->
                    applicationInfo.enabled && packageEnabled(MoshExtensionContract.PACKAGE_NAME)
                } ?: false,
                servicePresent = serviceInfo != null,
                serviceEnabled = serviceInfo?.let { info ->
                    info.enabled && componentEnabled(MoshExtensionContract.component)
                } ?: false,
                serviceExported = serviceInfo?.exported == true,
                sameApplicationUid = serviceInfo?.applicationInfo?.uid == applicationContext.applicationInfo.uid,
                separateBrokerProcess = serviceInfo?.processName == "${applicationContext.packageName}:mosh_broker",
            )
            MoshExtensionDecision.discover(facts)
        } catch (_: PackageManager.NameNotFoundException) {
            MoshDiscoveryDecision.QueryFailed
        } catch (_: RuntimeException) {
            MoshDiscoveryDecision.QueryFailed
        }
    }

    override fun bind(connection: ServiceConnection): Boolean = applicationContext.bindService(
        Intent(MoshExtensionContract.BIND_ACTION).setComponent(MoshExtensionContract.component),
        connection,
        Context.BIND_AUTO_CREATE,
    )

    override fun unbind(connection: ServiceConnection) {
        applicationContext.unbindService(connection)
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageName: String): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        packageManager.getPackageInfo(packageName, flags)
    }

    @Suppress("DEPRECATION")
    private fun serviceInfo(component: ComponentName) = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getServiceInfo(
            component,
            PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
        )
    } else {
        packageManager.getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
    }

    private fun packageEnabled(packageName: String): Boolean = when (
        packageManager.getApplicationEnabledSetting(packageName)
    ) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
        -> false
        else -> true
    }

    private fun componentEnabled(component: ComponentName): Boolean = when (
        packageManager.getComponentEnabledSetting(component)
    ) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
        -> false
        else -> true
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.toVersion(): MoshExtensionVersion {
        val code = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
        val safeName = versionName
            ?.filterNot(Char::isISOControl)
            ?.take(MAX_VERSION_NAME_CHARS)
            ?.ifBlank { null }
        return MoshExtensionVersion(code, safeName)
    }

    private companion object {
        const val MAX_VERSION_NAME_CHARS = 64
    }
}
