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
class DesktopApp(audioOutput: String? = null, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) : AutoCloseable {
    val scope = scope
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    val catalog = SharedOnlineCatalog(InnerTubeClient(OkHttpCatalogTransport(http),
        nowSeconds = { System.currentTimeMillis() / 1000 }, sha1Hex = JvmCatalogCrypto::sha1Hex))
    val resolver = GuestPlayerResolver(http)
    val library = LocalLibrary(File(dataDir, "library.json"))
    val engine = MpvEngine(audioOutput)
    val proxy = StreamProxy(http, resolver)
    val player = DesktopPlayer(scope, engine, resolver, proxy, catalog, library)

    override fun close() {
        engine.close()
        proxy.close()
        scope.cancel()
        http.dispatcher.executorService.shutdown()
    }
}
