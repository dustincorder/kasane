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
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
