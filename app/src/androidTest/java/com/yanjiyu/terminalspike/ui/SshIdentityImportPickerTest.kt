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

    @Test fun systemPickerUsesResolvedExportedComponentAndUnavailableLaunchesAreRecoverable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val normal = sshIdentityDocumentIntent(context)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, normal.action)
        assertEquals("*/*", normal.type)
        assertTrue(normal.hasCategory(Intent.CATEGORY_OPENABLE))
        val system = sshIdentitySystemDocumentIntent(context)
        assertNotNull("This test device must provide DocumentsUI", system)
        val activity = context.packageManager.getActivityInfo(system!!.component!!, 0)
        assertTrue(activity.exported && activity.enabled)
        assertEquals(normal.action, system.action)
        assertEquals(normal.type, system.type)
        assertFalse(tryLaunchSshIdentityDocument(system) { throw android.content.ActivityNotFoundException() })
        assertFalse(tryLaunchSshIdentityDocument(system) { throw SecurityException() })
        assertTrue(tryLaunchSshIdentityDocument(normal) { assertNull(it.component) })
    }

    @Test fun extensionlessCustomNamedKeyCanBeSelectedAndParsed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val key = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        val bytes = ByteArrayOutputStream().also(key::writePrivateKey).toByteArray()
        key.dispose()
        val directory = context.filesDir.resolve("key-picker-fixtures").apply { mkdirs() }
        val fixtures = listOf("application/octet-stream", "text/plain", "application/x-pem-file",
            "application/x-ssh-private-key").mapIndexed { index, mime ->
            directory.resolve("spike-regression-custom-$index-${System.nanoTime()}") to mime
        }
        val component = ComponentName(context, PrivateKeyPickerDocumentsProvider::class.java)
        context.packageManager.setComponentEnabledSetting(component,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        val selected = AtomicReference<android.net.Uri?>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            fixtures.forEach { (fixture, mime) ->
                fixture.writeBytes(bytes)
                directory.resolve("${fixture.name}.mime").writeText(mime)
            }
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
            for ((fixture, mime) in fixtures) {
                selected.set(null)
                compose.onNodeWithText("Pick regression key").performClick()
                val name = fixture.name
                val file = device.wait(Until.findObject(By.res("android:id/title").text(name)), 8000)
                assertNotNull("Extensionless key must be visible for $mime", file)
                assertTrue("Extensionless key must be selectable regardless of provider MIME type", file!!.isEnabled)
                file!!.click()
                compose.waitUntil(5000) { selected.get() != null }
                val loaded = resolver.openInputStream(selected.get()!!)!!.use { it.readBytes() }
                try {
                    assertArrayEquals(bytes, loaded)
                    val parsed = KeyPair.load(JSch(), loaded, null)
                    try { assertEquals(KeyPair.RSA, parsed.keyType) } finally { parsed.dispose() }
                } finally { loaded.fill(0) }
            }
        } finally {
            if (selected.get() == null) device.dumpWindowHierarchy(context.filesDir.resolve("picker-failure.xml"))
            device.pressBack()
            fixtures.forEach { (fixture, _) ->
                fixture.delete()
                directory.resolve("${fixture.name}.mime").delete()
            }
            context.packageManager.setComponentEnabledSetting(component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            bytes.fill(0)
        }
    }
}
