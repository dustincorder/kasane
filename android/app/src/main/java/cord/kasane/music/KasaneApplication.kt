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

class KasaneApplication : Application() {

    lateinit var database: KasaneDatabase
        private set

    lateinit var playbackManager: PlaybackManager
        private set

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

        database = Room.databaseBuilder(
            applicationContext,
            KasaneDatabase::class.java,
            "kanade.db"
        ).addMigrations(KasaneDatabase.MIGRATION_1_2, KasaneDatabase.MIGRATION_2_3).build()

        ytProvider = YouTubeMusicProvider(
            playerDiagnostic = { if (BuildConfig.DEBUG) android.util.Log.d("KasanePlayback", it) },
            accountSession = { accountRepository.session() },
        )
        accountLibraryProvider = ytProvider.catalog
        catalogCache = CatalogCache(RoomCatalogCacheStore(database.catalogCacheDao()))
        cachedCatalog = CachedCatalogRepository(catalogCache, ytProvider.catalog)
        accountRepository = cord.kasane.music.auth.AccountRepository(
            cord.kasane.music.auth.KeystoreSessionStore(this), ytProvider.catalog::identity)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            accountRepository.restore()
        }

        playbackManager = PlaybackManager(
            context = applicationContext,
            trackDao = database.trackDao(),
            ytProvider = ytProvider
        )
    }
}
