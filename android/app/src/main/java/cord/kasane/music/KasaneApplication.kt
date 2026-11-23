package cord.kasane.music

import android.app.Application
import kotlinx.coroutines.launch
import androidx.room.Room
import cord.kasane.music.data.KasaneDatabase
import cord.kasane.music.data.CatalogCache
import cord.kasane.music.data.RoomCatalogCacheStore
import cord.kasane.music.data.CachedCatalogRepository
import cord.kasane.music.playback.PlaybackManager
import cord.kasane.music.provider.YouTubeMusicProvider

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class KasaneApplication : Application() {

    lateinit var database: KasaneDatabase
        private set

    lateinit var settings: cord.kasane.music.settings.AppSettings
        private set

    lateinit var offline: cord.kasane.music.offline.OfflineManager
        private set

    lateinit var searchHistory: cord.kasane.music.settings.SearchHistory
        private set

    private val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    lateinit var playbackManager: PlaybackManager
        private set

    @Volatile
    var connect: cord.kasane.music.connect.ConnectService? = null
        private set

    val remote = kotlinx.coroutines.flow.MutableStateFlow<cord.kasane.music.connect.RemoteController?>(null)

    lateinit var ytProvider: YouTubeMusicProvider
        private set

    lateinit var accountRepository: cord.kasane.music.auth.AccountRepository
        private set

    lateinit var accountLibraryProvider: cord.kasane.music.provider.AccountLibraryProvider
        private set

    lateinit var catalogCache: CatalogCache
        private set

    lateinit var cachedCatalog: CachedCatalogRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = cord.kasane.music.settings.AppSettings(this)
        searchHistory = cord.kasane.music.settings.SearchHistory(this)

        renameLegacyDatabase()
        database = Room.databaseBuilder(
            applicationContext,
            KasaneDatabase::class.java,
            DATABASE_NAME
        ).addMigrations(KasaneDatabase.MIGRATION_1_2, KasaneDatabase.MIGRATION_2_3, KasaneDatabase.MIGRATION_3_4, KasaneDatabase.MIGRATION_4_5, KasaneDatabase.MIGRATION_5_6, KasaneDatabase.MIGRATION_6_7).build()

        val httpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(cord.kasane.music.offline.OfflineGateInterceptor { settings.offlineOnly.value })
            .build()
        ytProvider = YouTubeMusicProvider(
            client = httpClient,
            playerDiagnostic = { if (BuildConfig.DEBUG) android.util.Log.d("KasanePlayback", it) },
            accountSession = { accountRepository.session() },
            verificationRuntime = cord.kasane.music.provider.verification.WebViewVerificationRuntime(applicationContext),
        )
        accountLibraryProvider = ytProvider.catalog
        catalogCache = CatalogCache(RoomCatalogCacheStore(database.catalogCacheDao()))
        cachedCatalog = CachedCatalogRepository(catalogCache, ytProvider.catalog)
        accountRepository = cord.kasane.music.auth.AccountRepository(
            cord.kasane.music.auth.KeystoreSessionStore(this), ytProvider.catalog::identity)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            accountRepository.restore()
        }

        offline = cord.kasane.music.offline.OfflineManager(
            context = this,
            dao = database.audioDao(),
            trackDao = database.trackDao(),
            stores = cord.kasane.music.offline.AudioStores(this, settings),
            settings = settings,
            resolve = ytProvider.hlsResolver::resolve,
            network = androidx.media3.datasource.DefaultHttpDataSource.Factory().setConnectTimeoutMs(15000).setReadTimeoutMs(15000),
            http = httpClient,
            scope = applicationScope,
        )

        playbackManager = PlaybackManager(
            context = applicationContext,
            trackDao = database.trackDao(),
            ytProvider = ytProvider
        )

        applicationScope.launch {
            settings.connectEnabled.collect { enabled ->
                if (enabled && connect == null) {
                    connect = cord.kasane.music.connect.AndroidConnect
                        .createService(applicationContext, playbackManager, BuildConfig.VERSION_NAME, applicationScope)
                        .also {
                            it.start()
                            remote.value = cord.kasane.music.connect.RemoteController(
                                it.manager, playbackManager, applicationScope)
                        }
                } else if (!enabled) {
                    connect?.stop()
                    connect = null
                    remote.value = null
                }
            }
        }
    }

    /** Earlier builds named the database after the old project name; keep the user's data. */
    private fun renameLegacyDatabase() {
        val legacy = getDatabasePath(LEGACY_DATABASE_NAME)
        if (!legacy.exists() || getDatabasePath(DATABASE_NAME).exists()) return
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val from = getDatabasePath(LEGACY_DATABASE_NAME + suffix)
            if (from.exists()) from.renameTo(getDatabasePath(DATABASE_NAME + suffix))
        }
    }

    private companion object {
        const val DATABASE_NAME = "kasane.db"
        const val LEGACY_DATABASE_NAME = "kanade.db"
    }
}
