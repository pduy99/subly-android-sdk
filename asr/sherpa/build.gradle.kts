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

    implementation(projects.asr.api)
    implementation(projects.core.model)

    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.litert.lm)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.okhttp)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}