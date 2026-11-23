import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

// The same verification bundle Android ships (built by `npm run build:verifier` at the repo root).
sourceSets.main { resources.srcDir(rootProject.file("../verifier/dist")) }

dependencies {
    implementation(project(":shared"))
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")
    implementation("com.materialkolor:material-kolor:2.1.1")
    implementation("org.jmdns:jmdns:3.5.9")
    // System WebKitGTK for playback verification, called directly (no bundled browser).
    implementation("net.java.dev.jna:jna:5.14.0")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "cord.kasane.desktop.MainKt"
        // JDK 17's jpackage launcher prints "pure virtual method called" on current glibc; 21's does not.
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.get()
            .metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "kasane-compose"
            packageVersion = "0.1.0"
        }
    }
}
