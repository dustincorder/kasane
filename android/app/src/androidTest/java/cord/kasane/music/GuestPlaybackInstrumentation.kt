package cord.kasane.music

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import cord.kasane.music.model.Track
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * No Activity or second player. Exercises the real PlaybackService/MediaSession,
 * muted and without requesting audio focus, so the user's other app keeps playing.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GuestPlaybackInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun report(line: String) {
        android.util.Log.i("KasanePlaybackProbe", line)
        sendStatus(0, Bundle().apply { putString("stream", "$line\n") })
    }
    private fun <T> main(block: () -> T): T {
        var value: T? = null
        var error: Throwable? = null
        runOnMainSync { try { value = block() } catch (e: Throwable) { error = e } }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
    private fun waitFor(label: String, timeout: Long = 40000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < until) {
            if (main(condition)) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out: $label")
    }
    override fun onStart() {
        var controller: MediaController? = null
        var saved: List<androidx.media3.common.MediaItem> = emptyList()
        var savedIndex = 0
        var savedPosition = 0L
        var savedVolume = 1f
        var savedAttributes = androidx.media3.common.AudioAttributes.DEFAULT
        var touched = false
        var passed = false
        var savedShuffle = false
        var savedRepeat = Player.REPEAT_MODE_OFF
        try {
            val app = targetContext.applicationContext as KasaneApplication
            val future = main { MediaController.Builder(targetContext,
                SessionToken(targetContext, ComponentName(targetContext, PlaybackService::class.java))).buildAsync() }
            val player = future.get(30, TimeUnit.SECONDS)
            controller = player
            main {
                check(!player.isPlaying && !player.playWhenReady) { "Kasane is active; refusing to interrupt it" }
                check(player.isCommandAvailable(Player.COMMAND_SET_AUDIO_ATTRIBUTES)) { "Audio-focus control unavailable; probe cancelled" }
                saved = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                savedIndex = player.currentMediaItemIndex
                savedPosition = player.currentPosition
                savedVolume = player.volume
                savedAttributes = player.audioAttributes
                savedShuffle = player.shuffleModeEnabled
                savedRepeat = player.repeatMode
                player.volume = 0f
                player.setAudioAttributes(savedAttributes, false)
                touched = true
            }
            val seeds = listOf(
                Triple("kIft-LUHHVA", "Espresso", "Sabrina Carpenter"),
                Triple("ZVgHPSyEIqk", "Let Down", "Radiohead"),
                Triple("kOn-HdEg6AQ", "Beat It", "Michael Jackson"),
                Triple("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley"))
            val tracks = seeds.map { (id, title, artist) ->
                val found = runBlocking { app.ytProvider.catalog.search("$artist $title", CatalogKind.SONG).tracks.firstOrNull { it.id == id } }
                found ?: Track(id, title, artist)
            }
            main {
                player.setMediaItems(tracks.map { PlaybackService.trackToMediaItem(it) })
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_OFF
            }
            for ((index, track) in tracks.withIndex()) {
                main { player.seekToDefaultPosition(index); player.prepare(); player.play() }
                waitFor("READY ${track.id}") {
                    player.currentMediaItem?.mediaId == track.id && player.isPlaying && player.playbackState == Player.STATE_READY &&
                        player.duration > 0 && player.currentPosition > 500
                }
                val start = main { player.currentPosition }
                Thread.sleep(900)
                check(main { player.currentPosition > start + 200 }) { "Playback position did not advance" }
                main { player.pause() }
                Thread.sleep(200)
                val paused = main { player.currentPosition }
                Thread.sleep(400)
                check(main { !player.isPlaying && kotlin.math.abs(player.currentPosition - paused) < 100 }) { "Pause failed" }
                main { player.seekTo(30000); player.play() }
                waitFor("seek ${track.id}") { player.currentPosition >= 29000 && player.isPlaying }
                waitFor("manager projection ${track.id}", 5000) {
                    app.playbackManager.uiState.value.currentTrack?.id == track.id &&
                        app.playbackManager.uiState.value.currentTrack?.thumbnailUri == track.thumbnailUri
                }
                val historyRecorded = runBlocking {
                    withTimeout(5_000) {
                        app.database.trackDao().getRecentHistory().first { rows ->
                            rows.any { it.trackId == track.id && it.title == track.title && it.artist == track.artist }
                        }
                    }
                }
                check(historyRecorded.any { it.trackId == track.id }) { "Started track was not written to Room history" }
                report("videoId=${track.id} client=ANDROID_VR/1.65.10 Media3=READY position=advancing pause=PASS seek=PASS history=PASS artworkMetadata=${track.thumbnailUri != null}")
            }
            main { player.seekToDefaultPosition(0); player.play() }
            waitFor("first item") { player.currentMediaItemIndex == 0 && player.isPlaying }
            main { player.seekToNextMediaItem() }
            waitFor("next") { player.currentMediaItemIndex == 1 && player.isPlaying }
            main { player.seekToPreviousMediaItem() }
            waitFor("previous") { player.currentMediaItemIndex == 0 && player.isPlaying }
            report("Next=PASS Previous=PASS replay=PASS uiProjection=PASS muted=true audioFocus=false")
            passed = true
        } catch (e: Throwable) {
            report("FAIL type=${e.javaClass.simpleName} detail=${if (e is AssertionError || e is IllegalStateException) e.message else "see sanitized playback diagnostic status"}")
        } finally {
            controller?.let { player -> main {
                if (touched) {
                    player.pause()
                    player.stop()
                    if (saved.isEmpty()) player.clearMediaItems()
                    else player.setMediaItems(saved, savedIndex.coerceIn(0, saved.lastIndex), savedPosition)
                    player.shuffleModeEnabled = savedShuffle
                    player.repeatMode = savedRepeat
                    player.volume = savedVolume
                    player.setAudioAttributes(savedAttributes, true)
                }
                player.release()
            } }
        }
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", if (passed) "PASS: real MediaSession playback; audible sound intentionally not checked.\n" else "FAIL: guest playback probe\n") })
    }
}
