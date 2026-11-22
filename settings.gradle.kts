// Maven Central enforces shared-runner rate limits (HTTP 429 since 2026).
// Google's public HTTPS GCS mirror serves the same Maven Central artifacts.
// Retain canonical Maven Central as fallback if the mirror is unavailable.
// Source: https://docs.cloud.google.com/docs/buildpacks/java
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

pluginManagement {
    repositories {
        google()
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}

rootProject.name = "N-LIC"
include(":app")
