plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.helios.subly.asr.whisper"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 34

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_USE_CPU=ON",
                    "-DGGML_NEON=ON",             
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // ggml-base-q5_1.bin is already compressed; skip APK recompression so
    // whisper.cpp can mmap it directly (when we switch to asset loading).
    androidResources {
        noCompress += "bin"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(projects.asr.api)
    // api (not implementation): ModelDownloader appears in WhisperTranscriber's
    // public constructor, so it must be visible to consumers of this module.
    api(projects.core.downloader)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
