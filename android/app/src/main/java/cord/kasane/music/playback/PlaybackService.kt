package cord.kasane.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import cord.kasane.music.KasaneApplication
import cord.kasane.music.MainActivity
import cord.kasane.music.model.HistoryEntity
import cord.kasane.music.model.Track
import kotlinx.coroutines.*
import java.io.IOException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var historyRecordedForWindow = false
    private var radioJob: Job? = null
    private var radioSeed: String? = null
    private var radioForced = false
    private var radioCollectionId: String? = null
    private var radioContinuation: String? = null
    private var radioGeneration = 0
    private val requestedRadioPages = mutableSetOf<String>()
    private val radioFailures = mutableMapOf<String, Int>()
    private val autoplayListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
        if (key == "autoplay" && ::player.isInitialized) {
            if (!preferences.getBoolean("autoplay", true) && !radioForced) {
                radioJob?.cancel(); radioGeneration++
                requestedRadioPages.remove(radioContinuation ?: "initial:$radioSeed")
                radioFailures.remove(radioContinuation ?: "initial:$radioSeed")
            } else fillRadio()
        }
    }

    private fun nearQueueEnd(): Boolean {
        var index = player.currentMediaItemIndex
        var remaining = 0
        val timeline = player.currentTimeline
        while (!timeline.isEmpty && index != C.INDEX_UNSET && remaining <= 4) {
            remaining++
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
        }
        return remaining <= 4
    }
    private fun configureRadio(item: MediaItem?) {
        if (item == null || item.mediaMetadata.extras?.getBoolean("isLocal") == true) {
            radioJob?.cancel(); radioGeneration++; radioSeed = null; radioCollectionId = null
            radioContinuation = null; requestedRadioPages.clear(); radioFailures.clear(); return
        }
        val extras = item.mediaMetadata.extras
        val mode = extras?.getString("radioMode")
        if (mode in setOf("single", "forced")) {
            val forced = mode == "forced"
            if (radioSeed == item.mediaId && radioCollectionId == null && radioForced == forced) return
            radioJob?.cancel(); radioGeneration++
            radioSeed = item.mediaId; radioForced = forced; radioCollectionId = null
            radioContinuation = null; requestedRadioPages.clear(); radioFailures.clear()
            // Drop only the previous radio's future entries. Explicit manual/collection items stay.
            val future = mutableSetOf<Int>()
            val timeline = player.currentTimeline
            var index = player.currentMediaItemIndex
            while (!timeline.isEmpty && index != C.INDEX_UNSET) {
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
                if (index == C.INDEX_UNSET || !future.add(index)) break
            }
            future.sortedDescending().forEach { i ->
                if (player.getMediaItemAt(i).mediaMetadata.extras?.getString("radioMode") == "radio") player.removeMediaItem(i)
            }
        } else if (mode == "collection") {
            val contextId = extras.getString("collectionContext") ?: item.mediaId
            if (contextId == radioCollectionId) return
            radioJob?.cancel(); radioGeneration++; radioForced = false; radioCollectionId = contextId
            radioSeed = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                .lastOrNull { it.mediaMetadata.extras?.getString("collectionContext") == contextId }?.mediaId ?: item.mediaId
            radioContinuation = null; requestedRadioPages.clear(); radioFailures.clear()
        }
    }
    private fun fillRadio() {
        val seed = radioSeed ?: return
        if (radioJob?.isActive == true || player.mediaItemCount >= QueuePolicy.LIMIT ||
            !QueuePolicy.shouldPrefetchRadio(nearQueueEnd(), requestedRadioPages.isEmpty(), radioCollectionId != null,
                player.repeatMode != Player.REPEAT_MODE_OFF,
                getSharedPreferences("playback", MODE_PRIVATE).getBoolean("autoplay", true), radioForced)) return
        val token = radioContinuation
        val key = token ?: "initial:$seed"
        if ((radioFailures[key] ?: 0) >= 2 || !requestedRadioPages.add(key)) return
        val generation = radioGeneration
        radioJob = scope.launch {
            try {
                val app = application as KasaneApplication
                val page = app.ytProvider.catalog.radioPage(seed, token)
                if (generation != radioGeneration || radioSeed != seed) return@launch
                if (player.repeatMode != Player.REPEAT_MODE_OFF || (!radioForced && !getSharedPreferences("playback", MODE_PRIVATE).getBoolean("autoplay", true))) {
                    requestedRadioPages.remove(key); return@launch
                }
                radioFailures.remove(key)
                radioContinuation = page.continuation
                val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                val next = QueuePolicy.radioAppend(existing, page.tracks, QueuePolicy.LIMIT - player.mediaItemCount)
                player.addMediaItems(next.map { trackToMediaItem(it, radioMode = "radio") })
                android.util.Log.i("KasanePlayback", "videoId=$seed event=radio_page added=${next.size} continuation=${page.continuation != null}")
                if (player.playbackState == Player.STATE_ENDED && player.hasNextMediaItem() && player.playWhenReady) {
                    player.seekToNextMediaItem(); player.prepare(); player.play()
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (generation != radioGeneration) return@launch
                val attempts = (radioFailures[key] ?: 0) + 1
                radioFailures[key] = attempts; requestedRadioPages.remove(key)
                android.util.Log.w("KasanePlayback", "videoId=$seed event=radio_failed attempt=$attempts")
                if (attempts < 2) scope.launch {
                    delay(1500)
                    if (generation == radioGeneration && radioSeed == seed) fillRadio()
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        val app = application as KasaneApplication
        // Media3 resolves the next item on its loader thread, including hardware/session skips.
        // The timeline retains stable media IDs; expiring CDN URLs never become the queue identity.
        val network = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000).setReadTimeoutMs(15000)
        val factory = object : androidx.media3.exoplayer.source.MediaSource.Factory {
            override fun setDrmSessionManagerProvider(provider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider) = this
            override fun setLoadErrorHandlingPolicy(policy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy) = this
            override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_OTHER)
            override fun createMediaSource(item: MediaItem): androidx.media3.exoplayer.source.MediaSource =
                if (item.localConfiguration?.uri?.scheme == "kasane")
                    TransportMediaSource(item, app.ytProvider.hlsResolver::resolve, network,
                        playbackPositionMs = { if (player.currentMediaItem?.mediaId == item.mediaId) player.currentPosition else 0L })
                else DefaultMediaSourceFactory(DefaultDataSource.Factory(this@PlaybackService))
                    .setLoadErrorHandlingPolicy(BoundedStreamLoadPolicy()).createMediaSource(item)
        }
        player = ExoPlayer.Builder(this).setMediaSourceFactory(factory)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        getSharedPreferences("playback", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(autoplayListener)
        player.addListener(object : Player.Listener {
            private fun recordHistory() {
                if (!player.isPlaying || historyRecordedForWindow) return
                val item = player.currentMediaItem ?: return
                historyRecordedForWindow = true
                val track = mediaItemToTrack(item)
                scope.launch {
                    try {
                        app.database.trackDao().insertTrack(track)
                        app.database.trackDao().insertHistory(HistoryEntity(trackId = track.id, title = track.title,
                            artist = track.artist, album = track.album, thumbnailUri = track.thumbnailUri))
                    } catch (_: Exception) { /* A storage error must never stop playback. */ }
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group ->
                    for (index in 0 until group.length) if (group.isTrackSelected(index)) {
                        val format = group.getTrackFormat(index)
                        android.util.Log.i("KasanePlayback", "videoId=${player.currentMediaItem?.mediaId} event=audio_format mime=${format.sampleMimeType} codecs=${format.codecs} positionMs=${player.currentPosition}")
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val http = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                android.util.Log.w("KasanePlayback", "videoId=${player.currentMediaItem?.mediaId} event=playback_error positionMs=${player.currentPosition} media3Code=${error.errorCode} http=${http?.responseCode ?: -1}")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { recordHistory() }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                configureRadio(mediaItem)
                historyRecordedForWindow = false
                recordHistory()
                fillRadio()
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                configureRadio(player.currentMediaItem); fillRadio()
            }
            override fun onRepeatModeChanged(repeatMode: Int) { if (repeatMode == Player.REPEAT_MODE_OFF) fillRadio() }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { fillRadio() }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) fillRadio()
            }
        })
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep active music and the session alive when the UI is dismissed.
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) stopSelf()
    }
    override fun onDestroy() {
        getSharedPreferences("playback", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(autoplayListener)
        scope.cancel()
        mediaSession?.let { it.player.release(); it.release() }
        mediaSession = null
        super.onDestroy()
    }
    companion object {
        fun trackToMediaItem(track: Track, streamUrl: String? = track.streamUri, radioMode: String? = null, collectionContext: String? = null): MediaItem {
            val extras = Bundle().apply { putDouble("durationSeconds", track.durationSeconds); putBoolean("isLocal", track.isLocal); radioMode?.let { putString("radioMode", it) }; collectionContext?.let { putString("collectionContext", it) } }
            return MediaItem.Builder().setMediaId(track.id)
                .setUri(if (!track.isLocal && track.sourceProvider == "youtube") "kasane://track/${Uri.encode(track.id)}"
                    else streamUrl ?: "kasane://track/${Uri.encode(track.id)}")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                    .setAlbumTitle(track.album).setArtworkUri(track.thumbnailUri?.let(Uri::parse))
                    .setExtras(extras).build()).build()
        }
        fun mediaItemToTrack(item: MediaItem): Track {
            val data = item.mediaMetadata
            return Track(item.mediaId, data.title?.toString().orEmpty(), data.artist?.toString().orEmpty(),
                album = data.albumTitle?.toString(), durationSeconds = data.extras?.getDouble("durationSeconds") ?: 0.0,
                thumbnailUri = data.artworkUri?.toString(),
                streamUri = item.localConfiguration?.uri?.takeIf { it.scheme != "kasane" }?.toString(),
                isLocal = data.extras?.getBoolean("isLocal") ?: false)
        }
    }
}
