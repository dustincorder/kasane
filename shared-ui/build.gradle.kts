plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

android {
    namespace = "cord.kasane.ui"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    androidTarget { compilations.all { kotlinOptions.jvmTarget = "17" } }
    jvm { compilations.all { kotlinOptions.jvmTarget = "17" } }
    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.materialIconsExtended)
            api("com.materialkolor:material-kolor:2.1.1")
            api("sh.calvin.reorderable:reorderable:2.5.1")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.desktop.uiTestJUnit4)
        }
    }
}
