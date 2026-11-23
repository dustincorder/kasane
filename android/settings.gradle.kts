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

// Shared Compose Multiplatform UI (theme, components, screens) used by Android and desktop.
include(":ui")
project(":ui").projectDir = file("../shared-ui")

// Isolated experiment: system-WebView playback verification for the desktop (not part of any app).
include(":poc-webview")
project(":poc-webview").projectDir = file("../poc/webview-verify")
