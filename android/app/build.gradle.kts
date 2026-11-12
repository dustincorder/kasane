plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "cord.kasane.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "cord.kasane.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1-alpha"
        testInstrumentationRunner = if (providers.gradleProperty("kasaneHlsProbe").orNull == "true")
            "cord.kasane.music.HlsPlaybackInstrumentation"
        else if (providers.gradleProperty("kasanePlaybackProbe").orNull == "true")
            "cord.kasane.music.GuestPlaybackInstrumentation"
        else "androidx.test.runner.AndroidJUnitRunner"
    }

    // Instrumentation is debug-only and isolated from the user's release package/data.
    testBuildType = "debug"

    testOptions {
        unitTests.isReturnDefaultValues = true
        managedDevices {
            devices {
                create<com.android.build.api.dsl.ManagedVirtualDevice>("kasaneApi35") {
                    device = "Pixel 6"
                    apiLevel = 35
                    systemImageSource = "aosp"
                }
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            val privateDir = file(System.getProperty("user.home") + "/.glazegram")
            val envFile = listOf(
                file(privateDir.absolutePath + "/.env"),
                file(privateDir.absolutePath + "/secrets.env"),
            ).firstOrNull { it.isFile }
            val values = if (envFile != null) {
                envFile.readLines()
                    .mapNotNull { line -> line.substringBefore('#').trim().takeIf { it.contains('=') } }
                    .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim().trim('"', '\'') }
            } else {
                emptyMap()
            }
            val storeFilePath = values["RELEASE_KEYSTORE"] ?: values["KEYSTORE_PATH"] ?:
                file(privateDir.absolutePath + "/release.keystore").takeIf { it.isFile }?.absolutePath
            if (!storeFilePath.isNullOrBlank() && values["RELEASE_KEY_ALIAS"] != null &&
                values["RELEASE_STORE_PASSWORD"] != null && values["RELEASE_KEY_PASSWORD"] != null
            ) {
                storeFile = file(storeFilePath)
                keyAlias = values["RELEASE_KEY_ALIAS"]
                storePassword = values["RELEASE_STORE_PASSWORD"]
                keyPassword = values["RELEASE_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".dev"
            isDebuggable = true
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

// Gradle's connected-test runner installs its target and test APKs and may
// uninstall both during cleanup, even when setup fails. Never use it here:
// instrumentation runs on the disposable managed emulator below instead.
gradle.taskGraph.whenReady {
    if (allTasks.any { task ->
            task.name.startsWith("connected") &&
                (task.name.endsWith("AndroidTest") || task.name == "connectedCheck")
        }) {
        throw GradleException("Connected Android instrumentation is blocked for Kasane. Use kasaneApi35DebugAndroidTest on the disposable managed emulator.")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Media3
    val media3Version = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Network & Serialization
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
