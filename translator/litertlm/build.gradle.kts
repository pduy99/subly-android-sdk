plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.helios.subly.translator.litertlm"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // arm64-v8a only, to match the rest of the SDK. litertlm-android also
        // ships x86_64, which no SDK target device uses.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(projects.translator.api)
    // api (not implementation): ModelDownloader appears in
    // LiteRtLmTranslator's public constructor.
    api(projects.core.downloader)

    implementation(libs.litert.lm)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
