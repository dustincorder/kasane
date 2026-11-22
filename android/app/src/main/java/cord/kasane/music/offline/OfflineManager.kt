package cord.kasane.music.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import cord.kasane.music.data.TrackDao
import cord.kasane.music.model.Track
import cord.kasane.music.provider.PlaybackStream
import cord.kasane.music.provider.ProgressiveStream
import cord.kasane.music.provider.ResolvedAudioStream
import cord.kasane.music.provider.playbackFailure
import cord.kasane.music.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DownloadProgress(val bytes: Long, val total: Long) {
    val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
}

data class StorageUsage(val savedBytes: Long = 0, val tempBytes: Long = 0, val tempLimitBytes: Long = 0, val savedCount: Int = 0,
    /** Streamed songs whose whole file is in the temporary cache: they replay offline but are not "saved". */
    val replayableCount: Int = 0)

data class SaveSummary(val queued: Int, val alreadySaved: Int, val rejected: Int, val blockedBy: String? = null)

/**
 * Owns the offline library: temporary-cache bookkeeping, explicit saves, and the single download queue.
 * Downloads run one at a time, survive process death (interrupted rows are re-queued and resume from the
 * bytes already written), and never log or persist signed stream URLs.
 */
@OptIn(UnstableApi::class)
class OfflineManager(
    context: Context,
    private val dao: AudioFileDao,
    private val trackDao: TrackDao,
    private val stores: AudioStores,
    private val settings: AppSettings,
    private val resolve: suspend (String) -> PlaybackStream,
    private val network: DataSource.Factory,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) : OfflinePlayback {
    private val app = context.applicationContext
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val mutableProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    private val mutableUsage = MutableStateFlow(StorageUsage())
    @Volatile private var activeId: String? = null
    @Volatile private var activeJob: Job? = null

    val progress: StateFlow<Map<String, DownloadProgress>> = mutableProgress.asStateFlow()
    val usage: StateFlow<StorageUsage> = mutableUsage.asStateFlow()
    val pinned: Flow<List<AudioFileRecord>> get() = dao.observePinned()
    val all: Flow<List<AudioFileRecord>> get() = dao.observeAll()

    override val offlineOnly: Boolean get() = settings.offlineOnly.value

    init {
        scope.launch(Dispatchers.IO) { workerLoop() }
        scope.launch { combine(settings.offlineOnly, settings.wifiOnlyDownloads) { a, b -> a to b }.drop(1).collect { trigger.trySend(Unit) } }
        scope.launch { settings.cacheLimitMb.drop(1).collect { withContext(Dispatchers.IO) { stores.evictor.trim(stores.temp) }; refreshUsage() } }
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { trigger.trySend(Unit) }
            })
        }
    }

    // ---- Playback integration -------------------------------------------------------------------------

    override suspend fun local(videoId: String): LocalAudio? = withContext(Dispatchers.IO) {
        val row = dao.get(videoId) ?: return@withContext null
        if (row.cacheKey.isEmpty() || row.contentLength <= 0) return@withContext null
        val inSaved = stores.saved.getCachedBytes(row.cacheKey, 0, row.contentLength)
        val inTemp = if (inSaved >= row.contentLength) 0 else stores.temp.getCachedBytes(row.cacheKey, 0, row.contentLength)
        if (!OfflinePolicy.playableFromCache(row, maxOf(inSaved, inTemp))) return@withContext null
        dao.touch(videoId, System.currentTimeMillis())
        LocalAudio(row.cacheKey, row.mimeType)
    }

    override fun resolved(videoId: String, audio: ResolvedAudioStream) {
        if (!settings.cacheEnabled.value) return
        scope.launch(Dispatchers.IO) {
            val old = dao.get(videoId)
            if (old?.pinned == true) return@launch
            val now = System.currentTimeMillis()
            dao.upsert(AudioFileRecord(videoId, OfflinePolicy.cacheKey(videoId, audio.itag), audio.itag, audio.mimeType,
                audio.contentLength, AudioState.CACHED.key, false, 0, null, false, now, now))
        }
    }

    override fun playbackFactory(network: DataSource.Factory) = stores.playbackFactory(network)
    override fun cacheOnlyFactory() = stores.cacheOnlyFactory()

    // ---- Library actions ------------------------------------------------------------------------------

    suspend fun save(tracks: List<Track>): SaveSummary = withContext(Dispatchers.IO) {
        var queued = 0; var already = 0; var rejected = 0
        val now = System.currentTimeMillis()
        for (track in tracks.distinctBy { it.id }) {
            if (!OfflinePolicy.canRequestSave(track)) { rejected++; continue }
            val existing = dao.get(track.id)
            if (existing?.pinned == true && existing.audioState != AudioState.FAILED) { already++; continue }
            trackDao.insertTrack(track)
            dao.upsert((existing ?: AudioFileRecord(track.id, "", 0, "", 0, AudioState.QUEUED.key, true, 0, null, false, now, now))
                .copy(pinned = true, state = AudioState.QUEUED.key, failure = null, updatedAt = now))
            queued++
        }
        trigger.trySend(Unit)
        SaveSummary(queued, already, rejected, OfflinePolicy.blockReason(offlineOnly, settings.wifiOnlyDownloads.value, isMetered()))
    }

    fun retry(videoId: String) { scope.launch(Dispatchers.IO) {
        dao.get(videoId)?.let { dao.upsert(it.copy(state = AudioState.QUEUED.key, failure = null, updatedAt = System.currentTimeMillis())) }
        trigger.trySend(Unit)
    } }

    /** Cancel an active or queued download, or remove saved audio. Partial and complete bytes are deleted. */
    fun remove(videoId: String) { scope.launch(Dispatchers.IO) {
        if (activeId == videoId) activeJob?.cancelAndJoin()
        val row = dao.get(videoId)
        if (row != null && row.cacheKey.isNotEmpty()) runCatching { stores.saved.removeResource(row.cacheKey) }
        dao.delete(videoId)
        mutableProgress.value = mutableProgress.value - videoId
        refreshUsage()
    } }

    fun cancelAll() { scope.launch(Dispatchers.IO) {
        activeJob?.cancelAndJoin()
        dao.observePinnedSnapshot().filter { it.audioState in setOf(AudioState.QUEUED, AudioState.DOWNLOADING) }.forEach { remove(it.videoId) }
    } }

    /** Deletes only the temporary cache. Saved music is a separate store and is never touched here. */
    fun clearTemporaryCache() { scope.launch(Dispatchers.IO) {
        val cache = stores.temp
        cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
        dao.deleteUnpinned()
        refreshUsage()
    } }

    fun locationApplied(selected: cord.kasane.music.settings.StorageLocation): Boolean = stores.locationApplied(selected)

    fun refreshUsage() { scope.launch(Dispatchers.IO) {
        val replayable = dao.unpinnedRecords().count { r ->
            OfflinePolicy.playableFromCache(r, stores.temp.getCachedBytes(r.cacheKey.ifEmpty { "-" }, 0, r.contentLength.coerceAtLeast(0)))
        }
        mutableUsage.value = StorageUsage(stores.saved.cacheSpace, stores.temp.cacheSpace,
            settings.cacheLimitMb.value * 1024L * 1024L, dao.pinnedCount(), replayable)
    } }

    // ---- Download queue -------------------------------------------------------------------------------

    private suspend fun workerLoop() {
        dao.resetInterrupted()
        verifySaved()
        refreshUsage()
        while (scope.isActive) {
            val next = dao.nextQueued()
            if (next == null) { trigger.receive(); continue }
            val block = OfflinePolicy.blockReason(offlineOnly, settings.wifiOnlyDownloads.value, isMetered())
            if (block != null) {
                if (next.failure != block) dao.upsert(next.copy(failure = block))
                trigger.receive(); continue
            }
            activeId = next.videoId
            val job = scope.launch(Dispatchers.IO) { download(next) }
            activeJob = job
            job.join()
            activeId = null; activeJob = null
            refreshUsage()
        }
    }

    /** Saved rows whose bytes disappeared (storage cleared externally) must not claim to be available. */
    private suspend fun verifySaved() {
        dao.observePinnedSnapshot().filter { it.audioState == AudioState.SAVED }.forEach { row ->
            if (stores.saved.getCachedBytes(row.cacheKey, 0, row.contentLength) < row.contentLength)
                dao.upsert(row.copy(state = AudioState.FAILED.key, complete = false, failure = "Saved audio is missing. Save it again."))
        }
    }

    private suspend fun download(start: AudioFileRecord) {
        val id = start.videoId
        var row = start
        for (attempt in 1..MAX_ATTEMPTS) {
            val stream = try { resolve(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { playbackFailure(e) }
            when (val eligible = OfflinePolicy.eligibility(stream)) {
                is OfflinePolicy.Eligibility.NotSavable -> return fail(row, eligible.reason)
                OfflinePolicy.Eligibility.Direct -> Unit
            }
            val audio = (stream as ProgressiveStream).audio
            val key = OfflinePolicy.cacheKey(id, audio.itag)
            if (row.cacheKey.isNotEmpty() && row.cacheKey != key) runCatching { stores.saved.removeResource(row.cacheKey) }
            row = row.copy(cacheKey = key, itag = audio.itag, mimeType = audio.mimeType, contentLength = audio.contentLength,
                state = AudioState.DOWNLOADING.key, failure = null, updatedAt = System.currentTimeMillis())
            dao.upsert(row)
            mutableProgress.value = mutableProgress.value + (id to DownloadProgress(stores.saved.getCachedBytes(key, 0, audio.contentLength), audio.contentLength))
            var lastWrite = 0L
            val error = try {
                write(audio, key) { done ->
                    mutableProgress.value = mutableProgress.value + (id to DownloadProgress(done, audio.contentLength))
                    val now = System.currentTimeMillis()
                    if (now - lastWrite > 1000) { lastWrite = now; scope.launch(Dispatchers.IO) { dao.updateProgress(id, done) } }
                }
                null
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { e }
            if (error == null) {
                val stored = stores.saved.getCachedBytes(key, 0, audio.contentLength)
                val declared = ContentMetadata.getContentLength(stores.saved.getContentMetadata(key))
                if (stored >= audio.contentLength && (declared < 0 || declared == audio.contentLength)) {
                    saveArtwork(id)
                    dao.upsert(row.copy(state = AudioState.SAVED.key, complete = true, bytesDone = audio.contentLength,
                        failure = null, updatedAt = System.currentTimeMillis()))
                    mutableProgress.value = mutableProgress.value - id
                    return
                }
                return fail(row, "The download ended before the whole file was received. Try again.")
            }
            val code = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
            // An expired signed URL (403/410) is fixed by resolving again; written spans are kept and reused.
            if (code != 403 && code != 410) delay(1500L * attempt)
            if (attempt == MAX_ATTEMPTS) return fail(row, if (error is IOException) "The network connection failed. Check it and retry." else "The download could not be completed.")
        }
    }

    private suspend fun fail(row: AudioFileRecord, reason: String) {
        dao.upsert(row.copy(state = AudioState.FAILED.key, failure = reason, updatedAt = System.currentTimeMillis()))
        mutableProgress.value = mutableProgress.value - row.videoId
    }

    private suspend fun write(audio: ResolvedAudioStream, key: String, onProgress: (Long) -> Unit) = suspendCancellableCoroutine<Unit> { cont ->
        val source = stores.saveFactory(network).createDataSource()
        val spec = DataSpec.Builder().setUri(audio.url).setKey(key).build()
        val writer = CacheWriter(source, spec, null) { _, cached, _ -> onProgress(cached) }
        cont.invokeOnCancellation { writer.cancel() }
        Thread({
            try { writer.cache(); if (cont.isActive) cont.resume(Unit) }
            catch (e: Throwable) { if (cont.isActive) cont.resumeWithException(e) }
        }, "kasane-save").start()
    }

    private suspend fun saveArtwork(videoId: String) {
        val track = trackDao.getTrackById(videoId) ?: return
        val url = track.thumbnailUri?.takeIf { it.startsWith("https://") } ?: return
        val dir = File(app.filesDir, "offline-artwork").apply { mkdirs() }
        val file = File(dir, "$videoId.img")
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body ?: return
                if (!response.isSuccessful || body.contentLength() > MAX_ARTWORK_BYTES) return
                file.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            trackDao.insertTrack(track.copy(thumbnailUri = android.net.Uri.fromFile(file).toString()))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { file.delete() }
    }

    private fun isMetered(): Boolean = runCatching { app.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(false)

    private suspend fun AudioFileDao.observePinnedSnapshot(): List<AudioFileRecord> = observePinned().first()

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_ARTWORK_BYTES = 2L * 1024 * 1024
    }
}
