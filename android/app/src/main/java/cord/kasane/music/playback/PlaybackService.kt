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
    override fun onCreate() {
        super.onCreate()
        val app = application as KasaneApplication
        // Media3 resolves the next item on its loader thread, including hardware/session skips.
        // The timeline retains stable media IDs; expiring CDN URLs never become the queue identity.
        val sources = androidx.media3.datasource.DataSource.Factory {
            YouTubeDataSource(DefaultDataSource.Factory(this).createDataSource(), app.ytProvider.playerResolver)
        }
        player = ExoPlayer.Builder(this)
            // Briefly recover from transient socket/network changes, then surface the error.
            // This bounds retries (including Wi-Fi/LTE handoffs) without adding a parallel retry loop.
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(2)))
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
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
            override fun onIsPlayingChanged(isPlaying: Boolean) { recordHistory() }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                radioJob?.cancel()
                historyRecordedForWindow = false
                recordHistory()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED || !player.playWhenReady || player.hasNextMediaItem() ||
                    player.repeatMode != Player.REPEAT_MODE_OFF || radioJob?.isActive == true) return
                val seed = player.currentMediaItem?.mediaId ?: return
                radioJob = scope.launch {
                    try {
                        val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                        val next = app.ytProvider.catalog.radio(seed).filter { it.id !in existing }.take(25)
                        if (player.currentMediaItem?.mediaId != seed || !player.playWhenReady || player.playbackState != Player.STATE_ENDED || next.isEmpty()) return@launch
                        if (player.mediaItemCount + next.size > QueuePolicy.LIMIT) return@launch
                        player.addMediaItems(next.map { trackToMediaItem(it) })
                        player.seekToNextMediaItem(); player.prepare(); player.play()
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { /* Radio is optional. The explicit queue remains playable. */ }
                }
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
        scope.cancel()
        mediaSession?.let { it.player.release(); it.release() }
        mediaSession = null
        super.onDestroy()
    }
    companion object {
        fun trackToMediaItem(track: Track, streamUrl: String? = track.streamUri): MediaItem {
            val extras = Bundle().apply { putDouble("durationSeconds", track.durationSeconds); putBoolean("isLocal", track.isLocal) }
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
