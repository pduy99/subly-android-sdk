pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Subly-Android-SDK"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
include(":sdk")
include(":core:data")
include(":core:model")
include(":core:downloader")
include(":audio:api")
include(":audio:impl")
include(":asr:api")
include(":asr:sherpa")
include(":asr:whisper")
include(":translator:api")
include(":translator:mlkit")
include(":audio:api")
include(":audio:impl")
