package cord.kasane.desktop.playback

import cord.kasane.desktop.audio.MpvEngine
import cord.kasane.desktop.audio.StreamProxy
import cord.kasane.desktop.library.LocalLibrary
import cord.kasane.music.model.Track
import cord.kasane.music.playback.QueuePolicy
import cord.kasane.music.provider.GuestPlayerResolver
import cord.kasane.music.provider.SharedOnlineCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

enum class RepeatMode { OFF, ALL, ONE }

data class PlayerState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val volume: Float = 1f,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val error: String? = null,
    /** A short, self-clearing message, e.g. that another device changed the volume. */
    val notice: String? = null,
) {
    val current: Track? get() = queue.getOrNull(index)
    val canNext: Boolean get() = index + 1 < queue.size || (repeat == RepeatMode.ALL && queue.isNotEmpty())
    val canPrevious: Boolean get() = index > 0 || positionSec > 3
}

/**
 * The desktop playback owner: Kasane's queue semantics (shared [QueuePolicy]),
 * YouTube radio for single-track starts, and one URL at a time handed to mpv.
 */
class DesktopPlayer(
    private val scope: CoroutineScope,
    private val engine: MpvEngine,
    private val resolver: GuestPlayerResolver,
    private val proxy: StreamProxy,
    private val hls: cord.kasane.music.provider.IosHlsResolver,
    private val catalog: SharedOnlineCatalog,
    private val library: LocalLibrary,
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Queue entries started as a radio context, and the context that may still append to it. */
    private var radioContext: String? = null
    private var radioContinuation: String? = null
    private var radioSeed: Track? = null
    private var radioJob: Job? = null
    private var loadJob: Job? = null
    private var unshuffled: List<Track>? = null
    private var refreshedFor: String? = null

    init {
        scope.launch {
            engine.status.collect { s ->
                _state.update { it.copy(
                    positionSec = s.positionSec, durationSec = if (s.durationSec > 0) s.durationSec else it.durationSec,
                    isPlaying = !s.paused && !s.idle, isLoading = it.isLoading && s.idle || s.buffering,
                    volume = (s.volume / 100.0).toFloat())
                }
                if (!s.idle && !s.buffering) _state.update { it.copy(isLoading = false) }
            }
        }
        scope.launch {
            engine.events.collect { event ->
                when (event) {
                    MpvEngine.Event.Ended -> onEnded()
                    is MpvEngine.Event.Failed -> onFailed(event.reason)
                }
            }
        }
    }

    /** A track from search/home: a new context of its own, followed by its radio. */
    fun playTrack(track: Track) {
        val current = _state.value.current
        if (!QueuePolicy.startsNewContext(current?.id, track.id, forcedRadio = false)) { if (!_state.value.isPlaying) toggle(); return }
        startRadio(track)
    }

    fun startRadio(track: Track) {
        val start = QueuePolicy.newContext(track)
        unshuffled = null
        setQueue(start.items, start.startIndex, shuffle = false)
        val context = UUID.randomUUID().toString()
        radioContext = context
        radioSeed = track
        radioContinuation = null
        loadRadio(context, track.id, null)
    }

    /** A list (album, playlist, liked songs) in its own order; shuffle only picks the start. */
    fun playCollection(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        val start = QueuePolicy.collectionStart(tracks, shuffle) { (0 until it).random() } ?: return
        radioContext = null
        radioSeed = null
        unshuffled = null
        setQueue(start.items, if (shuffle) start.startIndex else startIndex.coerceIn(0, start.items.lastIndex), shuffle = false)
        if (shuffle) toggleShuffle()
    }

    private fun setQueue(items: List<Track>, index: Int, shuffle: Boolean) {
        _state.update { it.copy(queue = items, index = index, shuffle = shuffle, error = null) }
        loadCurrent(0.0, play = true)
    }

    private fun loadRadio(context: String, videoId: String, continuation: String?) {
        radioJob?.cancel()
        radioJob = scope.launch {
            val page = try { catalog.radioPage(videoId, continuation) } catch (e: CancellationException) { throw e } catch (_: Exception) { return@launch }
            if (!QueuePolicy.acceptRadioPage(context, setOf(radioContext))) return@launch
            radioContinuation = page.continuation
            _state.update { s ->
                val additions = QueuePolicy.radioAppend(s.queue.map { it.id }.toSet(),
                    QueuePolicy.dropSeedAliases(page.tracks, radioSeed), QueuePolicy.LIMIT - s.queue.size)
                s.copy(queue = s.queue + additions)
            }
        }
    }

    private fun maybeExtendRadio() {
        val s = _state.value
        val context = radioContext ?: return
        val seed = radioSeed ?: return
        if (s.queue.size - s.index <= 3 && radioJob?.isActive != true && radioContinuation != null) {
            loadRadio(context, seed.id, radioContinuation)
        }
    }

    private fun loadCurrent(startSec: Double, play: Boolean, forceRefresh: Boolean = false) {
        val track = _state.value.current ?: return
        loadJob?.cancel()
        _state.update { it.copy(isLoading = true, error = null, positionSec = startSec, durationSec = track.durationSeconds) }
        loadJob = scope.launch {
            try {
                // iOS HLS gives full-length audio when YouTube offers it; otherwise the guest direct stream,
                // which YouTube may cut after its first part (detected in onEnded, never skipped silently).
                val manifest = runCatching { hls.resolve(track.id) }.getOrNull()
                if (_state.value.current?.id != track.id) return@launch
                if (manifest != null) engine.load(manifest.url, manifest.userAgent, startSec, play)
                else {
                    val stream = resolver.resolve(track.id, forceRefresh = forceRefresh)
                    if (_state.value.current?.id != track.id) return@launch
                    engine.load(proxy.register(track.id, stream), null, startSec, play)
                }
                library.recordPlayed(track)
                maybeExtendRadio()
            } catch (e: CancellationException) { throw e
            } catch (e: IOException) {
                _state.update { it.copy(isLoading = false, isPlaying = false, error = e.message ?: "This track could not be played.") }
            }
        }
    }

    private fun onEnded() {
        val s = _state.value
        val id = s.current?.id
        if (id != null && proxy.consumeCutOff(id)) {
            _state.update { it.copy(isPlaying = false, isLoading = false, error = "YouTube stopped this track after its first part. " +
                "Full-length playback on this desktop build needs verified, signed-in playback, which is not available here yet.") }
            return
        }
        when {
            s.repeat == RepeatMode.ONE -> loadCurrent(0.0, play = true)
            s.index + 1 < s.queue.size -> moveTo(s.index + 1)
            s.repeat == RepeatMode.ALL && s.queue.isNotEmpty() -> moveTo(0)
            else -> _state.update { it.copy(isPlaying = false) }
        }
    }

    /** A signed URL can expire mid-session; reload it once at the same position, then report. */
    private fun onFailed(reason: String) {
        val s = _state.value
        val id = s.current?.id ?: return
        if (refreshedFor != id) {
            refreshedFor = id
            loadCurrent(s.positionSec, play = true, forceRefresh = true)
        } else {
            _state.update { it.copy(isLoading = false, isPlaying = false, error = "Playback failed: $reason") }
        }
    }

    private fun moveTo(index: Int, play: Boolean = true) {
        _state.update { it.copy(index = index) }
        refreshedFor = null
        loadCurrent(0.0, play)
    }

    fun toggle() {
        val s = _state.value
        if (s.current == null) return
        if (s.error != null || (!s.isPlaying && engine.status.value.idle)) loadCurrent(s.positionSec, play = true)
        else engine.setPaused(s.isPlaying)
    }

    fun pause() { if (_state.value.isPlaying) engine.setPaused(true) }

    fun next() {
        val s = _state.value
        if (s.index + 1 < s.queue.size) moveTo(s.index + 1)
        else if (s.repeat == RepeatMode.ALL && s.queue.isNotEmpty()) moveTo(0)
    }

    fun previous() {
        val s = _state.value
        if (s.positionSec > 3 || s.index <= 0) seek(0.0) else moveTo(s.index - 1)
    }

    fun seek(sec: Double) {
        _state.update { it.copy(positionSec = sec) }
        engine.seek(sec)
    }

    fun setVolume(volume: Float) = engine.setVolume(volume.coerceIn(0f, 1f) * 100.0)

    fun playIndex(index: Int) { if (index in _state.value.queue.indices) moveTo(index) }

    fun addToQueue(track: Track) {
        val s = _state.value
        if (s.queue.isEmpty()) return playCollection(listOf(track))
        if (s.queue.none { it.id == track.id }) _state.update { it.copy(queue = it.queue + track) }
    }

    fun playNext(track: Track) {
        val s = _state.value
        if (s.queue.isEmpty()) return playCollection(listOf(track))
        val existing = s.queue.indexOfFirst { it.id == track.id }.takeIf { it >= 0 }
        if (existing == s.index) return
        val target = QueuePolicy.nextInsertionIndex(s.index, existing, s.queue.size)
        val without = s.queue.toMutableList().apply { if (existing != null) removeAt(existing) }
        val current = s.queue[s.index]
        without.add(target.coerceIn(0, without.size), track)
        _state.update { it.copy(queue = without, index = without.indexOfFirst { t -> t.id == current.id }) }
    }

    fun removeAt(index: Int) {
        val s = _state.value
        if (index !in s.queue.indices || index == s.index) return
        _state.update { it.copy(queue = s.queue.filterIndexed { i, _ -> i != index }, index = if (index < s.index) s.index - 1 else s.index) }
    }

    fun move(from: Int, to: Int) {
        val s = _state.value
        val (f, t) = QueuePolicy.validMove(s.queue.size, from, to) ?: return
        val list = s.queue.toMutableList().apply { add(t, removeAt(f)) }
        _state.update { it.copy(queue = list, index = QueuePolicy.indexAfterMove(s.index, f, t)) }
    }

    /** Shuffle reorders what is still to come, keeping the current track; turning it off restores the order. */
    fun toggleShuffle() {
        val s = _state.value
        val current = s.current ?: return
        if (!s.shuffle) {
            unshuffled = s.queue
            val upcoming = s.queue.drop(s.index + 1).shuffled()
            _state.update { it.copy(queue = s.queue.take(s.index + 1) + upcoming, shuffle = true) }
        } else {
            val original = unshuffled?.filter { t -> s.queue.any { it.id == t.id } }
                ?.let { base -> base + s.queue.filter { t -> base.none { it.id == t.id } } } ?: s.queue
            unshuffled = null
            _state.update { it.copy(queue = original, index = original.indexOfFirst { it.id == current.id }, shuffle = false) }
        }
    }

    fun cycleRepeat() = _state.update { it.copy(repeat = RepeatMode.entries[(it.repeat.ordinal + 1) % 3]) }

    fun clearError() = _state.update { it.copy(error = null) }

    private var noticeJob: Job? = null
    fun notify(message: String) {
        _state.update { it.copy(notice = message) }
        noticeJob?.cancel()
        noticeJob = scope.launch { kotlinx.coroutines.delay(3_000); _state.update { s -> if (s.notice == message) s.copy(notice = null) else s } }
    }

    /** Connect handoff target: start a queue at a position, resolving media locally. */
    fun startHandoff(queue: List<Track>, index: Int, positionSec: Double, play: Boolean, shuffle: Boolean, repeat: RepeatMode) {
        radioContext = null
        unshuffled = null
        _state.update { it.copy(queue = queue, index = index.coerceIn(0, queue.lastIndex), shuffle = shuffle, repeat = repeat, error = null) }
        refreshedFor = null
        loadCurrent(positionSec, play)
    }
}
