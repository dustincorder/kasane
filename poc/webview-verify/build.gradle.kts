plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

// The production resolver, compiled unchanged from the Android sources.
sourceSets.main {
    kotlin.srcDir(rootProject.file("app/src/main/java"))
    kotlin.include("cord/kasane/poc/**", "cord/kasane/music/provider/verification/VerifiedPlayerResolver.kt")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":desktop")) // MpvEngine + StreamProxy, unchanged
    implementation("org.json:json:20240303")
    implementation("net.java.dev.jna:jna:5.14.0")
    // JavaFX WebView fallback (bundles its own WebKit build); measured, not proposed by default.
    for (m in listOf("base", "graphics", "controls", "media", "web")) implementation("org.openjfx:javafx-$m:21.0.5:linux")
}

application { mainClass.set("cord.kasane.poc.MainKt") }
