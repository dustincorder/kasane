package cord.kasane.music

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import cord.kasane.music.data.*
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.provider.OnlineCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger

/** Real guest network + Room timings, never personal collections or device-wide settings. */
class CatalogCacheInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish") && targetContext.packageName == "cord.kasane.music.dev")
        var activity: Activity? = null
        var phase = "foreground"
        try {
            activity = startActivitySync(android.content.Intent(targetContext, cord.kasane.music.playback.PlaybackProbeActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val requests = AtomicInteger()
            val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                check(chain.request().header("Cookie") == null && chain.request().header("Authorization") == null)
                requests.incrementAndGet()
                chain.proceed(chain.request())
            }.build()
            val remote = OnlineCatalog(client, { null })
            val app = targetContext.applicationContext as KasaneApplication
            fun repository() = CachedCatalogRepository(CatalogCache(RoomCatalogCacheStore(app.database.catalogCacheDao())), remote)
            val scope = "guest:cache-measurement"
            val query = "Sabrina Carpenter Espresso"
            val repo = repository()
            fun emit(message: String) { sendStatus(0, Bundle().apply { putString("stream", "$message\n") }) }
            runBlocking {
                phase = "home_refresh"
                var start = System.nanoTime()
                val home = withTimeout(60000) { repo.home(scope, force = true) }
                check(home != null && home.shelves.any { it.items.isNotEmpty() })
                emit("CACHE home refreshMs=${(System.nanoTime()-start)/1_000_000} httpRequests=${requests.get()}")
                phase = "search_refresh"
                val beforeSearch = requests.get(); start = System.nanoTime()
                val search = withTimeout(60000) { repo.search(scope, query, CatalogKind.SONG, force = true) }
                check(search.shelves.any { it.items.isNotEmpty() })
                emit("CACHE search refreshMs=${(System.nanoTime()-start)/1_000_000} httpRequests=${requests.get()-beforeSearch}")
                phase = "memory_reads"
                val beforeCached = requests.get()
                val homeTimes = mutableListOf<Long>(); val searchTimes = mutableListOf<Long>()
                repeat(20) {
                    start = System.nanoTime(); check(repo.home(scope) == home); homeTimes += (System.nanoTime()-start)/1000
                    start = System.nanoTime(); check(repo.search(scope, query, CatalogKind.SONG) == search); searchTimes += (System.nanoTime()-start)/1000
                }
                check(requests.get() == beforeCached)
                emit("CACHE memory homeMedianUs=${homeTimes.sorted()[10]} searchMedianUs=${searchTimes.sorted()[10]} reads=40 httpRequests=0")
                phase = "room_reads"
                val restored = repository(); start = System.nanoTime()
                check(restored.home(scope) == home)
                val roomHomeMs = (System.nanoTime()-start)/1_000_000; start = System.nanoTime()
                check(restored.search(scope, query, CatalogKind.SONG) == search)
                val roomSearchMs = (System.nanoTime()-start)/1_000_000
                check(requests.get() == beforeCached)
                emit("CACHE room homeMs=$roomHomeMs searchMs=$roomSearchMs httpRequests=0")
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "CATALOG CACHE PASSED: real guest refresh, memory reuse and fresh repository Room reads; UI/image timings not measured\n") })
        } catch (error: Throwable) {
            // Network exception messages may contain URLs; only fixed phase/type are safe.
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "CATALOG CACHE FAILED: phase=$phase type=${error.javaClass.simpleName}\n") })
        } finally { runOnMainSync { activity?.finish() } }
    }
}
