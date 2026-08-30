import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

private data class ExtensionReleaseSigningValues(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

private val extensionReleaseSigningInputs = listOf(
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
private val suppliedExtensionReleaseSigningInputs = extensionReleaseSigningInputs.count {
    !it.isNullOrEmpty()
}
if (suppliedExtensionReleaseSigningInputs !in setOf(0, extensionReleaseSigningInputs.size)) {
    throw GradleException(
        "Release signing is only enabled when store file, store password, key alias, and key " +
            "password are all supplied.",
    )
}
private val extensionReleaseSigningValues = if (
    suppliedExtensionReleaseSigningInputs == extensionReleaseSigningInputs.size
) {
    ExtensionReleaseSigningValues(
        storeFile = extensionReleaseSigningInputs[0]!!,
        storePassword = extensionReleaseSigningInputs[1]!!,
        keyAlias = extensionReleaseSigningInputs[2]!!,
        keyPassword = extensionReleaseSigningInputs[3]!!,
    )
} else {
    null
}

abstract class BuildMoshNativeTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceInputs: ConfigurableFileCollection

    @get:Internal
    abstract val sdkDirectory: DirectoryProperty

    @get:Internal
    abstract val extensionDirectory: DirectoryProperty

    @get:Internal
    abstract val buildScript: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    abstract val nativeWorkDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun buildNative() {
        execOperations.exec {
            workingDir(extensionDirectory.get().asFile)
            commandLine(
                "bash",
                buildScript.get().asFile.absolutePath,
                sdkDirectory.get().asFile.absolutePath,
                nativeWorkDirectory.get().asFile.absolutePath,
                outputDirectory.get().asFile.absolutePath,
            )
        }.assertNormalExitValue()
    }
}

extensions.configure<ApplicationExtension>("android") {
    namespace = "com.yanjiyu.terminalspike.mosh"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.yanjiyu.terminalspike.mosh"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "1.0.1-mosh-1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    val configuredReleaseSigning = extensionReleaseSigningValues?.let { values ->
        signingConfigs.create("release") {
            storeFile = rootProject.file(values.storeFile)
            storePassword = values.storePassword
            keyAlias = values.keyAlias
            keyPassword = values.keyPassword
        }
    }

    buildTypes {
        debug {
            isJniDebuggable = true
        }
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
        aidl = true
        buildConfig = true
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    sourceSets.named("main") {
        assets.directories.add("licenses")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

val buildMoshNative = tasks.register<BuildMoshNativeTask>("buildMoshNative") {
    group = "build"
    description = "Builds the pinned Mosh client for arm64-v8a and x86_64."
    sourceInputs.from(
        fileTree("third_party") { include("**/*") },
        fileTree("patches") { include("**/*") },
        fileTree("scripts") { include("**/*") },
        fileTree("src/main/cpp") { include("**/*") },
    )
    extensionDirectory.set(layout.projectDirectory)
    buildScript.set(layout.projectDirectory.file("scripts/build-native.sh"))
    sdkDirectory.set(androidComponents.sdkComponents.sdkDirectory)
    nativeWorkDirectory.set(layout.buildDirectory.dir("native"))
    outputDirectory.set(layout.buildDirectory.dir("generated/moshJniLibs"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            buildMoshNative,
            BuildMoshNativeTask::outputDirectory,
        )
    }
}

dependencies {
    implementation(project(":mosh-api"))
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
