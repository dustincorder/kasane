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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kasane"
include(":app")

include(":shared")
project(":shared").projectDir = file("../shared")

// Experimental Compose Multiplatform desktop client (Linux JVM); the Tauri client is unaffected.
include(":desktop")
project(":desktop").projectDir = file("../desktop-compose")
