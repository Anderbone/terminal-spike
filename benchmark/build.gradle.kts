plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

val useConnectedBenchmarkDevices = providers
    .gradleProperty("terminalSpikeUseConnectedBenchmarkDevices")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: false

android {
    namespace = "com.yanjiyu.terminalspike.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    testOptions.managedDevices.localDevices {
        create("pixel6Api35") {
            device = "Pixel 6"
            apiLevel = 35
            systemImageSource = "aosp"
        }
    }

    sourceSets.getByName("main").assets.srcDir(
        rootProject.file("app/src/test/resources/terminal-benchmark-fixtures"),
    )
}

kotlin {
    jvmToolchain(17)
}

baselineProfile {
    if (!useConnectedBenchmarkDevices) managedDevices += "pixel6Api35"
    useConnectedDevices = useConnectedBenchmarkDevices
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
