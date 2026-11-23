package cord.kasane.music.connect

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

private class InteropAdapter : ConnectPlayerAdapter {
    val executed = java.util.concurrent.CopyOnWriteArrayList<String>()
    var state = PlayerSnapshot(
        playback = "paused",
        track = TrackMeta("t1", "Song One", "Artist"),
        queue = listOf(TrackMeta("t1", "Song One", "Artist"), TrackMeta("t2", "Song Two", "Artist")),
        queueIndex = 0,
        durationMs = 200_000,
        volume = 0.7f,
    )
    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    private val _changed = MutableStateFlow(0L)
    override val changed: StateFlow<Long> = _changed
    override suspend fun snapshot() = state
    override suspend fun execute(action: String, args: JsonObject) {
        executed += action
        state = when (action) {
            "play" -> state.copy(playback = "playing")
            "pause" -> state.copy(playback = "paused")
            "playQueueIndex" -> state.copy(playback = "playing")
            else -> state
        }
        _changed.value = counter.incrementAndGet()
    }
    override suspend fun handoff(request: Frame.Handoff) {
        state = state.copy(
            queue = request.queue,
            queueIndex = request.index.toLong(),
            playback = if (request.playing) "playing" else "paused",
        )
        _changed.value = counter.incrementAndGet()
    }
    override fun capabilities() = listOf("seek", "queue", "handoff")
}

/**
 * Kotlin <-> Rust interoperability over real TLS sockets on this host.
 * Opt-in: set KASANE_RUST_CLI to a built `kasane-connect-cli` binary.
 */
class ConnectInteropTest {
    private val cli: String? = System.getenv("KASANE_RUST_CLI")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var work: File
    private val processes = mutableListOf<Process>()
    private val logs = mutableListOf<File>()
    private fun logOf(p: Process) = logs[processes.indexOf(p)].readText()

    @Before
    fun setUp() {
        assumeTrue("KASANE_RUST_CLI not set", cli != null && File(cli).canExecute())
        work = java.nio.file.Files.createTempDirectory("connect-interop").toFile()
    }

    @After
    fun tearDown() {
        processes.forEach { it.destroyForcibly() }
        scope.cancel()
        if (::work.isInitialized) work.deleteRecursively()
    }

    private fun fixture(name: String) = ConnectIdentities.fromPkcs12(
        requireNotNull(javaClass.getResource("/connect/$name")).readBytes(),
        "kasane".toCharArray(), UUID.randomUUID().toString(), name)

    private fun manager(adapter: ConnectPlayerAdapter) = ConnectManager(ConnectConfig(
        identity = fixture("test-identity.p12"), adapter = adapter,
        trustFile = File(work, "kotlin-trusted.json"), appVersion = "interop",
        platform = "android", scope = scope, listenPort = 0))

    private fun rust(vararg args: String, stdin: String? = null): Process {
        val pb = ProcessBuilder(listOf(cli!!, "--data-dir", File(work, "rust").path) + args)
        pb.environment()["KC_MDNS"] = "0"
        val log = File(work, "rust-${processes.size}.log")
        pb.redirectErrorStream(true).redirectOutput(log)
        logs += log
        return pb.start().also { p ->
            processes += p
            stdin?.let { p.outputStream.write(it.toByteArray()); p.outputStream.flush() }
        }
    }

    private fun finish(p: Process, seconds: Long = 20): Pair<Int, String> {
        assertTrue("rust cli timed out", p.waitFor(seconds, TimeUnit.SECONDS))
        return p.exitValue() to logOf(p)
    }

    private suspend fun awaitTrue(timeoutMs: Long = 15_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (check()) return; delay(25) }
        error("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun rustControllerPairsAndControlsKotlinPlayer() = runBlocking {
        val adapter = InteropAdapter()
        val kotlin = manager(adapter)
        kotlin.start()
        try {
            val addr = "127.0.0.1:${kotlin.port}"
            val pair = rust("pair", addr, stdin = "y\n")
            runCatching { awaitTrue { kotlin.prompts.value.isNotEmpty() } }.onFailure {
                pair.destroy(); pair.waitFor(5, TimeUnit.SECONDS); error("no prompt; rust said: " + logOf(pair))
            }
            delay(3000)
            kotlin.confirmPairing(kotlin.prompts.value.first().promptId, true)
            val (code, out) = finish(pair)
            assertEquals(out, 0, code)
            assertTrue(out, out.contains("paired:"))

            val (pauseCode, pauseOut) = finish(rust("cmd", addr, "pause"))
            assertEquals(pauseOut, 0, pauseCode)
            assertTrue(adapter.executed.contains("pause"))
            val (seekCode, seekOut) = finish(rust("cmd", addr, "seek", "{\"positionMs\":61000}"))
            assertEquals(seekOut, 0, seekCode)

            val log = File(work, "state.log")
            val state = ProcessBuilder(cli!!, "--data-dir", File(work, "rust").path, "state", addr)
                .redirectErrorStream(true).redirectOutput(log)
                .apply { environment()["KC_MDNS"] = "0" }.start().also { processes += it }
            delay(3000)
            state.destroy()
            state.waitFor(5, TimeUnit.SECONDS)
            val stateOut = log.readText()
            assertTrue(stateOut, stateOut.contains("\"title\":\"Song One\""))
        } finally {
            kotlin.stop()
        }
    }

    @Test
    fun kotlinControllerPairsAndControlsRustPlayer() = runBlocking {
        val serve = rust("serve", "--auto-accept")
        var port: String? = null
        awaitTrue { port = Regex("port=(\\d+)").find(logOf(serve))?.groupValues?.get(1); port != null }
        assertNotNull(port)
        val kotlin = manager(InteropAdapter())
        kotlin.start()
        try {
            val promptId = runCatching { kotlin.pair("127.0.0.1:$port") }
                .getOrElse { error("pair failed: $it; rust said: " + logOf(serve)) }
            assertNotNull(promptId)
            kotlin.confirmPairing(promptId!!, true)
            awaitTrue { kotlin.devices.value.any { it.connection == "paired" } }
            val id = kotlin.devices.value.first { it.connection == "paired" }.id!!
            kotlin.sendCommand(id, "pause")
            awaitTrue { kotlin.remoteStates.value[id]?.playback == "paused" }
            kotlin.sendCommand(id, "play")
            awaitTrue { kotlin.remoteStates.value[id]?.playback == "playing" }
        } finally {
            kotlin.stop()
        }
    }
}
