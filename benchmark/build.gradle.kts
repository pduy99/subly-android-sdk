plugins {
    alias(libs.plugins.android.library)
}

// Measurement tooling only — never published, never shipped with the SDK.
// The runner lives in androidTest; src/main holds only the pure-Kotlin dataset/
// results models shared between the device runner and host-side unit tests.
android {
    namespace = "com.helios.subly.benchmark"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Route TestStorage writes into additional_test_output so Gradle pulls
        // them off the device (works unchanged on Gradle Managed Devices).
        testInstrumentationRunnerArguments += mapOf(
            "useTestStorageService" to "true",
            // Recorded into results.json so runs stay comparable after the fact.
            "benchmarkGitSha" to providers.exec {
                workingDir(layout.projectDirectory.asFile)
                commandLine("git", "rev-parse", "--short", "HEAD")
            }.standardOutput.asText.map { it.trim() }.getOrElse("unknown"),
        )

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(libs.gson)

    androidTestImplementation(projects.sdk)
    androidTestImplementation(projects.audio.api)
    androidTestImplementation(projects.asr.sherpa)
    // Sherpa's native runtime is compileOnly in :asr:sherpa (apps must ship
    // it themselves) — bundle it into the test APK the same way an app would.
    androidTestImplementation(files(rootProject.file("asr/sherpa/libs/sherpa-onnx-1.13.2.aar")))
    androidTestImplementation(projects.asr.whisper)
    androidTestImplementation(projects.asr.vosk)
    androidTestImplementation(projects.translator.mlkit)
    androidTestImplementation(projects.translator.litertlm)

    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.storage)
    androidTestUtil(libs.androidx.test.services)

    testImplementation(libs.junit)
}

// The corpus lives inside androidTest assets so it rides along in the test
// APK with no extra wiring (AGP 9's sourceSets DSL currently breaks on extra
// asset roots); host-side unit tests reach it through this property.
tasks.withType<Test>().configureEach {
    systemProperty(
        "benchmark.dataset.dir",
        layout.projectDirectory.dir("src/androidTest/assets/dataset").asFile.absolutePath,
    )
}

tasks.register("validateDataset") {
    group = "verification"
    description = "Checks benchmark/dataset/ and manifest.json for consistency."
    dependsOn("testDebugUnitTest")
}
