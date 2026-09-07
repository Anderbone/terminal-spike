import com.android.build.api.dsl.LibraryExtension
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
    alias(libs.plugins.android.library)
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

extensions.configure<LibraryExtension>("android") {
    namespace = "com.yanjiyu.terminalspike.mosh"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.pro")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
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

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
