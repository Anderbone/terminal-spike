import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.BuiltArtifactsLoader
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.protobuf)
}

abstract class GenerateLegalNotices : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val noticeSource: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val outputFile = outputDirectory.file("THIRD_PARTY_NOTICES.md").get().asFile
        outputFile.parentFile.mkdirs()
        noticeSource.get().asFile.copyTo(outputFile, overwrite = true)
    }
}

abstract class VerifyReleasePackaging : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mappingFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:Classpath
    abstract val jschArtifacts: ConfigurableFileCollection

    @get:Input
    abstract val runtimeComponents: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val noticeSource: RegularFileProperty

    @get:Input
    abstract val noticePath: Property<String>

    @TaskAction
    fun verify() {
        verifyRuntimeComponents(runtimeComponents.get())
        verifyTerminalMapping(mappingFile.get().asFile)
        val expectedClasses = jschArtifacts.files.flatMapTo(sortedSetOf()) { artifact ->
            ZipFile(artifact).use { archive ->
                archive.entries().asSequence()
                    .map { entry -> entry.name }
                    .filter(::isKeptJschClass)
                    .map { path -> path.removeSuffix(".class").replace('/', '.') }
                    .toList()
            }
        }
        if (expectedClasses.isEmpty()) {
            throw GradleException("Could not derive reflection-kept classes from the resolved JSch artifact.")
        }

        val classMappings = mappingFile.get().asFile.useLines { lines ->
            lines.filter { line ->
                line.isNotEmpty() && !line[0].isWhitespace() && line.endsWith(':') && " -> " in line
            }.associate { line ->
                val (original, renamed) = line.removeSuffix(":").split(" -> ", limit = 2)
                original to renamed
            }
        }
        expectedClasses.forEach { className ->
            val renamed = classMappings[className]
            if (renamed != className) {
                throw GradleException(
                    "Release R8 must retain reflection-loaded JSch class $className by its original name; " +
                        "mapping contained ${renamed ?: "no live class"}.",
                )
            }
        }

        val builtArtifacts = builtArtifactsLoader.get().load(apkDirectory.get())
            ?: throw GradleException("Could not load release APK metadata.")
        if (builtArtifacts.elements.isEmpty()) throw GradleException("No release APK was produced.")
        val expectedNotices = noticeSource.get().asFile.readBytes()
        builtArtifacts.elements.forEach { artifact ->
            verifyTerminalArchive(File(artifact.outputFile))
            ZipFile(artifact.outputFile).use { archive ->
                val entry = archive.getEntry(noticePath.get())
                    ?: throw GradleException("${artifact.outputFile} is missing ${noticePath.get()}.")
                val packagedNotices = archive.getInputStream(entry).use { stream -> stream.readBytes() }
                if (!packagedNotices.contentEquals(expectedNotices)) {
                    throw GradleException(
                        "${artifact.outputFile} contains notices that differ from THIRD_PARTY_NOTICES.md.",
                    )
                }
            }
        }
    }

    private fun isKeptJschClass(path: String): Boolean {
        if (!path.endsWith(".class") || path.startsWith("META-INF/versions/")) return false
        val root = "com/jcraft/jsch/"
        if (!path.startsWith(root)) return false
        val relative = path.removePrefix(root)
        return when {
            relative.startsWith("jce/") -> true
            relative.startsWith("jbcrypt/") -> true
            relative.startsWith("jzlib/") -> true
            '/' in relative -> false
            relative.startsWith("DH") -> true
            relative in REFLECTED_ROOT_CLASSES -> true
            else -> false
        }
    }

    companion object {
        private val FORBIDDEN_TERMINAL_CLASS_PREFIXES = listOf(
            "com.yanjiyu.terminalspike.terminal.FakeTerminalSession",
            "com.yanjiyu.terminalspike.workload.WorkloadGenerator",
            "com.yanjiyu.terminalspike.workload.StreamingWorkload",
            "com.yanjiyu.terminalspike.workload.FullScreenWorkload",
            "com.yanjiyu.terminalspike.workload.LocalWorkloadEngine",
            "com.yanjiyu.terminalspike.workload.DebugLocalWorkloadEngine",
            "com.yanjiyu.terminalspike.workload.WorkloadMode",
            "com.yanjiyu.terminalspike.workload.StreamingRate",
            "com.yanjiyu.terminalspike.workload.FullScreenRate",
            "com.yanjiyu.terminalspike.workload.PreloadSize",
            "com.yanjiyu.terminalspike.ui.DebugTerminalBuildFeature",
            "com.yanjiyu.terminalspike.ui.TerminalBuildUiState",
            "com.yanjiyu.terminalspike.ui.TerminalControlsKt",
            "com.yanjiyu.terminalspike.performance.TerminalBenchmarkActivity",
            "com.yanjiyu.terminalspike.terminal.benchmark.",
            "com.yanjiyu.terminalspike.mosh.MoshExtensionActivity",
            "com.yanjiyu.terminalspike.mosh.MoshExtensionService",
            "com.yanjiyu.terminalspike.mosh.MoshNativeBridge",
            "com.yanjiyu.terminalspike.mosh.MoshWorkerService",
            "com.yanjiyu.terminalspike.mosh.internal.",
        )
        private val FORBIDDEN_TERMINAL_MAPPING_MEMBERS = listOf("PerformanceOverlay(", "isBenchmark(")
        private val FORBIDDEN_TERMINAL_TEXT = listOf(
            "local benchmark",
            "continuing benchmark output",
            "Terminal Spike TUI",
            "Streaming output rate",
            "Full screen update rate",
            "Preload line count",
            "Performance overlay",
            "Native renderer lab",
            "Renderer lab",
            "Developer diagnostics",
            "Open renderer lab",
            "isBenchmark",
            "Terminal fixture running",
            "Terminal fixture complete",
            "plain-100k",
            "ansi-heavy",
            "cursor-redraw",
            "full-screen-redraw",
            "cjk-wide",
            "tmux-status",
        )
        private val FORBIDDEN_TERMINAL_TOKENS = listOf("Bench")
        private val FORBIDDEN_MOSH_IMPLEMENTATION_DESCRIPTORS = listOf(
            "Lcom/yanjiyu/terminalspike/mosh/MoshExtensionActivity;",
            "Lcom/yanjiyu/terminalspike/mosh/MoshExtensionService;",
            "Lcom/yanjiyu/terminalspike/mosh/MoshNativeBridge;",
            "Lcom/yanjiyu/terminalspike/mosh/MoshWorkerService;",
            "Lcom/yanjiyu/terminalspike/mosh/internal/",
            "Java_com_yanjiyu_terminalspike_mosh_MoshNativeBridge",
        )
        private val FORBIDDEN_TEST_CREDENTIAL_TEXT = listOf(
            "terminal-spike-test-only",
            "terminal-spike integration client",
            "terminal-spike integration host",
            "terminal-spike rotated integration host",
            "correct horse battery staple",
            "fresh credential",
            "retry-passphrase",
            "private key test material",
            "legacy private key",
            "secret-key-body-",
            "unterminated-secret",
        )
        private val FORBIDDEN_RUNTIME_PROJECT_PATHS = setOf(":mosh-extension")
        private val FORBIDDEN_RUNTIME_MODULE_GROUP_PREFIXES = setOf(
            "com.github.mobile-shell",
            "com.googlecode.mosh",
            "com.termux",
            "org.connectbot",
            "org.gnu",
        )
        private val FORBIDDEN_RUNTIME_MODULE_NAMES = setOf(
            "hogweed",
            "libmosh",
            "mosh",
            "mosh-client",
            "nettle",
        )
        private val FORBIDDEN_NATIVE_ARCHIVE_EXTENSIONS = setOf("a", "o", "so")
        private val PRIVATE_KEY_BLOCK_PATTERN = Regex(
            """-----BEGIN ([A-Z0-9 ]*PRIVATE KEY)-----(.*?)-----END \1-----""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val REFLECTED_ROOT_CLASSES = setOf(
            "CipherNone.class",
            "UserAuthNone.class",
            "UserAuthPassword.class",
            "UserAuthKeyboardInteractive.class",
            "UserAuthPublicKey.class",
        )

        fun verifyRuntimeComponents(components: Iterable<String>) {
            components.forEach { component ->
                val fields = component.split('|')
                when (fields.firstOrNull()) {
                    "project" -> {
                        val projectPath = fields.getOrNull(1).orEmpty()
                        if (projectPath in FORBIDDEN_RUNTIME_PROJECT_PATHS) {
                            throw GradleException(
                                "Main release runtime graph contains forbidden project $projectPath.",
                            )
                        }
                    }

                    "module" -> {
                        val group = fields.getOrNull(1).orEmpty()
                        val module = fields.getOrNull(2).orEmpty()
                        val forbiddenGroup = FORBIDDEN_RUNTIME_MODULE_GROUP_PREFIXES.firstOrNull {
                            prefix -> group == prefix || group.startsWith("$prefix.")
                        }
                        if (
                            forbiddenGroup != null ||
                            module.lowercase() in FORBIDDEN_RUNTIME_MODULE_NAMES
                        ) {
                            throw GradleException(
                                "Main release runtime graph contains forbidden module $group:$module.",
                            )
                        }
                    }
                }
            }
        }

        fun verifyTerminalMapping(mappingFile: File) {
            val mappingText = mappingFile.readText()
            val liveOriginalClasses = mappingText.lineSequence()
                .filter { line ->
                    line.isNotEmpty() && !line[0].isWhitespace() && line.endsWith(':') && " -> " in line
                }
                .map { line -> line.substringBefore(" -> ") }
                .toList()
            FORBIDDEN_TERMINAL_CLASS_PREFIXES.forEach { forbidden ->
                val retained = liveOriginalClasses.firstOrNull { className ->
                    className == forbidden || className.startsWith("$forbidden$") ||
                        (forbidden.endsWith('.') && className.startsWith(forbidden))
                }
                if (retained != null) {
                    throw GradleException(
                        "Release artifact retained forbidden implementation class $retained.",
                    )
                }
            }
            FORBIDDEN_TERMINAL_MAPPING_MEMBERS.forEach { forbidden ->
                if (forbidden in mappingText) {
                    throw GradleException("Release artifact retained forbidden developer terminal member $forbidden.")
                }
            }
        }

        fun verifyTerminalArchive(archiveFile: File) {
            ZipFile(archiveFile).use { archive ->
                archive.entries().asSequence()
                    .filterNot { entry -> entry.isDirectory }
                    .forEach { entry ->
                        if (isForbiddenMoshNativeEntry(entry.name)) {
                            throw GradleException(
                                "${archiveFile.name} contains forbidden Mosh native entry ${entry.name}.",
                            )
                        }
                        val bytes = archive.getInputStream(entry).use { stream -> stream.readBytes() }
                        FORBIDDEN_MOSH_IMPLEMENTATION_DESCRIPTORS.forEach { forbidden ->
                            if (bytes.containsAsciiIgnoringCase(forbidden)) {
                                throw GradleException(
                                    "${archiveFile.name} contains forbidden Mosh implementation " +
                                        "descriptor '$forbidden' in ${entry.name}.",
                                )
                            }
                        }
                        FORBIDDEN_TEST_CREDENTIAL_TEXT.forEach { forbidden ->
                            if (
                                bytes.containsAsciiIgnoringCase(forbidden) ||
                                bytes.containsUtf16LeIgnoringCase(forbidden)
                            ) {
                                throw GradleException(
                                    "${archiveFile.name} contains an exact test credential token " +
                                        "in ${entry.name}.",
                                )
                            }
                        }
                        val privateKeyFingerprints = bytes.privateKeyPayloadFingerprints()
                        if (privateKeyFingerprints.isNotEmpty()) {
                            throw GradleException(
                                "${archiveFile.name} contains an actual private-key payload in " +
                                    "${entry.name} (SHA-256 ${privateKeyFingerprints.joinToString()}).",
                            )
                        }
                        FORBIDDEN_TERMINAL_TEXT.forEach { forbidden ->
                            if (
                                bytes.containsAsciiIgnoringCase(forbidden) ||
                                bytes.containsUtf16LeIgnoringCase(forbidden)
                            ) {
                                throw GradleException(
                                    "${archiveFile.name} contains forbidden developer terminal text " +
                                        "'$forbidden' in ${entry.name}.",
                                )
                            }
                        }
                        FORBIDDEN_TERMINAL_TOKENS.forEach { forbidden ->
                            if (bytes.containsDelimitedAsciiIgnoringCase(forbidden)) {
                                throw GradleException(
                                    "${archiveFile.name} contains forbidden developer terminal token " +
                                        "'$forbidden' in ${entry.name}.",
                                )
                            }
                        }
                    }
            }
        }

        private fun isForbiddenMoshNativeEntry(entryName: String): Boolean {
            val fileName = entryName.substringAfterLast('/').lowercase()
            if (
                fileName.substringAfterLast('.', missingDelimiterValue = "") !in
                FORBIDDEN_NATIVE_ARCHIVE_EXTENSIONS
            ) {
                return false
            }
            return fileName.startsWith("libmosh") ||
                fileName.startsWith("libnettle") ||
                fileName.startsWith("libhogweed")
        }

        private fun ByteArray.privateKeyPayloadFingerprints(): Set<String> =
            sequenceOf(
                toString(StandardCharsets.ISO_8859_1),
                toString(StandardCharsets.UTF_16LE),
            ).flatMap { text -> PRIVATE_KEY_BLOCK_PATTERN.findAll(text) }
                .mapNotNull { match ->
                    val payload = match.groupValues[2]
                        .lineSequence()
                        .map(String::trim)
                        .filter { line -> line.isNotEmpty() && ':' !in line }
                        .joinToString(separator = "")
                    if (
                        payload.length < 64 ||
                        payload.any { character ->
                            !character.isLetterOrDigit() && character !in "+/="
                        }
                    ) {
                        return@mapNotNull null
                    }
                    val decoded = runCatching { Base64.getDecoder().decode(payload) }.getOrNull()
                        ?.takeIf { material -> material.size >= 32 }
                        ?: return@mapNotNull null
                    MessageDigest.getInstance("SHA-256")
                        .digest(decoded)
                        .joinToString(separator = "") { byte ->
                            "%02x".format(byte.toInt() and 0xff)
                        }
                }.toSet()

        private fun ByteArray.containsAsciiIgnoringCase(text: String): Boolean =
            containsBytesIgnoringAsciiCase(text.encodeToByteArray())

        private fun ByteArray.containsUtf16LeIgnoringCase(text: String): Boolean {
            val encoded = ByteArray(text.length * 2)
            text.forEachIndexed { index, character ->
                encoded[index * 2] = character.code.toByte()
                encoded[index * 2 + 1] = (character.code ushr 8).toByte()
            }
            return containsBytesIgnoringAsciiCase(encoded)
        }

        private fun ByteArray.containsDelimitedAsciiIgnoringCase(text: String): Boolean {
            val needle = text.encodeToByteArray()
            if (needle.isEmpty() || needle.size > size) return false
            for (start in 0..size - needle.size) {
                val leftDelimited = start == 0 || !(this[start - 1].toInt() and 0xff).isAsciiWordCharacter()
                if (!leftDelimited) continue
                var matches = true
                for (offset in needle.indices) {
                    val actual = this[start + offset].toInt() and 0xff
                    val expected = needle[offset].toInt() and 0xff
                    if (actual.asciiLowercase() != expected.asciiLowercase()) {
                        matches = false
                        break
                    }
                }
                val end = start + needle.size
                val rightDelimited = end == size || !(this[end].toInt() and 0xff).isAsciiWordCharacter()
                if (matches && rightDelimited) return true
            }
            return false
        }

        private fun ByteArray.containsBytesIgnoringAsciiCase(needle: ByteArray): Boolean {
            if (needle.isEmpty() || needle.size > size) return false
            for (start in 0..size - needle.size) {
                var matches = true
                for (offset in needle.indices) {
                    val actual = this[start + offset].toInt() and 0xff
                    val expected = needle[offset].toInt() and 0xff
                    if (actual.asciiLowercase() != expected.asciiLowercase()) {
                        matches = false
                        break
                    }
                }
                if (matches) return true
            }
            return false
        }

        private fun Int.asciiLowercase(): Int = if (this in 'A'.code..'Z'.code) this + 32 else this

        private fun Int.isAsciiWordCharacter(): Boolean =
            this in 'A'.code..'Z'.code || this in 'a'.code..'z'.code || this in '0'.code..'9'.code ||
                this == '_'.code
    }
}

abstract class VerifyReleaseBundlePackaging : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mappingFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundleFile: RegularFileProperty

    @get:Classpath
    abstract val jschArtifacts: ConfigurableFileCollection

    @get:Input
    abstract val runtimeComponents: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val noticeSource: RegularFileProperty

    @get:Input
    abstract val noticePath: Property<String>

    @TaskAction
    fun verify() {
        VerifyReleasePackaging.verifyRuntimeComponents(runtimeComponents.get())
        VerifyReleasePackaging.verifyTerminalMapping(mappingFile.get().asFile)
        val expectedClasses = jschArtifacts.files.flatMapTo(sortedSetOf()) { artifact ->
            ZipFile(artifact).use { archive ->
                archive.entries().asSequence()
                    .map { entry -> entry.name }
                    .filter(::isKeptJschClass)
                    .map { path -> path.removeSuffix(".class").replace('/', '.') }
                    .toList()
            }
        }
        if (expectedClasses.isEmpty()) {
            throw GradleException("Could not derive reflection-kept classes from the resolved JSch artifact.")
        }

        val classMappings = mappingFile.get().asFile.useLines { lines ->
            lines.filter { line ->
                line.isNotEmpty() && !line[0].isWhitespace() && line.endsWith(':') && " -> " in line
            }.associate { line ->
                val (original, renamed) = line.removeSuffix(":").split(" -> ", limit = 2)
                original to renamed
            }
        }
        expectedClasses.forEach { className ->
            val renamed = classMappings[className]
            if (renamed != className) {
                throw GradleException(
                    "Release R8 must retain reflection-loaded JSch class $className by its original name; " +
                        "mapping contained ${renamed ?: "no live class"}.",
                )
            }
        }

        val expectedNotices = noticeSource.get().asFile.readBytes()
        VerifyReleasePackaging.verifyTerminalArchive(bundleFile.get().asFile)
        ZipFile(bundleFile.get().asFile).use { archive ->
            val entry = archive.getEntry(noticePath.get())
                ?: throw GradleException(
                    "${bundleFile.get().asFile} is missing ${noticePath.get()}.",
                )
            val packagedNotices = archive.getInputStream(entry).use { stream -> stream.readBytes() }
            if (!packagedNotices.contentEquals(expectedNotices)) {
                throw GradleException(
                    "${bundleFile.get().asFile} contains notices that differ from THIRD_PARTY_NOTICES.md.",
                )
            }
        }
    }

    private fun isKeptJschClass(path: String): Boolean {
        if (!path.endsWith(".class") || path.startsWith("META-INF/versions/")) return false
        val root = "com/jcraft/jsch/"
        if (!path.startsWith(root)) return false
        val relative = path.removePrefix(root)
        return when {
            relative.startsWith("jce/") -> true
            relative.startsWith("jbcrypt/") -> true
            relative.startsWith("jzlib/") -> true
            '/' in relative -> false
            relative.startsWith("DH") -> true
            relative in REFLECTED_ROOT_CLASSES -> true
            else -> false
        }
    }

    companion object {
        private val REFLECTED_ROOT_CLASSES = setOf(
            "CipherNone.class",
            "UserAuthNone.class",
            "UserAuthPassword.class",
            "UserAuthKeyboardInteractive.class",
            "UserAuthPublicKey.class",
        )
    }
}

val thirdPartyNoticesSource = rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")

data class ReleaseSigningValues(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

val releaseSigningInputs = listOf(
    providers.gradleProperty("terminalSpike.releaseStoreFile")
        .orElse(providers.environmentVariable("TERMINAL_SPIKE_RELEASE_STORE_FILE"))
        .orNull,
    providers.gradleProperty("terminalSpike.releaseStorePassword")
        .orElse(providers.environmentVariable("TERMINAL_SPIKE_RELEASE_STORE_PASSWORD"))
        .orNull,
    providers.gradleProperty("terminalSpike.releaseKeyAlias")
        .orElse(providers.environmentVariable("TERMINAL_SPIKE_RELEASE_KEY_ALIAS"))
        .orNull,
    providers.gradleProperty("terminalSpike.releaseKeyPassword")
        .orElse(providers.environmentVariable("TERMINAL_SPIKE_RELEASE_KEY_PASSWORD"))
        .orNull,
)
val suppliedReleaseSigningInputs = releaseSigningInputs.count { !it.isNullOrEmpty() }
if (suppliedReleaseSigningInputs !in setOf(0, releaseSigningInputs.size)) {
    throw GradleException(
        "Release signing is only enabled when store file, store password, key alias, and key " +
            "password are all supplied.",
    )
}
val releaseSigningValues = if (suppliedReleaseSigningInputs == releaseSigningInputs.size) {
    ReleaseSigningValues(
        storeFile = releaseSigningInputs[0]!!,
        storePassword = releaseSigningInputs[1]!!,
        keyAlias = releaseSigningInputs[2]!!,
        keyPassword = releaseSigningInputs[3]!!,
    )
} else {
    null
}

extensions.configure<ApplicationExtension>("android") {
    namespace = "com.yanjiyu.terminalspike"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yanjiyu.terminalspike"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val configuredReleaseSigning = releaseSigningValues?.let { values ->
        signingConfigs.create("release") {
            storeFile = rootProject.file(values.storeFile)
            storePassword = values.storePassword
            keyAlias = values.keyAlias
            keyPassword = values.keyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (configuredReleaseSigning != null) signingConfig = configuredReleaseSigning
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("testDebug").kotlin.directories.add("src/benchmarkFixtures/java")
        maybeCreate("nonMinifiedRelease").apply {
            kotlin.directories.add("src/release/java")
            kotlin.directories.add("src/benchmarkFixtures/java")
            res.srcDir("src/release/res")
            assets.srcDir("src/test/resources/terminal-benchmark-fixtures")
        }
        maybeCreate("benchmarkRelease").apply {
            kotlin.directories.add("src/nonMinifiedRelease/java")
            kotlin.directories.add("src/benchmarkFixtures/java")
            assets.srcDir("src/test/resources/terminal-benchmark-fixtures")
        }
    }

}

kotlin {
    jvmToolchain(17)
}

room {
    schemaDirectory("$projectDir/schemas")
}

val protobufCompilerVersion = extensions.getByType(VersionCatalogsExtension::class.java)
    .named("libs")
    .findVersion("protobuf")
    .orElseThrow()
    .requiredVersion

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufCompilerVersion"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
        }
    }
}

dependencies {
    baselineProfile(project(":benchmark"))
    implementation(project(":mosh-api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    // Lifecycle 2.10 otherwise constrains serialization to 1.7.3, while Room 2.8.4's schema
    // serializers require the 1.8.1 core ABI in the migration-test APK.
    implementation(platform(libs.kotlinx.serialization.bom))
    implementation(libs.jsch)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.datastore)
    implementation(libs.protobuf.kotlin.lite)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

androidComponents {
    onVariants { variant ->
        if (variant.name == "benchmarkRelease") {
            variant.sources.manifests.addStaticManifestFile(
                "src/nonMinifiedRelease/AndroidManifest.xml",
            )
        }
        val capitalizedVariant = variant.name.replaceFirstChar { character -> character.titlecase() }
        val generateNotices = tasks.register<GenerateLegalNotices>(
            "generate${capitalizedVariant}LegalNotices",
        ) {
            noticeSource.set(thirdPartyNoticesSource)
            outputDirectory.set(
                layout.buildDirectory.dir("generated/legalNotices/${variant.name}"),
            )
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateNotices,
            GenerateLegalNotices::outputDirectory,
        )
    }

    onVariants(selector().withBuildType("release")) { variant ->
        val capitalizedVariant = variant.name.replaceFirstChar { character -> character.titlecase() }
        val runtimeClasspath = configurations.getByName("${variant.name}RuntimeClasspath")
        val resolvedJsch = runtimeClasspath.incoming.artifactView {
            componentFilter { identifier ->
                identifier is ModuleComponentIdentifier &&
                    identifier.group == "com.github.mwiede" && identifier.module == "jsch"
            }
        }.files
        val resolvedRuntimeComponents = providers.provider {
            runtimeClasspath.incoming.resolutionResult.allComponents.map { component ->
                when (val identifier = component.id) {
                    is ModuleComponentIdentifier ->
                        "module|${identifier.group}|${identifier.module}|${identifier.version}"
                    is ProjectComponentIdentifier -> "project|${identifier.projectPath}"
                    else -> "other|${identifier.displayName}"
                }
            }.sorted()
        }
        val verifyPackaging = tasks.register<VerifyReleasePackaging>(
            "verify${capitalizedVariant}Packaging",
        ) {
            group = "verification"
            description =
                "Verifies release isolation, credentials, runtime dependencies, JSch, and notices."
            mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            jschArtifacts.from(resolvedJsch)
            runtimeComponents.set(resolvedRuntimeComponents)
            noticeSource.set(thirdPartyNoticesSource)
            noticePath.set("assets/THIRD_PARTY_NOTICES.md")
        }
        val verifyBundlePackaging = tasks.register<VerifyReleaseBundlePackaging>(
            "verify${capitalizedVariant}BundlePackaging",
        ) {
            group = "verification"
            description =
                "Verifies release bundle isolation, credentials, dependencies, JSch, and notices."
            mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
            bundleFile.set(variant.artifacts.get(SingleArtifact.BUNDLE))
            jschArtifacts.from(resolvedJsch)
            runtimeComponents.set(resolvedRuntimeComponents)
            noticeSource.set(thirdPartyNoticesSource)
            noticePath.set("base/assets/THIRD_PARTY_NOTICES.md")
        }

        tasks.matching { task -> task.name == "assemble$capitalizedVariant" }.configureEach {
            dependsOn(verifyPackaging)
        }
        tasks.matching { task -> task.name == "bundle$capitalizedVariant" }.configureEach {
            dependsOn(verifyBundlePackaging)
        }
        tasks.matching { task -> task.name == "check" }.configureEach {
            dependsOn(verifyPackaging)
            dependsOn(verifyBundlePackaging)
        }
    }
}
