plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.helios.subly.asr.vad"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // arm64-v8a only, to match the rest of the SDK.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            // SpeechGate logs on every degradation path, and those paths are
            // precisely what the JVM tests exercise. Without this, android.util.Log
            // throws "not mocked" and the fail-open tests fail for a reason that
            // has nothing to do with the gate.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // The Silero VAD runs on the sherpa-onnx runtime, which is a local AAR the
    // app has to ship itself (AGP won't let a library module declare a local
    // AAR as `implementation`). Same arrangement as :asr:sherpa — and the same
    // consequence: an app that ships no sherpa AAR still links, and
    // SpeechGate degrades to pass-through instead of crashing.
    compileOnly(fileTree(rootProject.file("asr/sherpa/libs")) { include("*.aar") })

    // api (not implementation): ModelDownloader appears in SpeechGate's public
    // constructor, so it must be visible to consumers of this module.
    api(projects.core.downloader)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
