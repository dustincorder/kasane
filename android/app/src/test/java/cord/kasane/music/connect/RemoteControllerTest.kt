package cord.kasane.music.connect

import cord.kasane.music.model.Track
import cord.kasane.music.playback.PlaybackUiState
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** One fake for both seams: the local player ([LocalPlayback]) and the manager's adapter —
 * exactly how production binds AndroidPlayerAdapter and RemoteController to one PlaybackManager. */
private class FakePlayback : LocalPlayback, ConnectPlayerAdapter {
    private val _uiState = MutableStateFlow(PlaybackUiState())
    override val uiState: StateFlow<PlaybackUiState> = _uiState
    val handoffs = java.util.concurrent.CopyOnWriteArrayList<Pair<List<Track>, Long>>() // tracks to positionMs
    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    private val _changed = MutableStateFlow(0L)
    override val changed: StateFlow<Long> = _changed
    private fun bump() { _changed.value = counter.incrementAndGet() }

    fun setPlaying(queue: List<Track>, index: Int, positionMs: Long) {
        _uiState.value = _uiState.value.copy(
            currentTrack = queue.getOrNull(index), isPlaying = true,
            queue = queue, queueIndex = index, currentPositionMs = positionMs)
        bump()
    }

    // ---- LocalPlayback ----------------------------------------------------
    override suspend fun handoffQueue(tracks: List<Track>, index: Int, positionMs: Long,
        playing: Boolean, shuffle: Boolean, repeatMode: Int): Boolean {
        handoffs += tracks to positionMs
        _uiState.value = _uiState.value.copy(
            currentTrack = tracks.getOrNull(index), isPlaying = playing,
            queue = tracks, queueIndex = index, currentPositionMs = positionMs)
        bump()
        return true
    }

    override fun pause() {
        _uiState.value = _uiState.value.copy(isPlaying = false)
        bump()
    }

    // ---- ConnectPlayerAdapter ----------------------------------------------
    override suspend fun snapshot() = uiState.value.let { ui ->
        PlayerSnapshot(
            playback = if (ui.isPlaying) "playing" else if (ui.currentTrack != null) "paused" else "idle",
            track = ui.currentTrack?.toMeta(),
            positionMs = ui.currentPositionMs, durationMs = ui.durationMs,
            queue = ui.queue.map { it.toMeta() }, queueIndex = ui.queueIndex.toLong(),
            shuffle = ui.shuffleEnabled,
            repeat = when (ui.repeatMode) {
                androidx.media3.common.Player.REPEAT_MODE_ALL -> "all"
                androidx.media3.common.Player.REPEAT_MODE_ONE -> "one"
                else -> "off"
            },
        )
    }

    override suspend fun execute(action: String, args: JsonObject) {
        when (action) {
            "pause" -> pause()
            "play" -> {
                _uiState.value = _uiState.value.copy(isPlaying = _uiState.value.currentTrack != null)
                bump()
            }
            else -> {}
        }
    }

    override suspend fun handoff(request: Frame.Handoff) {
        handoffQueue(request.queue.map { it.toTrack() }, request.index, request.positionMs,
            request.playing, request.shuffle, androidx.media3.common.Player.REPEAT_MODE_OFF)
    }

    override fun capabilities() = listOf("seek", "queue", "handoff")
}

private class RecordingAdapter : ConnectPlayerAdapter {
    val handoffs = java.util.concurrent.CopyOnWriteArrayList<Frame.Handoff>()
    var state = PlayerSnapshot(playback = "idle")
    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    private val _changed = MutableStateFlow(0L)
    override val changed: StateFlow<Long> = _changed
    override suspend fun snapshot() = state
    override suspend fun execute(action: String, args: JsonObject) {
        state = when (action) {
            "play" -> state.copy(playback = "playing")
            "pause" -> state.copy(playback = "paused")
            else -> state
        }
        _changed.value = counter.incrementAndGet()
    }
    override suspend fun handoff(request: Frame.Handoff) {
        handoffs += request
        state = state.copy(
            queue = request.queue, queueIndex = request.index.toLong(),
            positionMs = request.positionMs,
            playback = if (request.playing) "playing" else "paused",
        )
        _changed.value = counter.incrementAndGet()
    }
    override fun capabilities() = listOf("seek", "queue", "handoff")
}

/** Remote-command application over a real wire loopback: two managers, one RemoteController. */
class RemoteControllerTest {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private lateinit var server: ConnectManager
    private lateinit var client: ConnectManager
    private lateinit var serverAdapter: RecordingAdapter
    private lateinit var playback: FakePlayback
    private lateinit var remote: RemoteController
    private lateinit var serverDir: java.io.File
    private lateinit var clientDir: java.io.File

    private fun fixture(name: String): ConnectIdentity =
        ConnectIdentities.fromPkcs12(
            requireNotNull(javaClass.getResource("/connect/$name")).readBytes(),
            "kasane".toCharArray(), UUID.randomUUID().toString(), name)

    private fun manager(identity: ConnectIdentity, adapter: ConnectPlayerAdapter, dir: java.io.File) =
        ConnectManager(ConnectConfig(identity, adapter, java.io.File(dir, "trusted.json"),
            appVersion = "test", platform = "android", scope = scope, listenPort = 0))

