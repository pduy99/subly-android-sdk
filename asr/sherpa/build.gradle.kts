plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.helios.subly.asr.sherpa"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 34

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
    // api (not implementation): ModelDownloader appears in SherpaOnnxTranscriber's
    // public constructor, so it must be visible to consumers of this module.
    api(projects.core.downloader)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}