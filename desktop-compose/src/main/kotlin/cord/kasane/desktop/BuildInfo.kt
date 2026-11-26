package cord.kasane.desktop

import cord.kasane.music.ota.UpdateChannel
import java.util.Properties

/**
 * Build-variant identity, baked into `kasane-build.properties` by the `generateBuildInfo`
 * Gradle task (channel via `-PkasaneChannel=stable|nightly`, default stable). Nightly is a
 * fully parallel install: own XDG data dir, own secret-store schema, own app name — no
 * shared state with stable. Properties the release pipeline must honour:
 * install dir `~/.local/opt/$installDirName`, desktop entry `cord.kasane.desktop$nightlySuffix`.
 */
object BuildInfo {
    private val props = Properties().apply {
        // javaClass here would be Properties.class (bootstrap loader, no app resources) —
        // the lookup must go through BuildInfo's own class loader.
        runCatching { BuildInfo::class.java.getResourceAsStream("/kasane-build.properties")?.use(::load) }
    }

    val channel: String = props.getProperty("channel", "stable")
    val version: String = props.getProperty("version", "dev")
    val isNightly: Boolean = channel == "nightly"
    val updateChannel: UpdateChannel = if (isNightly) UpdateChannel.NIGHTLY else UpdateChannel.STABLE

    val appName: String = if (isNightly) "Kasane Nightly" else "Kasane"
    /** `$XDG_DATA_HOME/<dataDirName>` — settings, library, caches, downloads. */
    val dataDirName: String = if (isNightly) "cord.kasane.compose.nightly" else "cord.kasane.compose"
    /** Secret Service schema so nightly keeps its own session item, not the stable one. */
    val secretSchema: String = "cord.kasane.compose${if (isNightly) ".nightly" else ""}.session"
    /** `~/.local/opt/<installDirName>` and the tarball/package base name. */
    val installDirName: String = if (isNightly) "kasane-nightly" else "kasane-compose"
    /** Suffix for the .desktop file: `cord.kasane.desktop.nightly.desktop` on nightly. */
    val nightlySuffix: String = if (isNightly) ".nightly" else ""

    /** Update asset matchers for this platform/channel (Linux x86_64 tarball);
     *  stable never takes a `nightly`-named asset. */
    val assetMatchers: List<String> =
        if (isNightly) listOf("linux", "x86_64", ".tar.gz")
        else listOf("linux", "x86_64", ".tar.gz", "-nightly")
}
