plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.helios.subly.asr.api"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    api(projects.core.model)

    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}