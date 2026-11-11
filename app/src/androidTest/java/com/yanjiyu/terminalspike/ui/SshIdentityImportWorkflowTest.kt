package com.yanjiyu.terminalspike.ui

import android.provider.Settings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.Until
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.yanjiyu.terminalspike.MainActivity
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.TerminalSpikeApplication
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses the real screen/launcher/ViewModel, normal Downloads provider, Room and credential store. */
class SshIdentityImportWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun assertKeyListed(name: String) {
        // Room commits before the catalog flow has reached Compose. Wait for the actual list,
        // scrolling its lazy rows rather than requiring a not-yet-composed row to exist.
        compose.waitUntil(10000) {
            runCatching {
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
                compose.onNodeWithText(name).assertIsDisplayed()
            }.isSuccess
        }
    }

    @Test fun pastedKeysUseProductionValidationAndPersistWithoutOpeningAnyPicker() {
        val context = compose.activity
        val container = (context.applicationContext as TerminalSpikeApplication).container
        val repository = container.terminalDataRepository
        val prefix = "paste-regression-${System.nanoTime()}"
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var originalClip: ClipData? = null
        var clipboardCaptured = false
        val before = runBlocking { container.sshKeyIdentities.observeAll().first().map { it.id }.toSet() }
        fun identities() = runBlocking { container.sshKeyIdentities.observeAll().first() }
        fun openImport() {
            compose.onNodeWithContentDescription(context.getString(R.string.connections_add_item)).performClick()
            compose.onNodeWithText(context.getString(R.string.connections_import_key)).performClick()
            compose.onNodeWithText(context.getString(R.string.key_import_paste)).performClick()
        }
        try {
            compose.waitUntil(15000) {
                val gotIt = compose.onAllNodes(hasText(context.getString(R.string.release_notes_got_it)))
                if (gotIt.fetchSemanticsNodes().isNotEmpty()) gotIt[0].performClick()
                compose.onAllNodes(hasContentDescription(context.getString(R.string.navigation_open_settings)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(context.getString(R.string.navigation_open_settings)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.settings_category_list_description))
                .performScrollToNode(hasText(context.getString(R.string.settings_category_ssh_keys)))
            compose.onNodeWithText(context.getString(R.string.settings_category_ssh_keys)).performClick()
            openImport()
            compose.onNodeWithText(context.getString(R.string.key_import_text)).performTextInput("discard-on-recreation")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(context.getString(R.string.key_import_text)).assertDoesNotExist()
            assertEquals(before, identities().map { it.id }.toSet())
            openImport()
            compose.onNodeWithText(context.getString(R.string.key_import_text)).performTextInput("not a key")
            compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
            assertEquals(before, identities().map { it.id }.toSet())
            openImport()
            compose.onNodeWithText(context.getString(R.string.key_import_text)).performTextInput("not a key")
            compose.onNodeWithText(context.getString(R.string.connections_import_key)).performClick()
            compose.waitUntil(8000) {
                compose.onAllNodes(hasText(context.getString(R.string.notice_key_import_invalid_format)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(before, identities().map { it.id }.toSet())
            compose.runOnIdle { originalClip = clipboard.primaryClip; clipboardCaptured = true }
            for (encrypted in listOf(false, true)) {
                val name = "$prefix-$encrypted"
                val key = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
                val output = ByteArrayOutputStream()
                try {
                    if (encrypted) key.writeOpenSSHv1PrivateKey(output, "test-passphrase".toByteArray())
                    else key.writePrivateKey(output)
                } finally { key.dispose() }
                val bytes = output.toByteArray()
                try {
                    openImport()
                    compose.onNodeWithText(context.getString(R.string.key_import_name)).performTextInput(name)
                    compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("test key", bytes.toString(Charsets.UTF_8))) }
                    compose.onNodeWithText(context.getString(R.string.key_import_clipboard)).performScrollTo().assertIsDisplayed().performClick()
                    compose.onNodeWithText(context.getString(R.string.connections_import_key)).performClick()
                    compose.waitUntil(10000) { identities().any { it.name == name } }
                    val identity = identities().single { it.name == name }
                    assertEquals(encrypted, identity.isPassphraseProtected)
                    val stored = runBlocking { repository.copyPrivateKey(identity.id) }
                    try { assertArrayEquals(bytes, stored) } finally { stored.fill(0) }
                    compose.activityRule.scenario.recreate()
                    assertKeyListed(name)
                } finally { bytes.fill(0) }
            }
            assertEquals(before.size + 2, identities().size)
        } finally {
            if (clipboardCaptured) compose.runOnIdle {
                originalClip?.let(clipboard::setPrimaryClip) ?: clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            runBlocking {
                identities().filter { it.id !in before && it.name.startsWith(prefix) }.forEach { repository.deleteIdentity(it.id) }
            }
        }
    }

    @Test fun downloadsExtensionlessKeyIsImportedPersistedAndListedWhileInvalidInputIsRejected() {
        // Use the Activity's configured app language, which may differ from the system language.
        val context = compose.activity
        val container = (context.applicationContext as TerminalSpikeApplication).container
        val repository = container.terminalDataRepository
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val name = "spike-import-custom-${System.nanoTime()}"
        val invalidName = "$name-invalid"
        val key = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        val bytes = ByteArrayOutputStream().also(key::writePrivateKey).toByteArray()
        key.dispose()
        val before = runBlocking { container.sshKeyIdentities.observeAll().first().map { it.id }.toSet() }
        var importedId: String? = null
        fun writeDownload(fileName: String, contents: ByteArray) {
            // Only generated disposable test material is copied; never a user's key.
            // Use direct commands: UiAutomation does not interpret shell quoting/redirection.
            val source = requireNotNull(context.externalCacheDir).resolve(fileName)
            try {
                source.writeBytes(contents)
                device.executeShellCommand("mkdir -p /sdcard/Download")
                device.executeShellCommand("cp ${source.absolutePath} /sdcard/Download/$fileName")
                assertEquals("Download fixture must exist before opening the picker", contents.size.toString(),
                    device.executeShellCommand("stat -c %s /sdcard/Download/$fileName").trim())
            } finally { source.delete() }
        }
        fun launchPicker(system: Boolean = false) {
            compose.onNodeWithContentDescription(context.getString(R.string.connections_add_item)).performClick()
            compose.onNodeWithText(context.getString(R.string.connections_import_key)).performClick()
            compose.onNodeWithText(context.getString(if (system) R.string.key_import_system_file else R.string.key_import_file)).performClick()
            assertTrue("Production import must launch the system picker",
                device.wait(Until.hasObject(By.res(Pattern.compile(".*:id/toolbar"))), 8000))
        }
        fun chooseDownload(fileName: String) {
            // Browse the actual internal-storage/Download directory, not indexed Downloads/Recent.
            // Shell-created files are not DownloadManager entries and need not appear in those views.
            device.waitForIdle()
            val deviceName = Settings.Global.getString(context.contentResolver, "device_name")
            val storageNames = "${Pattern.quote(deviceName ?: android.os.Build.MODEL)}|Internal storage|Internal shared storage|内部存储空间|内部存储"
            val storageSelector = By.res("android:id/title").text(Pattern.compile(storageNames))
            if (device.findObject(storageSelector) == null) {
                val roots = device.findObject(By.desc(Pattern.compile("Show roots|显示根目录")))
                assertNotNull("System picker must offer storage roots", roots)
                roots!!.click()
                device.waitForIdle()
            }
            val storage = device.wait(Until.findObject(storageSelector), 5000)
            assertNotNull("Internal storage root must be available", storage)
            // Resolve at click time: DocumentsUI rebuilds drawer rows as providers finish loading.
            assertTrue(device.findObject(UiSelector().resourceId("android:id/title")
                .textMatches(storageNames)).click())
            device.waitForIdle()
            // DocumentsUI remembers each directory's scroll position across picker invocations.
            val directory = UiScrollable(UiSelector().resourceIdMatches(".*:id/dir_list"))
            directory.scrollToBeginning(30)
            val folder = UiSelector().resourceId("android:id/title").text("Download")
            if (!device.findObject(folder).exists()) {
                directory.scrollIntoView(folder)
            }
            val folderNode = device.wait(Until.findObject(By.res("android:id/title").text("Download")), 5000)
            assertNotNull("Actual Download directory must be reachable", folderNode)
            // A pinned toolbar can cover the upper half of a scrolled row; tap its visible lower edge.
            var folderBounds = folderNode!!.visibleBounds
            val toolbarBottom = device.findObject(By.res(Pattern.compile(".*:id/toolbar")))!!.visibleBounds.bottom
            // UiScrollable may reveal a row underneath DocumentsUI's pinned toolbar.
            // Move that row into the unobscured list before tapping it.
            if (folderBounds.bottom - 3 <= toolbarBottom + 24) {
                assertTrue(device.swipe(device.displayWidth / 2, device.displayHeight / 2,
                    device.displayWidth / 2, device.displayHeight * 3 / 4, 20))
                device.waitForIdle()
                folderBounds = device.findObject(By.res("android:id/title").text("Download")).visibleBounds
            }
            assertTrue("Download row must be below the pinned toolbar", folderBounds.bottom - 3 > toolbarBottom + 24)
            assertTrue(device.click(folderBounds.centerX(), folderBounds.bottom - 3))
            assertTrue("Tapping Download must navigate out of the storage root",
                device.wait(Until.gone(By.res("android:id/title").text("Download")), 5000))
            device.waitForIdle()
            device.findObject(By.desc(Pattern.compile("List View|List view|列表视图")))?.click()
            device.waitForIdle()
            if (device.findObject(By.res("android:id/title").text(fileName)) == null) {
                UiScrollable(UiSelector().resourceIdMatches(".*:id/dir_list"))
                    .scrollIntoView(UiSelector().resourceId("android:id/title").text(fileName))
            }
            val file = device.wait(Until.findObject(By.res("android:id/title").text(fileName)), 8000)
            assertNotNull("Extensionless file must be listed in Downloads", file)
            assertTrue("Extensionless file must be selectable", file!!.isEnabled)
            file.click()
        }
        try {
            writeDownload(name, bytes)
            writeDownload(invalidName, "not a private key".toByteArray())
            compose.waitUntil(15000) {
                val gotIt = compose.onAllNodes(hasText(context.getString(R.string.release_notes_got_it)))
                if (gotIt.fetchSemanticsNodes().isNotEmpty()) gotIt[0].performClick()
                compose.onAllNodes(hasContentDescription(context.getString(R.string.navigation_open_settings)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(context.getString(R.string.navigation_open_settings)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.settings_category_list_description))
                .performScrollToNode(hasText(context.getString(R.string.settings_category_ssh_keys)))
            compose.onNodeWithText(context.getString(R.string.settings_category_ssh_keys)).performClick()

            launchPicker()
            device.pressBack()
            // On first launch Back may close an automatically opened roots drawer first.
            if (!device.wait(Until.gone(By.res(Pattern.compile(".*:id/toolbar"))), 1000)) device.pressBack()
            assertTrue("Cancelling must return to the app",
                device.wait(Until.gone(By.res(Pattern.compile(".*:id/toolbar"))), 5000))
            compose.waitForIdle()
            assertEquals(before, runBlocking { container.sshKeyIdentities.observeAll().first().map { it.id }.toSet() })

            launchPicker()
            chooseDownload(invalidName)
            compose.waitUntil(8000) {
                compose.onAllNodes(hasText(context.getString(R.string.notice_key_import_invalid_format)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(before, runBlocking { container.sshKeyIdentities.observeAll().first().map { it.id }.toSet() })

            launchPicker(system = true)
            chooseDownload(name)
            compose.waitUntil(10000) {
                importedId = runBlocking {
                    container.sshKeyIdentities.observeAll().first().singleOrNull { it.name == name }?.id
                }
                importedId != null
            }
            assertKeyListed(name)
            val stored = runBlocking { repository.copyPrivateKey(requireNotNull(importedId)) }
            try { assertArrayEquals(bytes, stored) } finally { stored.fill(0) }
            assertEquals(before + requireNotNull(importedId),
                runBlocking { container.sshKeyIdentities.observeAll().first().map { it.id }.toSet() })
            compose.activityRule.scenario.recreate()
            assertKeyListed(name)
            assertEquals(name, runBlocking { container.sshKeyIdentities.get(requireNotNull(importedId)) }?.name)
        } catch (failure: Throwable) {
            runCatching { device.dumpWindowHierarchy(context.filesDir.resolve("key-import-workflow-failure.xml")) }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            // Delete only this test's identity/files, including after a partially completed import.
            runBlocking {
                container.sshKeyIdentities.observeAll().first().filter { it.name == name && it.id !in before }
                    .forEach { repository.deleteIdentity(it.id) }
            }
            device.executeShellCommand("rm -f /sdcard/Download/$name /sdcard/Download/$invalidName")
            bytes.fill(0)
        }
    }
}
