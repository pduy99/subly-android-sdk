plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.helios.subly.asr.api"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 34
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