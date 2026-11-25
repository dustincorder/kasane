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
import kotlinx.coroutines.launch
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
    /** Offline Only gate: every OkHttp request is refused while the setting is on (Android parity). */
    private val offlineOnly = java.util.concurrent.atomic.AtomicBoolean(false)
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            if (offlineOnly.get()) throw cord.kasane.desktop.offline.OfflineModeException() else chain.proceed(chain.request())
        }.build()
    private val innerTube = InnerTubeClient(OkHttpCatalogTransport(http),
        nowSeconds = { System.currentTimeMillis() / 1000 }, sha1Hex = JvmCatalogCrypto::sha1Hex)
    /** Secret Service when reachable, else a memory-only session the UI marks as such. */
    private val sessionStore = runCatching {
        cord.kasane.desktop.auth.SecretServiceStore().takeIf { it.probe() != cord.kasane.desktop.auth.SecretServiceStore.Availability.UNAVAILABLE }
    }.getOrNull()
    val settings = DesktopSettings(File(root, "settings.json"))
    val account = cord.kasane.desktop.auth.DesktopAccount(scope, sessionStore,
        validate = { session -> catalog.identity(session) },
        modeStore = settings.sessionModeStore)
    val catalog: SharedOnlineCatalog = SharedOnlineCatalog(innerTube) { account.session() }
    val resolver = GuestPlayerResolver(http)
    val library = LocalLibrary(File(root, "library.json"))
    val likes = cord.kasane.desktop.library.DesktopLikes(scope, { account.session() },
        read = catalog::likeState, write = catalog::setLikedFor, writeDislike = catalog::setDislikedFor)
    val likedSongs = cord.kasane.desktop.library.LikedSongs(scope, likes,
        loadPage = { continuation -> catalog.playlist("LM", continuation) }, enabled = { account.session() != null })
    val engine = MpvEngine(audioOutput)
    val mpvVersion: String = runCatching {
        ProcessBuilder("mpv", "--version").start().inputStream.bufferedReader().readLine()?.split(' ')?.getOrNull(1)
    }.getOrNull() ?: "unknown version"
    val proxy = StreamProxy(http, resolver)
    /** System WebKitGTK in a helper process, started on the first verified track and stopped when idle. */
    val verifier = cord.kasane.desktop.verify.VerifierProcess(scope,
        System.getenv("KASANE_VERIFIER_IDLE_MS")?.toLongOrNull() ?: 180_000)
    private val selector = cord.kasane.desktop.playback.StreamSelector(proxy, resolver, cord.kasane.music.provider.IosHlsResolver(http),
        cord.kasane.music.provider.verification.VerifiedPlayerResolver(http, verifier,
            accountHeaders = { account.signedInHeaders() }))
    /** Shared lyrics resolver over JSON-file storage; AMLL TTML → LRCLIB → YouTube Music. */
    val lyrics = cord.kasane.desktop.lyrics.DesktopLyrics(http, catalog, { account.session() },
        cord.kasane.desktop.lyrics.FileLyricsStore(File(root, "lyrics.json")))
    /** Shared MusicBrainz/CAA resolver over JSON-file storage; gated by the enrich setting. */
    val metadata = cord.kasane.desktop.metadata.DesktopMetadata(http,
        cord.kasane.desktop.metadata.FileMetadataStore(File(root, "metadata.json")),
        enabled = { settings.prefs.value.enrichMetadata })
    /** Saved music, the stream cache and the download queue; playback consults it before the network. */
    val offline: cord.kasane.desktop.offline.DesktopOffline = cord.kasane.desktop.offline.DesktopOffline(root, http, scope, settings, selector::resolveForSave,
        onSaved = { track ->
            // Android's OfflineManager order: metadata → lyrics prefetch → official cover repoints art.
            val meta = if (settings.prefs.value.enrichMetadata)
                runCatching { metadata.metadataFor(track) }.getOrNull() else null
            runCatching { lyrics.lyricsFor(track) }
            meta?.coverArtUrl?.let { offline.saveArtworkFrom(track.id, it) }
        })
    val streams: cord.kasane.desktop.playback.StreamSource = cord.kasane.desktop.playback.OfflineStreamSource(selector, offline)
    val player = DesktopPlayer(scope, engine, streams, cord.kasane.desktop.playback.RadioSource(catalog::radioPage), library,
        onPlayed = offline::played,
        onCountedPlay = { _, stream, positionSec, durationSec ->
            // Counted plays report back to YouTube's watch endpoints so the signed-in history feed
            // picks them up — the same ping the web client sends, fire-and-forget.
            val urls = listOfNotNull(stream?.playbackStatsUrl, stream?.watchtimeUrl)
                .mapNotNull { cord.kasane.music.playback.WatchReport.url(it, positionSec, durationSec, fmt = stream?.itag ?: 0) }
            if (urls.isNotEmpty()) scope.launch(Dispatchers.IO) {
                urls.forEach { url -> runCatching {
                    http.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().close() } }
            }
        })
    /** Same Connect core as Android; null when it could not start (reported in Settings). */
    val connect: cord.kasane.desktop.connect.DesktopConnect?
    val connectError: String?

    init {
        val created = runCatching { cord.kasane.desktop.connect.DesktopConnect(player, scope, File(root, "connect"), "0.1.0-compose") }
        connect = created.getOrNull()
        connectError = created.exceptionOrNull()?.message
        if (autoStartConnect && settings.prefs.value.connectEnabled) connect?.start()
        proxy.streamSink = offline::onStreamBytes
        scope.launch { settings.prefs.collect { offlineOnly.set(it.offlineOnly) } }
        // History is scoped per account; likes live only while a session exists.
        scope.launch {
            account.state.collect { state ->
                when (state) {
                    is cord.kasane.music.auth.AccountState.SignedIn -> {
                        library.setAccount(state.identity.channelId ?: state.identity.name)
                        likedSongs.refresh()
                    }
                    is cord.kasane.music.auth.AccountState.Guest,
                    is cord.kasane.music.auth.AccountState.Expired -> {
                        library.setAccount(null)
                        likedSongs.clear()
                        likes.clear()
                    }
                    else -> Unit
                }
            }
        }
        scope.launch { account.restore() }
    }

    fun setConnectEnabled(enabled: Boolean) {
        settings.update { it.copy(connectEnabled = enabled) }
        if (enabled) connect?.start() else connect?.stop()
    }

    override fun close() {
        connect?.stop()
        verifier.close()
        likes.close()
        engine.close()
        proxy.close()
        scope.cancel()
        http.dispatcher.executorService.shutdown()
    }
}
