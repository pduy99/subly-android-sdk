plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.helios.subly.asr.sherpa"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // arm64-v8a only: minSdk 34 devices are universally 64-bit ARM
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    aaptOptions {
        noCompress.addAll(listOf("ort", "txt"))
    }
}

dependencies {
    compileOnly(fileTree("libs") { include("*.aar") })

    api(projects.asr.api)
    // api (not implementation): ModelDownloader and SpeechGate both appear in
    // SherpaOnnxTranscriber's public constructor, so they must be visible to
    // consumers of this module.
    api(projects.core.downloader)
    api(projects.asr.vad)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}