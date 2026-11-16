pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor (stream extraction) is published on JitPack only.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

rootProject.name = "AuraWave"
include(":app", ":innertube")
