package cord.kasane.desktop

import cord.kasane.desktop.audio.MpvEngine
import cord.kasane.desktop.audio.StreamProxy
import cord.kasane.desktop.library.LocalLibrary
import cord.kasane.desktop.playback.DesktopPlayer
import cord.kasane.music.provider.GuestPlayerResolver
import cord.kasane.music.provider.InnerTubeClient
import cord.kasane.music.provider.JvmCatalogCrypto
import cord.kasane.music.provider.OkHttpCatalogTransport
import cord.kasane.music.provider.SharedOnlineCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Where this prototype keeps its own data; separate from the Tauri client's `cord.kasane.desktop`. */
val dataDir: File = File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
    ?: (System.getProperty("user.home") + "/.local/share"), "cord.kasane.compose")

/** Process-wide services, mirroring what KasaneApplication wires up on Android. */
class DesktopApp(
    audioOutput: String? = null,
    private val root: File = dataDir,
    autoStartConnect: Boolean = true,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {
    val scope = scope
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    val catalog = SharedOnlineCatalog(InnerTubeClient(OkHttpCatalogTransport(http),
        nowSeconds = { System.currentTimeMillis() / 1000 }, sha1Hex = JvmCatalogCrypto::sha1Hex))
    val resolver = GuestPlayerResolver(http)
    val library = LocalLibrary(File(root, "library.json"))
    val settings = DesktopSettings(File(root, "settings.json"))
    val engine = MpvEngine(audioOutput)
    val mpvVersion: String = runCatching {
        ProcessBuilder("mpv", "--version").start().inputStream.bufferedReader().readLine()?.split(' ')?.getOrNull(1)
    }.getOrNull() ?: "unknown version"
    val proxy = StreamProxy(http, resolver)
    val player = DesktopPlayer(scope, engine, resolver, proxy, cord.kasane.music.provider.IosHlsResolver(http), catalog, library)
    /** Same Connect core as Android; null when it could not start (reported in Settings). */
    val connect: cord.kasane.desktop.connect.DesktopConnect?
    val connectError: String?

    init {
        val created = runCatching { cord.kasane.desktop.connect.DesktopConnect(player, scope, File(root, "connect"), "0.1.0-compose") }
        connect = created.getOrNull()
        connectError = created.exceptionOrNull()?.message
        if (autoStartConnect && settings.prefs.value.connectEnabled) connect?.start()
    }

    fun setConnectEnabled(enabled: Boolean) {
        settings.update { it.copy(connectEnabled = enabled) }
        if (enabled) connect?.start() else connect?.stop()
    }

    override fun close() {
        connect?.stop()
        engine.close()
        proxy.close()
        scope.cancel()
        http.dispatcher.executorService.shutdown()
    }
}
