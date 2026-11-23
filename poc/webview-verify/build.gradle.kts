plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}


dependencies {
    implementation(project(":shared"))
    implementation(project(":desktop")) // MpvEngine + StreamProxy, unchanged
    implementation("org.json:json:20240303")
    // JavaFX WebView fallback (bundles its own WebKit build); measured, not proposed by default.
    for (m in listOf("base", "graphics", "controls", "media", "web")) implementation("org.openjfx:javafx-$m:21.0.5:linux")
}

application { mainClass.set("cord.kasane.poc.MainKt") }
