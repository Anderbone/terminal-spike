package com.yanjiyu.terminalspike.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseUiLiteralAllowListTest {
    @Test
    fun directReleaseUiLiteralsStayWithinReviewedProtocolAndInternalAllowList() {
        val sourceRoot = sequenceOf(
            Path.of("src/main/java/com/yanjiyu/terminalspike"),
            Path.of("app/src/main/java/com/yanjiyu/terminalspike"),
        ).first(Files::isDirectory)
        val releaseSurfaces = listOf(
            "ui/AboutSection.kt",
            "ui/DeveloperSettingsSection.kt",
            "ui/ExtraKeysBar.kt",
            "ui/LocalWorkspaceScreen.kt",
            "ui/SshConnectionBar.kt",
            "ui/TerminalSpikeScreen.kt",
            "ui/connections/ConnectionsComponents.kt",
            "ui/connections/ConnectionsEditors.kt",
            "ui/connections/ConnectionsScreen.kt",
            "ui/settings/SettingsScreen.kt",
            "ui/terminal/TerminalFindDialog.kt",
            "ui/terminal/TerminalSessionActions.kt",
            "terminal/view/FastTerminalView.kt",
            "terminal/view/TerminalViewBridge.kt",
        )
        val directText = Regex("""Text\(\"([^\"]*[A-Za-z][^\"]*)\"""")
        val rawLabel = Regex("""\blabel\s*=\s*\"([^\"]*[A-Za-z][^\"]*)\"""")
        val rawDescription = Regex("""contentDescription\s*=\s*\"([^\"]*[A-Za-z][^\"]*)\"""")
        val discovered = releaseSurfaces.flatMap { relative ->
            Files.readAllLines(sourceRoot.resolve(relative)).flatMap { line ->
                listOfNotNull(
                    directText.find(line)?.groupValues?.get(1),
                    rawLabel.find(line)?.groupValues?.get(1),
                    rawDescription.find(line)?.groupValues?.get(1),
                )
            }
        }.toSet()

        // Protocol/algorithm identifiers and Compose animation labels are intentionally not copy.
        val reviewed = setOf(
            "SSH",
            "Mosh",
            "Ed25519",
            "RSA 4096",
            "TERM",
            "\${endpoint.username}@\${endpoint.host}:\${endpoint.port}",
            "session-tab",
            "host-filter-colour",
            "host-selection",
            "key-selection",
            "snippet-selection",
            "primary-workspace",
            "M",
        )

        assertEquals(emptySet<String>(), discovered - reviewed)
    }

    @Test
    fun authoredTerminalNoticesCannotRegressToRawStrings() {
        val sourceRoot = sequenceOf(
            Path.of("src/main/java/com/yanjiyu/terminalspike"),
            Path.of("app/src/main/java/com/yanjiyu/terminalspike"),
        ).first(Files::isDirectory)
        val source = Files.readString(sourceRoot.resolve("ui/TerminalSpikeViewModel.kt"))
        val rawNotice = Regex(
            """(?:notice|successNotice|failureMessage)\s*=\s*\"([^\"]*[A-Za-z][^\"]*)\"""",
        ).findAll(source).map { it.groupValues[1] }.toSet()

        assertEquals(emptySet<String>(), rawNotice)
        assertEquals(false, source.contains("val notice: String?"))
        assertEquals(false, source.contains("fun consumeNotice(presentedNotice: String)"))
        assertEquals(false, source.contains("UiText.Dynamic(\""))
    }

    @Test
    fun terminalKeyCopyUsesResourcesWhileReviewedKeycapTokensStayVerbatim() {
        val sourceRoot = sequenceOf(
            Path.of("src/main/java/com/yanjiyu/terminalspike"),
            Path.of("app/src/main/java/com/yanjiyu/terminalspike"),
        ).first(Files::isDirectory)
        val accessory = Files.readString(
            sourceRoot.resolve("terminal/view/TerminalAccessoryAction.kt"),
        )
        val extraKey = Files.readString(sourceRoot.resolve("terminal/view/TerminalExtraKey.kt"))
        val reviewedTokens = setOf("CTRL", "ALT", "SHIFT")
        val tokenLiterals = Regex("""uiToken\(\"([^\"]+)\"\)""")
            .findAll(accessory)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(reviewedTokens, tokenLiterals)
        assertEquals(false, accessory.contains("val label: String"))
        assertEquals(false, accessory.contains("val accessibilityDescription: String"))
        assertEquals(false, extraKey.contains("val accessibilityDescription: String"))
    }
}
