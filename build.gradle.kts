// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    dependencies {
        // AGP's built-in Kotlin compiles with the Kotlin it bundles (2.2)
        // unless the Kotlin Gradle plugin is on the classpath. LiteRT-LM
        // 0.18 is built with Kotlin 2.4, whose metadata 2.2 cannot read.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}