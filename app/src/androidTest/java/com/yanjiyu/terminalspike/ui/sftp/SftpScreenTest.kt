package com.yanjiyu.terminalspike.ui.sftp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.yanjiyu.terminalspike.connection.SftpFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Rule
import org.junit.Test

class SftpScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val controller = SftpSessionController(scope) { error("No network expected") }

    @After
    fun close() = controller.close()

    @Test
    fun savedHostAuthenticationCanBeCancelledWithoutOpeningNetwork() {
        var closed = false
        controller.requestAuthentication("host-id", "Production", SftpSecretKind.PASSWORD)
        composeRule.setContent {
            MaterialTheme {
                SftpScreen(
                    controller = controller,
                    scope = scope,
                    onSubmitAuthentication = { _, secret -> secret.fill('\u0000') },
                    onClose = { closed = true; controller.close() },
                )
            }
        }

        composeRule.onNodeWithTag(SftpScreenTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Open files on Production").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { check(closed) }
    }

    @Test
    fun compactFolderRowUsesAnIconAndLongPressesToSelect() {
        var opened = false
        var selected = false
        composeRule.setContent {
            MaterialTheme {
                SftpFileRow(
                    file = SftpFile(
                        name = "projects",
                        path = "/projects",
                        isDirectory = true,
                        size = 0,
                        modifiedAtEpochSeconds = 0,
                    ),
                    selected = false,
                    enabled = true,
                    onOpen = { opened = true },
                    onSelect = { selected = true },
                )
            }
        }

        composeRule.onNodeWithTag(SftpFolderIconTestTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Folder").assertDoesNotExist()
        composeRule.onNodeWithTag("$SftpItemTestTagPrefix/projects")
            .performTouchInput { longClick() }
        composeRule.runOnIdle {
            check(selected)
            check(!opened)
        }
    }

    @Test
    fun tappingAFileOpensItWithoutUsingTapAsSelection() {
        var opened = false
        var selected = false
        composeRule.setContent {
            MaterialTheme {
                SftpFileRow(
                    file = SftpFile(
                        name = "notes.txt",
                        path = "/notes.txt",
                        isDirectory = false,
                        size = 2_048,
                        modifiedAtEpochSeconds = 0,
                    ),
                    selected = false,
                    enabled = true,
                    onOpen = { opened = true },
                    onSelect = { selected = true },
                )
            }
        }

        composeRule.onNodeWithTag(SftpFileIconTestTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("2 KB").assertIsDisplayed()
        composeRule.onNodeWithTag("$SftpItemTestTagPrefix/notes.txt").performClick()
        composeRule.runOnIdle {
            check(opened)
            check(!selected)
        }
    }

    @Test
    fun selectedFolderCanDownloadToDownloadsOrAChosenPhoneFolder() {
        val folder = SftpFile(
            name = "projects",
            path = "/projects",
            isDirectory = true,
            size = 0,
            modifiedAtEpochSeconds = 0,
        )
        composeRule.setContent {
            MaterialTheme {
                SftpBrowser(
                    state = SftpUiState.Browsing(
                        hostName = "Production",
                        path = "/",
                        files = listOf(folder),
                        selectedPath = folder.path,
                    ),
                    selectedItem = folder,
                    onClose = {},
                    onUp = {},
                    onOpen = {},
                    onOpenFile = {},
                    onSelect = {},
                    onCopy = {},
                    onCut = {},
                    onPaste = {},
                    onMkdir = {},
                    onRename = {},
                    onDelete = {},
                    onUploadFiles = {},
                    onUploadFolder = {},
                    onDownload = {},
                    onSaveTo = {},
                    onDismissMessage = {},
                )
            }
        }

        composeRule.onNodeWithText("Download").assertExists()
        composeRule.onNodeWithText("Save to…").assertExists()
    }

    @Test
    fun fromPhoneOffersFilesAndFolders() {
        composeRule.setContent {
            MaterialTheme {
                SftpBrowser(
                    state = SftpUiState.Browsing("Production", "/home", emptyList()),
                    selectedItem = null,
                    onClose = {},
                    onUp = {},
                    onOpen = {},
                    onOpenFile = {},
                    onSelect = {},
                    onCopy = {},
                    onCut = {},
                    onPaste = {},
                    onMkdir = {},
                    onRename = {},
                    onDelete = {},
                    onUploadFiles = {},
                    onUploadFolder = {},
                    onDownload = {},
                    onSaveTo = {},
                    onDismissMessage = {},
                )
            }
        }

        composeRule.onNodeWithText("From phone").performClick()
        composeRule.onNodeWithText("Choose files").assertIsDisplayed()
        composeRule.onNodeWithText("Choose folder").assertIsDisplayed()
    }
}
