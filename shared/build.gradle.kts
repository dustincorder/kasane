plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "cord.kasane.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    androidTarget { compilations.all { kotlinOptions.jvmTarget = "17" } }
    jvm {
        compilations.all { kotlinOptions.jvmTarget = "17" }
    }
    // Android and desktop are both JVM: network, TLS and Connect code is written once in jvmCommonMain.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                withJvm()
                withAndroidTarget()
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val jvmCommonMain by getting {
            dependencies {
                api("com.squareup.okhttp3:okhttp:4.12.0")
                // Android provides org.json in the platform; desktop consumers add the artifact themselves.
                compileOnly("org.json:json:20240303")
            }
        }
        jvmTest.dependencies {
            implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
            implementation("org.json:json:20240303")
        }
    }
}
