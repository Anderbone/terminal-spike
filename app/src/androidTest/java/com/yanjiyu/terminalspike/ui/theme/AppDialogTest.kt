package com.yanjiyu.terminalspike.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.yanjiyu.terminalspike.TerminalSpikeComponentTestActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppDialogTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalSpikeComponentTestActivity>()

    @Test fun shortConfirmationWrapsContentAndRetainsTouchSizedActions() {
        compose.setContent {
            TerminalSpikeTheme(themeMode = AppThemeMode.DARK, updateSystemBarIcons = false) {
                AppDialog(
                    onDismissRequest = {}, modifier = Modifier.testTag("dialog-panel"),
                    title = { Text("Delete snippet?") }, text = { Text("This removes the saved command.") },
                    confirmButton = { TextButton(onClick = {}, modifier = Modifier.testTag("confirm")) { Text("Delete") } },
                    dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                )
            }
        }
        val panel = compose.onNodeWithTag("dialog-panel").fetchSemanticsNode().boundsInRoot
        val screen = compose.activity.resources.displayMetrics.heightPixels
        assertTrue("Short dialog should wrap its content", panel.height < screen * 0.65f)
        compose.onNodeWithTag("confirm").assertIsDisplayed().assertTouchHeightIsEqualTo(48.dp)
        savePreview("compact-confirmation.png")
    }

    @Test fun longEditorScrollsWhileTitleAndActionsStayVisibleWithKeyboard() {
        val value = mutableStateOf("")
        compose.setContent {
            TerminalSpikeTheme(themeMode = AppThemeMode.LIGHT, updateSystemBarIcons = false) {
                AppEditorDialog(
                    onDismissRequest = {}, title = { Text("Edit snippet") },
                    text = {
                        Column {
                            OutlinedTextField(value.value, { value.value = it }, modifier = Modifier.testTag("name"), label = { Text("Name") })
                            repeat(24) { Text("Command detail $it", Modifier.padding(vertical = 12.dp)) }
                            Text("Last detail", Modifier.testTag("last-detail"))
                        }
                    },
                    confirmButton = { TextButton(onClick = {}, modifier = Modifier.testTag("save")) { Text("Save") } },
                    dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                )
            }
        }
        compose.onNodeWithTag("name").performTextInput("My command")
        compose.onNodeWithText("Edit snippet").assertIsDisplayed()
        compose.onNodeWithTag("save").assertIsDisplayed().assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("last-detail").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("save").assertIsDisplayed()
    }

    @Test fun actualSnippetEditorUsesTheCompactFrameAndRetainsItsActions() {
        compose.setContent {
            TerminalSpikeTheme(themeMode = AppThemeMode.DARK, updateSystemBarIcons = false) {
                com.yanjiyu.terminalspike.ui.connections.SnippetEditorDialog(
                    initial = com.yanjiyu.terminalspike.ui.connections.SnippetEditorDraft(
                        name = "Project session", command = "cd ~/projects\ncodex",
                    ),
                    onDismiss = {}, onSave = { Result.success(Unit) },
                )
            }
        }
        compose.onNodeWithTag(com.yanjiyu.terminalspike.ui.connections.SnippetEditorNameTestTag).assertIsDisplayed()
        compose.onNodeWithTag(com.yanjiyu.terminalspike.ui.connections.SnippetEditorSaveTestTag).assertIsDisplayed()
        savePreview("compact-snippet-editor.png")
    }

    private fun savePreview(name: String) {
        // Preview artifacts are optional; Compose dialog capture requires API 28.
        if (android.os.Build.VERSION.SDK_INT < 28) return
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        java.io.File(compose.activity.cacheDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
