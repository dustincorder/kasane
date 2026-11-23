package cord.kasane.desktop

import cord.kasane.music.model.CatalogKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * `--smoke [query]`: the real end-to-end flow without a window or sound
 * (mpv `--ao=null`): Home shelves, search, stream resolution, decoding,
 * position advance, next track. Prints facts only, never URLs.
 */
fun runSmoke(query: String): Int = DesktopApp(audioOutput = "null", root = kotlin.io.path.createTempDirectory("kasane-smoke").toFile(), autoStartConnect = false).use { app ->
    runBlocking {
        val home = app.catalog.home(null)
        println("home: ${home.shelves.size} shelves, first: ${home.shelves.take(3).joinToString { "\"${it.title}\" (${it.items.size})" }}")
        val results = app.catalog.search(query, CatalogKind.SONG, null).tracks
        println("search \"$query\": ${results.size} songs, first: ${results.firstOrNull()?.let { "${it.title} — ${it.artist} [${it.id}] art=${it.thumbnailUri != null}" }}")
        val track = results.firstOrNull() ?: return@runBlocking 1.also { println("FAIL no results") }
        app.player.playTrack(track)
        val playing = runCatching { withTimeout(25_000) { app.player.state.first { it.isPlaying && it.positionSec > 0.5 || it.error != null } } }.getOrNull()
        if (playing == null || playing.error != null) return@runBlocking 1.also { println("FAIL playback: ${playing?.error ?: "timeout"}") }
        delay(3_000)
        val s = app.player.state.value
        println("playing: ${s.current?.title} pos=${"%.1f".format(s.positionSec)}s dur=${"%.0f".format(s.durationSec)}s queue=${s.queue.size} (radio appended)")
        app.player.seek(60.0); delay(1_500)
        println("after seek(60): pos=${"%.1f".format(app.player.state.value.positionSec)}s")
        app.player.toggle(); delay(800)
        val paused = app.player.state.value
        println("after pause: playing=${paused.isPlaying}")
        app.player.toggle()
        app.player.next()
        val nextState = runCatching { withTimeout(25_000) { app.player.state.first { it.index == 1 && it.isPlaying && it.positionSec > 0.5 || it.error != null } } }.getOrNull()
        println("next: ${nextState?.current?.title ?: "-"} — ${nextState?.current?.artist ?: ""} playing=${nextState?.isPlaying} error=${nextState?.error}")
        if (nextState?.isPlaying == true && !paused.isPlaying) 0 else 1
    }
}

/** `--play QUERY... SECONDS`: play the first song result headless and print the position every 5 s. */
fun runPlay(args: List<String>): Int = DesktopApp(audioOutput = "null", root = kotlin.io.path.createTempDirectory("kasane-play").toFile(), autoStartConnect = false).use { app ->
    val seconds = args.last().toIntOrNull() ?: 60
    val query = args.dropLast(1).joinToString(" ")
    runBlocking {
        val t = app.catalog.search(query, CatalogKind.SONG, null).tracks.first()
        app.player.playTrack(t)
        repeat(seconds / 5) {
            delay(5_000)
            val s = app.player.state.value
            println("t=${(it + 1) * 5}s ${s.current?.title} idx=${s.index} pos=${"%.1f".format(s.positionSec)} playing=${s.isPlaying} source=${app.player.lastSource} err=${s.error} note=${app.player.lastSourceNote}")
        }
    }
    0
}