    @Before fun setUp() {
        serverAdapter = RecordingAdapter(); playback = FakePlayback()
        serverDir = java.nio.file.Files.createTempDirectory("rc-server").toFile()
        clientDir = java.nio.file.Files.createTempDirectory("rc-client").toFile()
        server = manager(fixture("test-identity.p12"), serverAdapter, serverDir)
        client = manager(fixture("test-identity-2.p12"), playback, clientDir)
        server.start(); client.start()
        remote = RemoteController(client, playback, scope)
    }

    @After fun tearDown() {
        client.stop(); server.stop(); job.cancel()
        serverDir.deleteRecursively(); clientDir.deleteRecursively()
    }

    private suspend fun awaitTrue(timeoutMs: Long = 15_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            delay(25)
        }
        error("condition not met within ${timeoutMs}ms")
    }

    private suspend fun pairedDeviceId(): String {
        client.pair("127.0.0.1:${server.port}")
        awaitTrue { server.prompts.value.isNotEmpty() }
        server.confirmPairing(server.prompts.value.first().promptId, true)
        awaitTrue { client.devices.value.any { it.connection == "paired" } }
        return client.devices.value.first { it.connection == "paired" }.id!!
    }

    private fun queue() = listOf(
        Track(id = "yt-a1", title = "Song One", artist = "Artist", thumbnailUri = null),
        Track(id = "yt-b2", title = "Song Two", artist = "Artist", thumbnailUri = null),
    )

    private suspend fun transfer(to: String?): Boolean {
        val done = CompletableDeferred<Boolean>()
        remote.transferTo(to) { done.complete(it) }
        return withTimeout(15_000) { done.await() }
    }

    @Test
    fun takeoverPlaysRemoteFromSourcePositionThenLocalPauses() = runBlocking {
        val deviceId = pairedDeviceId()
        playback.setPlaying(queue(), index = 1, positionMs = 42_000)

        assertTrue(transfer(deviceId))

        // The receiver applied the queue and continues at the source position.
        awaitTrue { serverAdapter.handoffs.isNotEmpty() }
        val handoff = serverAdapter.handoffs.single()
        assertEquals(listOf("yt-a1", "yt-b2"), handoff.queue.map { it.id })
        assertEquals(1, handoff.index)
        assertEquals(42_000L, handoff.positionMs)
        assertTrue(handoff.playing)
        awaitTrue { serverAdapter.state.playback == "playing" }
        // Never double-plays: local stopped once the takeover was acked.
        awaitTrue { !playback.uiState.value.isPlaying }
        // Roles: the remote is the accepted performer; we are the listener.
        awaitTrue { client.activeDevice.value == deviceId }
        awaitTrue { remote.target.value == deviceId }
        assertEquals(PlaybackRole.PERFORMER, server.localRole.value)
        assertEquals(PlaybackRole.LISTENER, client.localRole.value)
    }

    @Test
    fun transferBackYieldsRemoteThenPlaysLocally() = runBlocking {
        val deviceId = pairedDeviceId()
        playback.setPlaying(queue(), 0, 5_000)
        assertTrue(transfer(deviceId))
        awaitTrue { serverAdapter.state.playback == "playing" }
        awaitTrue { client.remoteStates.value[deviceId]?.playback == "playing" }

        assertTrue(transfer(null))

        // The performer stood down (yield), then local playback continued the same queue.
        awaitTrue { serverAdapter.state.playback == "paused" }
        awaitTrue { playback.handoffs.isNotEmpty() }
        val (tracks, positionMs) = playback.handoffs.single()
        assertEquals(listOf("yt-a1", "yt-b2"), tracks.map { it.id })
        assertTrue(positionMs >= 5_000L) // the pull continues at the remote's live position
        assertTrue(playback.uiState.value.isPlaying)
        awaitTrue { remote.target.value == null }
        assertEquals(PlaybackRole.PERFORMER, client.localRole.value)
    }

    @Test
    fun playTracksRemotelyIsATakeoverThatClaimsThePerformer() = runBlocking {
        val deviceId = pairedDeviceId()
        val done = CompletableDeferred<Boolean>()
        remote.playTracksRemotely(deviceId, queue(), shuffle = false) { done.complete(it) }
        assertTrue(withTimeout(15_000) { done.await() })
        awaitTrue { serverAdapter.state.playback == "playing" && serverAdapter.handoffs.isNotEmpty() }
        assertEquals(PlaybackRole.PERFORMER, server.localRole.value)
    }

    @Test
    fun noTakeoverToUnpairedDevice() = runBlocking {
        playback.setPlaying(queue(), 0, 0)
        // Nothing paired: the transfer must fail and local playback keeps going.
        assertFalse(transfer("ghost-device"))
        assertTrue(playback.uiState.value.isPlaying)
    }

    @Test
    fun stateFramesCarryRoleAndActiveId() = runBlocking {
        val deviceId = pairedDeviceId()
        // The server starts playing; the subscribed controller sees the new protocol fields.
        serverAdapter.execute("play", JsonObject(emptyMap()))
        awaitTrue { client.remoteStates.value[deviceId]?.role == "performer" }
        assertEquals(deviceId, client.remoteStates.value[deviceId]?.activeId)
    }
}
