package com.yanjiyu.terminalspike.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.content.pm.PackageManager
import com.yanjiyu.terminalspike.backup.PrivateKeyPickerDocumentsProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SshIdentityImportPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun extensionlessCustomNamedKeyCanBeSelectedAndParsed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val name = "spike-regression-custom-${System.nanoTime()}"
        val key = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        val bytes = ByteArrayOutputStream().also(key::writePrivateKey).toByteArray()
        key.dispose()
        val fixture = context.filesDir.resolve("key-picker-fixtures").apply { mkdirs() }.resolve(name)
        val component = ComponentName(context, PrivateKeyPickerDocumentsProvider::class.java)
        context.packageManager.setComponentEnabledSetting(component,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        val selected = AtomicReference<android.net.Uri?>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            fixture.writeBytes(bytes)
            compose.setContent {
                val contract = object : ActivityResultContracts.OpenDocument() {
                    override fun createIntent(context: Context, input: Array<String>): Intent =
                        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                            DocumentsContract.buildDocumentUri(
                                "com.yanjiyu.terminalspike.test.keypicker.documents", "root"))
                }
                val launcher = rememberLauncherForActivityResult(contract) {
                    selected.set(it)
                }
                Button(onClick = { launcher.launch(sshIdentityImportMimeTypes()) }) { Text("Pick regression key") }
            }
            compose.onNodeWithText("Pick regression key").performClick()
            val file = device.wait(Until.findObject(By.res("android:id/title").text(name)), 8000)
            assertNotNull("Extensionless key must be visible regardless of provider MIME type", file)
            assertTrue("Extensionless key must be selectable regardless of provider MIME type", file!!.isEnabled)
            file!!.click()
            compose.waitUntil(5000) { selected.get() != null }
            val loaded = resolver.openInputStream(selected.get()!!)!!.use { it.readBytes() }
            try {
                assertArrayEquals(bytes, loaded)
                val parsed = KeyPair.load(JSch(), loaded, null)
                try { assertEquals(KeyPair.RSA, parsed.keyType) } finally { parsed.dispose() }
            } finally { loaded.fill(0) }
        } finally {
            if (selected.get() == null) device.dumpWindowHierarchy(context.filesDir.resolve("picker-failure.xml"))
            device.pressBack()
            fixture.delete()
            context.packageManager.setComponentEnabledSetting(component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            bytes.fill(0)
        }
    }
}
