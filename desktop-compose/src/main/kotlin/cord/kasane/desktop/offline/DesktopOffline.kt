package cord.kasane.desktop.offline

import cord.kasane.desktop.DesktopSettings
import cord.kasane.music.model.Track
import cord.kasane.music.provider.ResolvedAudioStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

data class DownloadProgress(val bytes: Long, val total: Long) {
    val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
}

data class StorageUsage(val savedBytes: Long = 0, val tempBytes: Long = 0, val tempLimitBytes: Long = 0,
    val savedCount: Int = 0, val replayableCount: Int = 0)

data class SaveSummary(val queued: Int, val alreadySaved: Int, val rejected: Int, val blockedBy: String? = null)

/** Menu state of one track, like Android's OfflineBadge. */
enum class OfflineBadge { SAVED, DOWNLOADING, FAILED, QUEUED }

/** Thrown by the shared OkHttp client when Offline Only mode rejects a request. */
class OfflineModeException : IOException("Offline Only is on.")

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

@Serializable
data class OfflineRecord(
    val videoId: String,
    /** `ytm:<videoId>:<itag>` — two formats of one video never share bytes. */
    val cacheKey: String = "",
    val itag: Int = 0,
    val mimeType: String = "",
    val contentLength: Long = 0,
    /** queued | downloading | saved | cached | failed */
    val state: String = "queued",
    /** Explicitly saved by the user (saved store, no eviction) vs automatic stream cache (temp, LRU). */
    val pinned: Boolean = false,
    val bytesDone: Long = 0,
    /** Committed byte ranges, flattened as start0,len0,start1,len1… — survives restarts. */
    val ranges: List<Long> = emptyList(),
    val failure: String? = null,
    val track: Track? = null,
    val updatedAt: Long = 0,
    val lastPlayedAt: Long = 0,
) {
    val badge: OfflineBadge? get() = when {
        pinned && state == "saved" -> OfflineBadge.SAVED
        state == "downloading" -> OfflineBadge.DOWNLOADING
        state == "queued" -> OfflineBadge.QUEUED
        pinned && state == "failed" -> OfflineBadge.FAILED
        else -> null
    }
    fun committed() = ranges.chunked(2).sumOf { it[1] }
}

@Serializable
private data class OfflineIndex(val records: List<OfflineRecord> = emptyList(), val transient: List<String> = emptyList())

/**
 * The desktop offline library — same rules as Android's OfflineManager:
 * saved music lives in its own store forever, the stream cache is bounded and LRU-evicted,
 * one download runs at a time, interrupted rows re-queue on start and resume from committed
 * ranges, and a lost connection waits instead of failing. Bytes live in files, metadata in JSON.
 */
