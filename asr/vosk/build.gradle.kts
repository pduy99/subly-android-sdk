plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.helios.subly.asr.vosk"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // arm64-v8a only, to match the rest of the SDK. vosk-android ships
        // libvosk.so + JNA's libjnidispatch.so for this ABI inside the AAR.
        ndk {
            abiFilters += "arm64-v8a"
        }

        // Vosk talks to libvosk.so through JNA, which resolves its bindings
        // reflectively. R8 (esp. full mode) would otherwise strip the JNA
        // Structure subclasses and org.vosk types. Ship keep rules to every
        // consumer of this module so the recognizer doesn't NPE at runtime.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(projects.asr.api)
    // api (not implementation): ModelDownloader and SpeechGate both appear in
    // VoskTranscriber's public constructor, so they must be visible to
    // consumers of this module.
    api(projects.core.downloader)
    // The gate's sherpa-onnx runtime is compileOnly inside :asr:vad, so this
    // adds no native dependency here — a vosk-only app links and runs without
    // the AAR, and simply gets no gating.
    api(projects.asr.vad)

    // Vosk (Kaldi) JNI + bundled native libs. Unlike the sherpa module this is
    // a normal Maven dependency, so the .so ships transitively — consumers need
    // no extra app-side AAR wiring.
    implementation(libs.vosk.android)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
