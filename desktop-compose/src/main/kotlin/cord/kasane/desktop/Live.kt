package cord.kasane.desktop

import cord.kasane.music.model.CatalogKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.io.path.createTempDirectory

/**
 * `--live [--accept]`: the real desktop stack (mDNS, mutual TLS, pairing, player, mpv with no sound) without a
 * window, driven from stdin so a real phone can be tested against it. `--accept` stands in for the user's
 * single confirmation on this device. Commands: devices | pair ID | search QUERY | state | cmd ID ACTION [JSON]
 * | transfer ID|local | unpair ID | quit.
 */
fun runLive(args: List<String>): Int {
    val accept = "--accept" in args
    val root = args.indexOf("--data").takeIf { it >= 0 }?.let { java.io.File(args[it + 1]) } ?: createTempDirectory("kasane-live").toFile()
    val app = DesktopApp(audioOutput = "null", root = root)
    val connect = app.connect ?: run { println("connect failed: ${app.connectError}"); return 1 }
    println("live: this device ${connect.identity.deviceName} id=${connect.identity.deviceId} port=${connect.manager.port} accept=$accept")
    app.scope.launch {
        var last = ""
        connect.manager.devices.collect { list ->
            val text = list.joinToString { "${it.name}[${it.platform}/${it.state}${it.lastError?.let { e -> " err=$e" } ?: ""}]" }
            if (text != last) { last = text; println("devices: ${text.ifEmpty { "(none)" }}") }
        }
    }
    app.scope.launch {
        val seen = mutableSetOf<String>()
        connect.manager.prompts.collect { prompts ->
            for (p in prompts) if (seen.add(p.promptId)) {
                println("prompt: ${if (p.incoming) "incoming" else "outgoing(awaitingPeer=${p.awaitingPeer})"} peer=${p.peerName} code=${p.code}")
                if (accept && p.incoming) { println("prompt: confirming once on this device"); connect.manager.confirmPairing(p.promptId, true) }
            }
        }
    }
    val remoteLog = app.scope.launch {
        var last = ""
        connect.manager.remoteStates.collect { m ->
            for ((id, f) in m) {
                val line = "${f.playback} ${f.track?.title}/${f.track?.artist} pos=${f.positionMs / 1000}s vol=${f.volume} q=${f.queue?.size} idx=${f.queueIndex}"
                if (line != last) { last = line; println("remote[$id]: $line") }
            }
        }
    }
    val local = app.scope.launch {
        var last = ""
        app.player.state.collect { s ->
            val line = "${if (s.isPlaying) "playing" else "paused"} ${s.current?.title} pos=${s.positionSec.toInt()}s idx=${s.index} q=${s.queue.size} vol=${"%.2f".format(s.volume)} err=${s.error}"
            if (line != last && (s.positionSec.toInt() % 10 == 0 || s.error != null || !line.startsWith(last.substringBefore(" pos")))) { last = line; println("local: $line") }
        }
    }
    val json = Json
    runBlocking {
        for (line in generateSequence(::readLine)) {
            val parts = line.trim().split(' ', limit = 3)
            try {
                when (parts[0]) {
                    "devices" -> connect.manager.devices.value.forEach { println("  ${it.id} ${it.name} ${it.platform} ${it.state} addr=${it.addr} trusted=${it.trusted}") }
                    "pair" -> { connect.manager.pair(parts[1]); println("pair: started") }
                    "search" -> {
                        val q = line.substringAfter(' ')
                        val t = app.catalog.search(q, CatalogKind.SONG, null).tracks.first()
                        app.player.playTrack(t); println("search: playing ${t.title} — ${t.artist}")
                    }
                    "state" -> println("state: ${app.player.state.value.let { "${it.current?.title} playing=${it.isPlaying} pos=${it.positionSec}" }}")
                    "cmd" -> {
                        val rest = parts.getOrNull(2)?.split(' ', limit = 2)
                        val args2 = rest?.getOrNull(1)?.let { json.parseToJsonElement(it) as JsonObject } ?: JsonObject(emptyMap())
                        connect.manager.sendCommand(parts[1], rest?.get(0) ?: error("cmd ID ACTION"), args2); println("cmd: ack ok")
                    }
                    "transfer" -> { connect.remote.transferTo(parts[1].takeIf { it != "local" }); println("transfer: requested") }
                    "unpair" -> { connect.manager.unpair(parts[1]); println("unpair: done") }
                    "quit" -> break
                    "" -> {}
                    else -> println("unknown: ${parts[0]}")
                }
            } catch (e: Exception) { println("error: ${e::class.simpleName}: ${e.message}") }
        }
    }
    remoteLog.cancel(); local.cancel()
    app.close()
    return 0
}

