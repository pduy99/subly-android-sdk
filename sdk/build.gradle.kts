plugins {
    alias(libs.plugins.android.library)
}

group = "com.helios.subly.sdk"
version = "0.0.1"

apply(from = rootProject.file("gradle/whisper-bootstrap.gradle.kts"))

val sublyHasWhisper: Boolean = (extra["sublyHasWhisper"] as? Boolean) ?: false

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

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // Auto-detected: ON once `:sdk:prepareWhisper` has cloned
                // whisper.cpp into src/main/cpp/whisper.cpp/. Otherwise the
                // stub library builds and the Kotlin layer self-degrades.
                arguments += "-DSUBLY_HAS_WHISPER=${if (sublyHasWhisper) "ON" else "OFF"}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
