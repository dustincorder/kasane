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
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

val packagingJdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    .get().metadata.installationPath.asFile

compose.desktop {
    application {
        mainClass = "cord.kasane.desktop.MainKt"
        // JDK 21 is still affected by the launcher's exit-time crash on current glibc.
        javaHome = packagingJdk.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "kasane-compose"
            packageVersion = "0.2.0-alpha"
        }
    }
}

// The jpackage launcher crashes on this platform: a forked child aborts in Logger teardown at
// startup and the main process does the same at exit, dumping core on every launch (KDE shows a
// crash notification per spawned helper). Ship the JDK's own `java` binary inside the runtime so
// helpers and launch scripts can run the JVM directly and skip the buggy launcher entirely.
tasks.matching { it.name == "createDistributable" }.configureEach {
    doLast {
        val appDir = layout.buildDirectory.dir("compose/binaries/main/app/kasane-compose").get().asFile
        val javaBin = File(appDir, "lib/runtime/bin/java")
        javaBin.parentFile.mkdirs()
        File(packagingJdk, "bin/java").copyTo(javaBin, overwrite = true)
        javaBin.setExecutable(true)
        // Desktop entry points use this script instead of the crashing ELF launcher.
        val script = File(appDir, "bin/kasane-compose-java")
        script.writeText(
            """
            |#!/bin/sh
            |APP_ROOT="${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")/.." && pwd)"
            |exec "${'$'}APP_ROOT/lib/runtime/bin/java" \
            |    -Dcompose.application.resources.dir="${'$'}APP_ROOT/lib/app/resources" \
            |    -Dcompose.application.configure.swing.globals=true \
            |    -Dskiko.library.path="${'$'}APP_ROOT/lib/app" \
            |    -cp "${'$'}APP_ROOT/lib/app/*" \
            |    cord.kasane.desktop.MainKt "${'$'}@"
            |""".trimMargin()
        )
        script.setExecutable(true)
    }
}
