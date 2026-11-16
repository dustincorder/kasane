plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // Both current consumers run on JVM. Keep portable code in commonMain;
    // platform UI/audio/storage adapters stay in the app modules.
    jvm {
        compilations.all { kotlinOptions.jvmTarget = "17" }
    }
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmMain.dependencies { api("com.squareup.okhttp3:okhttp:4.12.0") }
        jvmTest.dependencies { implementation("com.squareup.okhttp3:mockwebserver:4.12.0") }
    }
}