class DesktopOffline(
    private val root: File,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val settings: DesktopSettings,
    /** Progressive audio for saving: the verified WEB_REMIX stream only, like Android. */
    private val resolveForSave: suspend (videoId: String) -> ResolvedAudioStream?,
    /** Runs after a pinned track is fully saved — metadata/lyrics enrichment, best-effort. */
    private val onSaved: suspend (Track) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val savedDir = File(root, "offline/saved").apply { mkdirs() }
    private val tempDir = File(root, "offline/temp").apply { mkdirs() }
    private val artDir = File(root, "offline/art").apply { mkdirs() }
    private val indexFile = File(root, "offline/index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val indexLock = Any()
    private var index: OfflineIndex = runCatching {
        json.decodeFromString<OfflineIndex>(indexFile.readText()) }.getOrDefault(OfflineIndex())
    private val transient = index.transient.toMutableSet()

    private val _records = MutableStateFlow<List<OfflineRecord>>(emptyList())
    val records = _records.asStateFlow()
    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress = _progress.asStateFlow()
    private val _usage = MutableStateFlow(StorageUsage())
    val usage = _usage.asStateFlow()

    private val trigger = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var activeId: String? = null
    @Volatile private var activeJob: Job? = null
    /** Flips false when a download dies on a bare IOException; a success flips it back and re-arms transient rows. */
    @Volatile private var networkUp = true

    val offlineOnly: Boolean get() = settings.prefs.value.offlineOnly

    init {
        scope.launch(Dispatchers.IO) { workerLoop() }
        scope.launch {
            settings.prefs.collect { p -> if (p.cacheLimitMb > 0) trigger.trySend(Unit) }
        }
    }

    // ---- Queries --------------------------------------------------------------------------------------

    fun record(videoId: String): OfflineRecord? = synchronized(indexLock) { index.records.firstOrNull { it.videoId == videoId } }

    fun badge(track: Track): OfflineBadge? = record(track.id)?.badge

    /** A complete saved or cached file, or null. Never touches the network. */
    fun localFile(videoId: String): File? {
        val row = record(videoId) ?: return null
        if (row.cacheKey.isEmpty() || row.contentLength <= 0) return null
        val file = File(storeDir(row.pinned), row.cacheKey)
        return if (file.isFile && file.length() >= row.contentLength) file else null
    }

    /** Saved tracks matching a local query — Offline Only search, same field set as Android. */
    fun searchSaved(query: String): List<Track> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return synchronized(indexLock) { index.records }
            .filter { it.pinned && it.state == "saved" }
            .mapNotNull { it.track }
            .filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album?.lowercase()?.contains(q) == true }
    }

    /** Local artwork for a saved track, kept as an app-private file like on Android. */
    fun savedArtwork(videoId: String): String? = File(artDir, "$videoId.img").takeIf { it.isFile }?.toURI()?.toString()

    // ---- Actions --------------------------------------------------------------------------------------

    suspend fun save(tracks: List<Track>): SaveSummary = withContext(Dispatchers.IO) {
        var queued = 0; var already = 0; var rejected = 0
        for (track in tracks.distinctBy { it.id }) {
            if (!canSave(track)) { rejected++; continue }
            val existing = record(track.id)
            if (existing?.pinned == true && existing.state != "failed") { already++; continue }
            upsert((existing ?: OfflineRecord(track.id)).copy(
                pinned = true, state = "queued", failure = null, track = track, updatedAt = now()))
            queued++
        }
        trigger.trySend(Unit)
        SaveSummary(queued, already, rejected, blockReason())
    }

    fun retry(videoId: String) {
        record(videoId)?.let { upsert(it.copy(state = "queued", failure = null, updatedAt = now())) }
        transient.remove(videoId); persist()
        trigger.trySend(Unit)
    }

    /** Cancel a queued/active download or remove saved bytes. Both stores are cleaned. */
    fun remove(videoId: String) { scope.launch(Dispatchers.IO) {
        if (activeId == videoId) activeJob?.cancelAndJoin()
        transient.remove(videoId)
        pendingTracks.remove(videoId)
        record(videoId)?.let { row -> if (row.cacheKey.isNotEmpty()) { File(savedDir, row.cacheKey).delete(); File(tempDir, row.cacheKey).delete() } }
        File(savedDir, "$videoId.part").delete(); File(tempDir, "$videoId.part").delete()
        mutate { it.copy(records = it.records.filterNot { r -> r.videoId == videoId }) }
        _progress.value = _progress.value - videoId
        refreshUsage()
    } }

    fun cancelAll() { scope.launch(Dispatchers.IO) {
        activeJob?.cancelAndJoin()
        synchronized(indexLock) { index.records }.filter { it.state == "queued" || it.state == "downloading" }
            .forEach { remove(it.videoId) }
    } }

    /** Deletes only the temporary cache; saved music is a separate store and is never touched here. */
    fun clearTemporaryCache() { scope.launch(Dispatchers.IO) {
        tempDir.listFiles()?.forEach { it.delete() }
        mutate { it.copy(records = it.records.filter { r -> r.pinned }) }
        refreshUsage()
    } }

    /** Track metadata seen before the first cached bytes arrive; attached to the row onStreamBytes creates. */
    private val pendingTracks = java.util.concurrent.ConcurrentHashMap<String, Track>()

    /** A track started playing through the proxy: keep the resolved identity for write-through caching. */
    fun played(track: Track) {
        if (!settings.prefs.value.cacheEnabled || !canSave(track)) return
        pendingTracks[track.id] = track
        val row = record(track.id) ?: return // created by onStreamBytes; metadata attaches there
        upsert(row.copy(lastPlayedAt = now(), track = row.track ?: track))
    }

    /**
     * Write-through caching: the StreamProxy reports streamed bytes, which fill `temp/<key>.part`.
     * Once the ranges cover the file it becomes a replayable cache entry — the same promise
     * Android's CacheDataSource gives, without an extra network fetch.
     */
    fun onStreamBytes(videoId: String, itag: Int, mimeType: String, contentLength: Long,
        position: Long, bytes: ByteArray, offset: Int, count: Int) {
        if (!settings.prefs.value.cacheEnabled || contentLength <= 0 || !VIDEO_ID.matches(videoId)) return
        val key = cacheKey(videoId, itag)
        val existing = record(videoId)
        if (existing?.pinned == true) return // saved bytes already live in the saved store
        val part = File(tempDir, "$key.part")
        RandomAccessFile(part, "rw").use { f -> f.seek(position); f.write(bytes, offset, count) }
        val row = existing ?: OfflineRecord(videoId)
        val merged = mergeRanges(row.ranges + listOf(position, count.toLong()))
        upsert(row.copy(cacheKey = key, itag = itag, mimeType = mimeType, contentLength = contentLength,
            state = "downloading", pinned = false, ranges = merged, track = row.track ?: pendingTracks[videoId],
            bytesDone = merged.chunked(2).sumOf { it[1] }, updatedAt = now()), persist = false)
        if (merged.size == 2 && merged[0] == 0L && merged[1] >= contentLength) {
            val done = File(tempDir, key)
            if (part.renameTo(done) || part.length() == 0L) upsert(record(videoId)!!.copy(
                state = "cached", bytesDone = contentLength, lastPlayedAt = now()))
            trimTemp()
        }
    }

    /** Whether a download should wait instead of starting — same user-facing reasons as Android. */
    fun blockReason(): String? = when {
        offlineOnly -> "Offline Only mode is on."
        !networkUp -> "Waiting for a connection."
        else -> null
    }

    fun refreshUsage() { scope.launch(Dispatchers.IO) {
        val rows = synchronized(indexLock) { index.records }
        val saved = savedDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
        val temp = tempDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
        val replayable = rows.count { !it.pinned && it.state == "cached" }
        _usage.value = StorageUsage(saved, temp, settings.prefs.value.cacheLimitMb * 1024L * 1024L,
            rows.count { it.pinned && it.state == "saved" }, replayable)
    } }

    // ---- Worker ----------------------------------------------------------------------------------------

    private suspend fun workerLoop() {
        // Interrupted downloads re-queue; saved rows whose bytes vanished stop claiming availability.
        mutate { idx -> idx.copy(records = idx.records.map {
            if (it.state == "downloading") it.copy(state = "queued", failure = null) else it }) }
        verifySaved()
        refreshUsage()
        if (transient.isNotEmpty()) resumeTransient()
        while (scope.isActive) {
            val next = nextQueued()
            if (next == null) { trigger.receive(); continue }
            val block = blockReason()
            if (block != null) {
                if (next.failure != block) upsert(next.copy(failure = block))
                kotlinx.coroutines.withTimeoutOrNull(BLOCK_RECHECK_MS) { trigger.receive() }
                if (networkUp) resumeTransient()
                continue
            }
            activeId = next.videoId
            val job = scope.launch(Dispatchers.IO) { download(next) }
            activeJob = job
            job.join()
            activeId = null; activeJob = null
            refreshUsage()
        }
    }

    private fun nextQueued(): OfflineRecord? = synchronized(indexLock) {
        index.records.filter { it.state == "queued" }.sortedWith(
            compareByDescending<OfflineRecord> { it.pinned }.thenBy { it.updatedAt }).firstOrNull()
    }

    private fun verifySaved() {
        synchronized(indexLock) { index.records }.filter { it.state == "saved" || it.state == "cached" }.forEach { row ->
            val file = File(storeDir(row.pinned), row.cacheKey)
            if (row.cacheKey.isEmpty() || !file.isFile || file.length() < row.contentLength) {
                // A gone saved file is a user-visible failure; a gone cache entry just stops claiming replay.
                if (row.pinned) upsert(row.copy(state = "failed", failure = "Saved audio is missing. Save it again."))
                else mutate { it.copy(records = it.records.filterNot { r -> r.videoId == row.videoId }) }
            }
        }
    }

    private suspend fun resumeTransient() {
        transient.forEach { id ->
            record(id)?.let { if (it.pinned && it.state == "failed") upsert(it.copy(state = "queued", failure = null, updatedAt = now())) }
        }
        transient.clear(); persist()
        trigger.trySend(Unit)
    }

    private suspend fun download(start: OfflineRecord) {
        val id = start.videoId
        var row = start
        for (attempt in 1..MAX_ATTEMPTS) {
            val stream = try { resolveForSave(id) } catch (e: CancellationException) { throw e } catch (e: IOException) {
                networkUp = false
                if (attempt == MAX_ATTEMPTS) return fail(row,
                    "The network connection failed. The download will resume when the connection is back.", markTransient = true)
                delay(1500L * attempt); continue
            }
            networkUp = true
            if (stream == null) {
                if (attempt == MAX_ATTEMPTS) return fail(row, "This track is delivered as a streaming-only format and cannot be saved offline.")
                delay(1500L * attempt); continue
            }
            if (stream.contentLength <= 0) return fail(row,
                "YouTube did not report a file size for this track, so it cannot be verified as complete.")
            val key = cacheKey(id, stream.itag)
            val dir = storeDir(row.pinned)
            if (row.cacheKey.isNotEmpty() && row.cacheKey != key) { File(dir, row.cacheKey).delete(); File(dir, "${row.cacheKey}.part").delete() }
            row = row.copy(cacheKey = key, itag = stream.itag, mimeType = stream.mimeType, contentLength = stream.contentLength,
                state = "downloading", failure = null, updatedAt = now())
            upsert(row)
            val part = File(dir, "$key.part")
            _progress.value = _progress.value + (id to DownloadProgress(row.committed(), stream.contentLength))
            val error = try {
                writeRanges(stream, part, row) { done -> _progress.value = _progress.value + (id to DownloadProgress(done, stream.contentLength)) }
                null
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { e }
            if (error == null) {
                val current = record(id) ?: return
                val file = File(dir, key)
                if ((part.isFile && part.renameTo(file) || file.isFile) && file.length() >= stream.contentLength) {
                    if (row.pinned) saveArtwork(id)
                    transient.remove(id); persist()
                    upsert(current.copy(state = if (row.pinned) "saved" else "cached", ranges = emptyList(),
                        bytesDone = stream.contentLength, failure = null, updatedAt = now()))
                    _progress.value = _progress.value - id
                    if (row.pinned) row.track?.let { runCatching { onSaved(it) } }
                    return
                }
                return fail(row, "The download ended before the whole file was received. Try again.")
            }
            val code = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<AudioHttpException>().firstOrNull()?.code
            if (code != 403 && code != 410) delay(1500L * attempt)
            if (attempt == MAX_ATTEMPTS) {
                val markTransient = code == null && error is IOException
                if (markTransient) networkUp = false
                return fail(row, if (error is IOException)
                    "The network connection failed. The download will resume when the connection is back."
                    else "The download could not be completed.", markTransient = markTransient)
            }
        }
    }

    private fun fail(row: OfflineRecord, reason: String, markTransient: Boolean = false) {
        if (markTransient) transient.add(row.videoId) else transient.remove(row.videoId)
        upsert(row.copy(state = "failed", failure = reason, updatedAt = now()))
        _progress.value = _progress.value - row.videoId
    }

    /**
     * Ranges committed in earlier attempts are skipped — a killed process resumes from bytes
     * already on disk. Up to [PARALLEL_RANGES] range requests share one scratch file; googlevideo
     * caps a single connection, so parallel ranges are the fast path Android measured.
     */
    private suspend fun writeRanges(stream: ResolvedAudioStream, part: File, start: OfflineRecord,
        onProgress: (Long) -> Unit) = coroutineScope {
        val parts = chunks(stream.contentLength)
        RandomAccessFile(part, "rw").use { f -> f.setLength(stream.contentLength) }
        val channel = java.io.RandomAccessFile(part, "rw").channel
        val committed = start.ranges.chunked(2).map { it[0] to it[1] }
        val fetched = java.util.concurrent.atomic.AtomicLong(committed.sumOf { it.second })
        val pending = parts.filter { (pos, len) -> committed.none { (cp, cl) -> cp <= pos && cp + cl >= pos + len } }
        pending.map { (position, length) ->
            async(Dispatchers.IO) {
                val buffer = ByteArray(64 * 1024)
                var offset = 0L
                http.newCall(Request.Builder().url(stream.url).header("Range", "bytes=$position-${position + length - 1}")
                    .apply { stream.headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { response ->
                    if (response.code == 403 || response.code == 410) throw AudioHttpException(response.code)
                    if (!response.isSuccessful) throw IOException("Audio server returned ${response.code}.")
                    val body = response.body?.byteStream() ?: throw IOException("Empty audio response.")
                    while (offset < length) {
                        val n = body.read(buffer, 0, minOf(buffer.size.toLong(), length - offset).toInt())
                        if (n < 0) break
                        channel.write(java.nio.ByteBuffer.wrap(buffer, 0, n), position + offset)
                        offset += n
                        onProgress(fetched.addAndGet(n.toLong()))
                    }
                }
                if (offset != length) throw IOException("Short read: $offset of $length bytes at $position")
                synchronized(indexLock) {
                    index.records.firstOrNull { it.videoId == start.videoId }?.let { row ->
                        index = index.copy(records = index.records.map { if (it.videoId == start.videoId)
                            row.copy(ranges = mergeRanges(row.ranges + listOf(position, length)), bytesDone = fetched.get()) else it })
                    }
                }
            }
        }.awaitAll()
        channel.close()
    }

    /** Official cover wins for saved tracks: replaces the artwork file and repoints the record. */
    suspend fun saveArtworkFrom(videoId: String, url: String) {
        val file = File(artDir, "$videoId.img")
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body ?: return
                if (!response.isSuccessful || body.contentLength() > MAX_ARTWORK_BYTES) return
                file.outputStream().use { body.byteStream().copyTo(it) }
            }
            record(videoId)?.let { row ->
                row.track?.let { upsert(row.copy(track = it.copy(thumbnailUri = file.toURI().toString()), updatedAt = now())) }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }

    private suspend fun saveArtwork(videoId: String) {
        val url = record(videoId)?.track?.thumbnailUri?.takeIf { it.startsWith("https://") } ?: return
        val file = File(artDir, "$videoId.img")
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body ?: return
                if (!response.isSuccessful || body.contentLength() > MAX_ARTWORK_BYTES) return
                file.outputStream().use { body.byteStream().copyTo(it) }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { file.delete() }
    }

    // ---- Index & stores ---------------------------------------------------------------------------------

    private fun storeDir(pinned: Boolean) = if (pinned) savedDir else tempDir

    private fun upsert(row: OfflineRecord, persist: Boolean = true) {
        synchronized(indexLock) {
            index = index.copy(records = (index.records.filterNot { it.videoId == row.videoId } + row))
        }
        if (persist) persist()
        _records.value = synchronized(indexLock) { index.records }.sortedByDescending { it.updatedAt }
    }

    private fun mutate(change: (OfflineIndex) -> OfflineIndex) {
        synchronized(indexLock) { index = change(index) }
        persist()
        _records.value = synchronized(indexLock) { index.records }.sortedByDescending { it.updatedAt }
    }

    private fun persist() {
        val text = synchronized(indexLock) { json.encodeToString(OfflineIndex.serializer(), index.copy(transient = transient.toList())) }
        indexFile.parentFile?.mkdirs()
        val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(indexFile)) indexFile.writeText(text)
    }

    /** The temporary cache is bounded by the user's limit; least recently used entries go first. */
    private fun trimTemp() {
        val limit = settings.prefs.value.cacheLimitMb * 1024L * 1024L
        var total = tempDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
        if (total <= limit) return
        val entries = synchronized(indexLock) { index.records }.filter { !it.pinned }.sortedBy { it.lastPlayedAt }
        for (row in entries) {
            if (total <= limit) break
            val f = File(tempDir, row.cacheKey)
            if (f.isFile) { total -= f.length(); f.delete() }
            File(tempDir, "${row.cacheKey}.part").delete()
            mutate { it.copy(records = it.records.filterNot { r -> r.videoId == row.videoId }) }
        }
    }

    companion object {
        fun cacheKey(videoId: String, itag: Int) = "ytm:$videoId:$itag"
        fun canSave(track: Track) = !track.isLocal && track.sourceProvider == "youtube" && VIDEO_ID.matches(track.id)
        fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024L * 1024 -> "%.0f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
            else -> "%.2f GB".format(bytes / 1073741824.0)
        }
        private const val MAX_ATTEMPTS = 3
        private const val BLOCK_RECHECK_MS = 15_000L
        private const val MAX_ARTWORK_BYTES = 2L * 1024 * 1024
        const val PARALLEL_RANGES = 6
        const val MIN_RANGE_BYTES = 512L * 1024
    }
}

private class AudioHttpException(val code: Int) : IOException("Audio server refused the stream ($code).")

private fun chunks(contentLength: Long): List<Pair<Long, Long>> {
    if (contentLength <= DesktopOffline.MIN_RANGE_BYTES) return listOf(0L to contentLength)
    val count = minOf(DesktopOffline.PARALLEL_RANGES, (contentLength / DesktopOffline.MIN_RANGE_BYTES).toInt())
    val size = (contentLength + count - 1) / count
    return (0 until count).map { it * size to minOf((it + 1) * size, contentLength) - it * size }
}

private fun mergeRanges(flat: List<Long>): List<Long> {
    val ranges = flat.chunked(2).map { it[0] to it[0] + it[1] }.sortedBy { it.first }
    val out = mutableListOf<Pair<Long, Long>>()
    for (r in ranges) {
        val last = out.lastOrNull()
        if (last != null && r.first <= last.second) out[out.lastIndex] = last.first to maxOf(last.second, r.second)
        else out += r
    }
    return out.flatMap { listOf(it.first, it.second - it.first) }
}
