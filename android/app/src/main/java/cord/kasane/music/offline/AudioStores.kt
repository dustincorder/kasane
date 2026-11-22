package cord.kasane.music.offline

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import cord.kasane.music.settings.AppSettings
import cord.kasane.music.settings.StorageLocation
import java.io.File

/**
 * Two physically separate Media3 caches.
 *  - [temp]: audio that was streamed. Bounded by the user's size limit, evicted least-recently-used.
 *  - [saved]: audio the user explicitly saved. No evictor: nothing here is ever deleted automatically.
 * Both are created lazily because SimpleCache scans its directory on construction.
 */
@OptIn(UnstableApi::class)
class AudioStores(context: Context, private val settings: AppSettings) {
    private val app = context.applicationContext
    private val database by lazy { StandaloneDatabaseProvider(app) }
    val evictor = ConfigurableLruEvictor { settings.cacheLimitMb.value * 1024L * 1024L }

    val tempDirectory: File = resolveTempDirectory(settings.cacheLocation.value)
    val savedDirectory: File = File(app.filesDir, "saved-audio")

    val temp: SimpleCache by lazy { SimpleCache(tempDirectory.apply { mkdirs() }, evictor, database) }
    val saved: SimpleCache by lazy { SimpleCache(savedDirectory.apply { mkdirs() }, NoOpCacheEvictor(), database) }

    private fun resolveTempDirectory(location: StorageLocation): File {
        val base = when (location) {
            StorageLocation.EXTERNAL -> app.getExternalFilesDir(null) ?: app.filesDir
            StorageLocation.INTERNAL -> app.cacheDir
        }
        return File(base, "audio-cache")
    }

    /** Whether the user's selected temp location is the one currently in use (a change needs a restart). */
    fun locationApplied(selected: StorageLocation): Boolean = tempDirectory.absolutePath == resolveTempDirectory(selected).absolutePath

    private fun tempLayer(upstream: DataSource.Factory?, writable: Boolean): CacheDataSource.Factory =
        CacheDataSource.Factory().setCache(temp).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).apply {
            if (upstream != null) setUpstreamDataSourceFactory(upstream)
            if (!writable) setCacheWriteDataSinkFactory(null)
        }

    /** Saved bytes first, then temporary cache, then network. Streamed bytes go to the temp cache only when enabled. */
    fun playbackFactory(network: DataSource.Factory): DataSource.Factory {
        val writable = tempLayer(network, writable = true)
        val readOnly = tempLayer(network, writable = false)
        val temporary = DataSource.Factory { if (settings.cacheEnabled.value) writable.createDataSource() else readOnly.createDataSource() }
        return CacheDataSource.Factory().setCache(saved).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(temporary)
    }

    /** Never touches the network; a missing byte range fails instead of silently streaming. */
    fun cacheOnlyFactory(): DataSource.Factory =
        CacheDataSource.Factory().setCache(saved).setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(tempLayer(null, writable = false))

    /** Writer for explicit saves: writes to [saved], reads already-streamed bytes from temp before using the network. */
    fun saveFactory(network: DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory().setCache(saved).setUpstreamDataSourceFactory(tempLayer(network, writable = false))
}
