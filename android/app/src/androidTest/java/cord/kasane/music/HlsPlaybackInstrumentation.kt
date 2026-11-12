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
    private var liveOnly = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); liveOnly = arguments?.getString("live") == "true"; start() }
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
        try {
            val stream = kotlinx.coroutines.runBlocking {
                cord.kasane.music.provider.HlsPlayerResolver(okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()).resolve("dQw4w9WgXcQ")
            }
            if (stream !is HlsStream) {
                finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "PUBLIC HLS unavailable for dQw4w9WgXcQ; no playable manifest supplied.\n") })
                return
            }
            player = main {
                val network = DefaultHttpDataSource.Factory()
                ExoPlayer.Builder(targetContext).build().apply {
                    volume = 0f
                    setMediaSource(TransportMediaSource(MediaItem.Builder().setMediaId("dQw4w9WgXcQ")
                        .setUri("kasane://track/dQw4w9WgXcQ").build(), { stream }, DefaultMediaSourceFactory(network), network))
                    prepare(); play()
                }
            }
            val p = player!!
            waitFor("public full playback", 280000) { p.playbackState == Player.STATE_ENDED || p.playerError != null }
            main { check(p.playerError == null); check(p.currentPosition > 200000); check(p.playbackState == Player.STATE_ENDED) }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PUBLIC HLS full Media3 playback passed: dQw4w9WgXcQ, IOS, position=${main { p.currentPosition }}ms\n") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "PUBLIC HLS failed: ${e.javaClass.simpleName}${if (e is java.io.IOException) ": ${e.message}" else ""}\n") })
        } finally { main { player?.release() } }
    }
    override fun onStart() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator required" }
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
                val progressive = DefaultMediaSourceFactory(network)
                ExoPlayer.Builder(targetContext).setMediaSourceFactory(
                    DefaultMediaSourceFactory(network).setLoadErrorHandlingPolicy(BoundedStreamLoadPolicy())).build().apply {
                    volume = 0f
                    setMediaSource(TransportMediaSource(
                        MediaItem.Builder().setMediaId("fixture-1").setUri("kasane://track/fixture-1").build(),
                        { HlsStream("http://127.0.0.1:${server.localPort}/manifest") }, progressive, network))
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
