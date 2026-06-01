plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.helios.subly.translator.api"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    api(projects.core.model)

    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}