plugins {
    alias(libs.plugins.android.library)
}

group = "com.helios.subly.sdk"
version = "0.0.1"

android {
    namespace = "com.helios.subly.sdk"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 34
        consumerProguardFiles("consumer-rules.pro")

        // arm64-v8a only: minSdk 34 devices are universally 64-bit ARM
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    aaptOptions {
        noCompress.addAll(listOf("ort", "txt"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // sherpa-onnx is `compileOnly` because AGP refuses to bundle a local
    // .aar inside another .aar (this module is `com.android.library`, so
    // its build output is itself an AAR). The Consumer app re-declares the
    // same .aar as `implementation(files(...))` so the final APK actually
    // packages sherpa-onnx classes + `libsherpa-onnx-jni.so`. Without that
    // app-side declaration, [SherpaOnnxBackend.Jni.isAvailable] returns
    // false at runtime and [SherpaOnnxTranscriber] drains.
    compileOnly(fileTree("libs") { include("*.aar") })

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)
    implementation(libs.mlkit.text.recognition)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
