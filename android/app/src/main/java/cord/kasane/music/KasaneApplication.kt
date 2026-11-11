package cord.kasane.music

import android.app.Application
import kotlinx.coroutines.launch
import androidx.room.Room
import cord.kasane.music.data.KasaneDatabase
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

    override fun onCreate() {
        super.onCreate()

        database = Room.databaseBuilder(
            applicationContext,
            KasaneDatabase::class.java,
            "kanade.db"
        ).fallbackToDestructiveMigration().build()

        ytProvider = YouTubeMusicProvider(playerDiagnostic = { if (BuildConfig.DEBUG) android.util.Log.d("KasanePlayback", it) })
        accountRepository = cord.kasane.music.auth.AccountRepository(
            cord.kasane.music.auth.KeystoreSessionStore(this), ytProvider.catalog::identity)
        ytProvider.accountSession = accountRepository::session
        ytProvider.catalog.onSessionExpired = {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch { accountRepository.expire() }
        }
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
