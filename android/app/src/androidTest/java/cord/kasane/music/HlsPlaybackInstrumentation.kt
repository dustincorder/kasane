package cord.kasane.music

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import cord.kasane.music.playback.TransportMediaSource
import cord.kasane.music.playback.BoundedStreamLoadPolicy
import cord.kasane.music.provider.HlsStream
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/** Synthetic tone only, disposable emulator only. No account/network/production package access. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HlsPlaybackInstrumentation : Instrumentation() {
    private var radioOnly = false
    private var liveOnly = false
    private var liveVideoId = "dQw4w9WgXcQ"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); liveOnly = arguments?.getString("live") == "true"
        radioOnly = arguments?.getString("radio") == "true"
        arguments?.getString("videoId")?.let { require(it.matches(Regex("[A-Za-z0-9_-]{11}"))); liveVideoId = it }
        start() }
    private fun <T> main(block: () -> T): T {
        var value: T? = null; var error: Throwable? = null
        runOnMainSync { try { value = block() } catch (e: Throwable) { error = e } }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun waitFor(label: String, timeout: Long = 20000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            if (main(condition)) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out: $label")
    }
    private fun runPublicHls() {
        var player: ExoPlayer? = null
        var activity: Activity? = null
        try {
            activity = startActivitySync(android.content.Intent(targetContext, cord.kasane.music.playback.PlaybackProbeActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val stream = kotlinx.coroutines.runBlocking {
                cord.kasane.music.provider.HlsPlayerResolver(okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()).resolve(liveVideoId)
            }
            if (stream !is HlsStream) {
                finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "PUBLIC HLS unavailable for $liveVideoId; no playable manifest supplied.\n") })
                return
            }
            player = main {
                val network = DefaultHttpDataSource.Factory()
                ExoPlayer.Builder(targetContext).build().apply {
                    volume = 0f
                    trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                    setMediaSource(TransportMediaSource(MediaItem.Builder().setMediaId(liveVideoId)
                        .setUri("kasane://track/$liveVideoId").build(), { stream }, network))
                    prepare(); play()
                }
            }
            val p = player!!
            waitFor("public audio decode", 40000) { p.isPlaying || p.playerError != null }
            main { check(p.playerError == null) }
            sendStatus(0, Bundle().apply { putString("stream", "PUBLIC HLS Media3 started: $liveVideoId\n") })
            waitFor("public full playback", 280000) { p.playbackState == Player.STATE_ENDED || p.playerError != null }
            main { check(p.playerError == null); check(p.duration > 60000 && p.currentPosition >= p.duration - 300); check(p.playbackState == Player.STATE_ENDED) }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PUBLIC HLS full Media3 playback passed: $liveVideoId, IOS, position=${main { p.currentPosition }}ms\n") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "PUBLIC HLS failed: ${e.javaClass.simpleName}${if (e is java.io.IOException) ": ${e.message}" else ""}\n") })
        } finally { main { player?.release(); activity?.finish() } }
    }
    private fun runRadioPrefetch() {
        var controller: androidx.media3.session.MediaController? = null
        var activity: Activity? = null
        try {
            activity = startActivitySync(android.content.Intent(targetContext, cord.kasane.music.playback.PlaybackProbeActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val future = main {
                androidx.media3.session.MediaController.Builder(targetContext, androidx.media3.session.SessionToken(targetContext,
                    android.content.ComponentName(targetContext, cord.kasane.music.playback.PlaybackService::class.java))).buildAsync()
            }
            controller = future.get(30, java.util.concurrent.TimeUnit.SECONDS)
            val p = controller!!
            main {
                p.volume = 0f
                p.setMediaItems(listOf(cord.kasane.music.playback.PlaybackService.trackToMediaItem(
                    cord.kasane.music.model.Track("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley"), radioMode = "single")))
                p.addMediaItem(cord.kasane.music.playback.PlaybackService.trackToMediaItem(
                    cord.kasane.music.model.Track("wzpAehzNCU8", "Brand New Day", "BLits Games")))
                p.prepare(); p.play()
            }
            waitFor("radio prefetch before track end", 45000) { p.mediaItemCount > 2 }
            main {
                val ids = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
                check(ids.first() == "dQw4w9WgXcQ")
                check(ids[1] == "wzpAehzNCU8")
                check(ids.size == ids.distinct().size)
                check(p.currentPosition < 45000)
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "RADIO PREFETCH PASSED: queue=${main { p.mediaItemCount }} manual-preserved=true deduplicated=true before-end=true\n") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "RADIO PREFETCH FAILED: ${e.javaClass.simpleName}\n") })
        } finally { main { controller?.stop(); controller?.clearMediaItems(); controller?.release(); activity?.finish() } }
    }
    override fun onStart() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator required" }
        if (radioOnly) { runRadioPrefetch(); return }
        if (liveOnly) { runPublicHls(); return }
        val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val requests = Collections.synchronizedList(mutableListOf<String>())
        var player: ExoPlayer? = null
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use {
                    val input = it.getInputStream().bufferedReader()
                    val path = input.readLine()?.split(' ')?.getOrNull(1)?.removePrefix("/") ?: ""
                    var line = input.readLine()
                    while (!line.isNullOrEmpty()) line = input.readLine()
                    requests.add(path)
                    if (path == "drop.ts") return@use
                    val status = if (path.startsWith("forbidden")) 403 else if (path == "missing.m3u8") 404 else 200
                    val bytes = if (status == 200) context.assets.open("hls/${if (path == "manifest") "master.m3u8" else path}").use { asset -> asset.readBytes() } else byteArrayOf()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 $status Result\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }
        fun report(text: String) = sendStatus(0, Bundle().apply { putString("stream", "$text\n") })
        try {
            check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator required" }
            player = main {
                val network = DefaultHttpDataSource.Factory()
                ExoPlayer.Builder(targetContext).setMediaSourceFactory(
                    DefaultMediaSourceFactory(network).setLoadErrorHandlingPolicy(BoundedStreamLoadPolicy())).build().apply {
                    volume = 0f
                    setMediaSource(TransportMediaSource(
                        MediaItem.Builder().setMediaId("fixture-1").setUri("kasane://track/fixture-1").build(),
                        { HlsStream("http://127.0.0.1:${server.localPort}/manifest") }, network))
                    prepare(); play()
                }
            }
            val p = player!!
            waitFor("initial decoding") { p.isPlaying && p.currentPosition > 1000 }
            main {
                check(p.audioFormat != null)
                check((p.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0)
            }
            waitFor("cross 60 seconds", 85000) { p.currentPosition >= 61000 || p.playerError != null }
            main { check(p.playerError == null); check(p.currentPosition >= 61000) }
            report("PASS decoded through 61 seconds")
            waitFor("full EOF", 25000) { p.playbackState == Player.STATE_ENDED || p.playerError != null }
            main { check(p.playerError == null); check(p.playbackState == Player.STATE_ENDED); check(p.currentPosition >= 74900) }
            report("PASS full 75-second HLS playback EOF")
            main { p.seekTo(35000); p.play() }
            waitFor("seek middle") { p.isPlaying && p.currentPosition in 35000..45000 }
            main { p.pause() }
            val paused = main { p.currentPosition }
            Thread.sleep(500)
            main { check(kotlin.math.abs(p.currentPosition - paused) < 150); p.play() }
            waitFor("resume") { p.isPlaying && p.currentPosition > paused + 300 }
            main { p.seekTo(60000) }
            waitFor("seek forward") { p.isPlaying && p.currentPosition in 60000..70000 }
            report("PASS seek backward / forward / pause / resume")
            main {
                p.addMediaItem(MediaItem.Builder().setMediaId("fixture-2").setUri("http://127.0.0.1:${server.localPort}/media.m3u8").setMimeType(MimeTypes.APPLICATION_M3U8).build())
                check(p.mediaItemCount == 2)
                check(p.getMediaItemAt(0).mediaId == "fixture-1")
                check(p.getMediaItemAt(0).localConfiguration!!.uri.scheme == "kasane")
                p.seekToNextMediaItem()
            }
            waitFor("next track") { p.currentMediaItem?.mediaId == "fixture-2" && p.isPlaying }
            check(requests.any { it == "segment10.ts" })
            report("PASS next track / stable identity / queue; requests=${requests.size}")
            for (path in listOf("missing.m3u8", "segment-error.m3u8", "network-error.m3u8")) {
                main {
                    p.setMediaItems(listOf(
                        MediaItem.Builder().setMediaId("error").setUri("http://127.0.0.1:${server.localPort}/$path").setMimeType(MimeTypes.APPLICATION_M3U8).build(),
                        MediaItem.Builder().setMediaId("retained").setUri("http://127.0.0.1:${server.localPort}/media.m3u8").setMimeType(MimeTypes.APPLICATION_M3U8).build()))
                    p.prepare(); p.play()
                }
                waitFor("bounded $path error") { p.playerError != null }
                main { check(p.mediaItemCount == 2); check(p.currentMediaItem?.mediaId == "error") }
                Thread.sleep(500)
                check(requests.count { it == path } <= 2)
                if (path == "segment-error.m3u8") check(requests.count { it == "forbidden.ts" } == 1)
                if (path == "network-error.m3u8") check(requests.count { it == "drop.ts" } in 1..6)
                report("PASS $path error is bounded; queue retained")
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "HLS playback passed\n") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "HLS FAILED: ${e.javaClass.simpleName}: ${e.message}\n") })
        } finally { main { player?.release() }; server.close() }
    }
}
