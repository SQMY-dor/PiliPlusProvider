@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        google()
        gradlePluginPortal()
        maven { url = uri("https://api.xposed.info/") }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        google()
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "PiliPlusProvider"
include(":app")
