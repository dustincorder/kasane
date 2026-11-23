package cord.kasane.desktop

import cord.kasane.music.model.CatalogKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory

/**
 * `--connect-smoke`: two desktop instances in one process, each with its own
 * identity, trust store, player and mpv (no sound). Exercises pairing with
 * codes on both sides, remote state, the current-device rule and a one-tap
 * transfer, plus rejection of an unpaired peer. Prints facts, returns 0 on success.
 */
fun runConnectSmoke(): Int {
    val a = DesktopApp(audioOutput = "null", root = createTempDirectory("kasane-a").toFile(), autoStartConnect = false)
    val b = DesktopApp(audioOutput = "null", root = createTempDirectory("kasane-b").toFile(), autoStartConnect = false)
    val c = DesktopApp(audioOutput = "null", root = createTempDirectory("kasane-c").toFile(), autoStartConnect = false)
    try {
        val ca = a.connect ?: error("A: ${a.connectError}")
        val cb = b.connect ?: error("B: ${b.connectError}")
        val cc = c.connect ?: error("C: ${c.connectError}")
        println("identities: A=${ca.identity.fingerprintHex.take(12)} B=${cb.identity.fingerprintHex.take(12)} (self-signed P-256, distinct=${ca.identity.fingerprintHex != cb.identity.fingerprintHex})")
        ca.manager.start(); cb.manager.start(); cc.manager.start()
        println("listening: A=${ca.manager.port} B=${cb.manager.port} C=${cc.manager.port}")
        return runBlocking {
            // Both sides confirm after comparing: the test checks the codes match, as a person would.
            val codes = linkedMapOf<String, String>()
            val confirmer = launch {
                while (true) {
                    for ((side, m) in listOf("A" to ca.manager, "B" to cb.manager)) m.prompts.value.forEach { p ->
                        if (codes.put(p.promptId, "$side ${if (p.incoming) "incoming" else "outgoing"} ${p.code}") == null) m.confirmPairing(p.promptId, true)
                    }
                    delay(100)
                }
            }
            withTimeout(30_000) { cb.manager.pair("127.0.0.1:${ca.manager.port}") }
            withTimeout(30_000) {
                ca.manager.devices.first { d -> d.any { it.id == cb.identity.deviceId && it.trusted } }
                cb.manager.devices.first { d -> d.any { it.id == ca.identity.deviceId && it.trusted } }
            }
            confirmer.cancel()
            val distinctCodes = codes.values.map { it.substringAfterLast(' ') }.toSet()
            println("pairing prompts: ${codes.values} -> same code on both sides: ${codes.size == 2 && distinctCodes.size == 1}; mutual trust stored")
            val track = a.catalog.search("Daft Punk Around the World", CatalogKind.SONG, null).tracks.first()
            a.player.playTrack(track)
            withTimeout(30_000) { a.player.state.first { it.isPlaying && it.positionSec > 1 } }
            cb.manager.connect(ca.identity.deviceId)
            val seen = withTimeout(15_000) { cb.manager.remoteStates.first { it[ca.identity.deviceId]?.playback == "playing" } }[ca.identity.deviceId]!!
            withTimeout(5_000) { cb.remote.target.first { it == ca.identity.deviceId } }
            println("B sees A: ${seen.track?.title} — ${seen.track?.artist} at ${seen.positionMs / 1000}s, queue=${seen.queue?.size}; B's current device = A")
            cb.remote.send("seek", kotlinx.serialization.json.buildJsonObject { put("positionMs", kotlinx.serialization.json.JsonPrimitive(90_000)) })
            delay(1_500)
            println("B seeks A to 90s: A at ${"%.1f".format(a.player.state.value.positionSec)}s")
            val before = a.player.state.value.positionSec
            cb.remote.transferTo(null)
            val moved = withTimeout(30_000) { b.player.state.first { it.isPlaying && it.positionSec > 0 } }
            delay(1_000)
            val aAfter = a.player.state.value
            println("transfer to B: B plays ${moved.current?.title} from ${"%.1f".format(moved.positionSec)}s (A was at ${"%.1f".format(before)}s); A playing=${aAfter.isPlaying}; B current=${cb.remote.target.value ?: "this computer"}")
            val unpaired = runCatching { withTimeout(15_000) { cc.manager.sendCommand(ca.identity.deviceId.let { "127.0.0.1:${ca.manager.port}" }, "pause") } }
            println("unpaired C -> A pause: ${unpaired.exceptionOrNull()?.let { it::class.simpleName + ": " + it.message } ?: "ACCEPTED (bad)"}; A still paused=${!a.player.state.value.isPlaying}")
            val rust = System.getenv("KASANE_RUST_CLI")?.let { cli -> rustInterop(cli, a, ca) }
            val ok = rust != false && codes.size == 2 && distinctCodes.size == 1 && seen.track?.id == track.id && moved.isPlaying &&
                kotlin.math.abs(moved.positionSec - before) < 15 && !aAfter.isPlaying && unpaired.isFailure
            if (ok) 0 else 1
        }
    } finally {
        listOf(a, b, c).forEach { runCatching { it.connect?.manager?.stop(); it.close() } }
    }
}


/** Rust CLI (rustls) pairs with desktop instance A and pauses it: proves the certificate and protocol interoperate. */
private suspend fun rustInterop(cli: String, a: DesktopApp, ca: cord.kasane.desktop.connect.DesktopConnect): Boolean {
    val dir = createTempDirectory("kasane-rust").toFile()
    val seen = mutableListOf<String>()
    val confirmer = kotlinx.coroutines.GlobalScope.launch {
        while (true) {
            ca.manager.prompts.value.forEach { p -> if (p.promptId !in seen) { seen += p.promptId; seen += p.code; ca.manager.confirmPairing(p.promptId, true) } }
            delay(100)
        }
    }
    fun run(vararg args: String, input: String = ""): String {
        val pb = ProcessBuilder(listOf(cli, "--data-dir", dir.path) + args).redirectErrorStream(true)
        pb.environment()["KC_MDNS"] = "0"
        val p = pb.start()
        p.outputStream.use { it.write(input.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }
    return try {
        a.player.toggle().also { delay(1_500) }
        val target = "127.0.0.1:${ca.manager.port}"
        val pairOut = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { run("pair", target, input = "y\n") }
        val rustCode = Regex("is the code (\\d{6})").find(pairOut)?.groupValues?.get(1)
        delay(1_000)
        val playingBefore = a.player.state.value.isPlaying
        val cmdOut = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { run("cmd", target, "pause") }
        delay(800)
        val paused = !a.player.state.value.isPlaying
        println("rust interop: Rust code=$rustCode desktop code=${seen.getOrNull(1)} match=${rustCode != null && rustCode == seen.getOrNull(1)}; " +
            "Rust 'pause' -> ${cmdOut.lines().lastOrNull { it.startsWith("ack") }}; desktop was playing=$playingBefore now paused=$paused")
        rustCode != null && rustCode == seen.getOrNull(1) && playingBefore && paused && cmdOut.contains("ack ok")
    } finally {
        confirmer.cancel()
        dir.deleteRecursively()
    }
}
