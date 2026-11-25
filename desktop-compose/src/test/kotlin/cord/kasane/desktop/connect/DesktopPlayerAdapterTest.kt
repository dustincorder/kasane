package cord.kasane.desktop.connect

import cord.kasane.desktop.audio.AudioEngine
import cord.kasane.desktop.library.LocalLibrary
import cord.kasane.desktop.playback.DesktopPlayer
import cord.kasane.desktop.playback.PlaybackStream
import cord.kasane.desktop.playback.RadioSource
import cord.kasane.desktop.playback.RepeatMode
import cord.kasane.desktop.playback.StreamSource
import cord.kasane.music.connect.ConnectJson
import cord.kasane.music.connect.ConnectPlayerAdapter
import cord.kasane.music.connect.Frame
import cord.kasane.music.connect.toMeta
import cord.kasane.music.model.Track
import cord.kasane.music.provider.RadioPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Loopback remote-command coverage: the actions a paired device sends over Connect land on
 * `adapter.execute`/`adapter.handoff` — same fakes as the queue tests, no network, no mpv.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPlayerAdapterTest {
    private fun track(id: String) = Track(id, "Title $id", "Artist", durationSeconds = 200.0)

    private class FakeEngine : AudioEngine {
        data class Load(val url: String, val userAgent: String?, val startSec: Double, val play: Boolean)
        val loads = mutableListOf<Load>()
        private val _status = MutableStateFlow(AudioEngine.Status())
        override val status = _status
        private val _events = MutableSharedFlow<AudioEngine.Event>(extraBufferCapacity = 16)
        override val events = _events.asSharedFlow()
        override fun load(url: String, userAgent: String?, startSec: Double, play: Boolean) {
            loads += Load(url, userAgent, startSec, play)
            _status.value = _status.value.copy(idle = false, paused = !play, buffering = false, positionSec = startSec, durationSec = 200.0)
        }
        override fun setPaused(paused: Boolean) { _status.value = _status.value.copy(paused = paused, idle = false) }
        override fun seek(sec: Double) { _status.value = _status.value.copy(positionSec = sec) }
        override fun setVolume(percent: Double) { _status.value = _status.value.copy(volume = percent) }
        override fun stop() { _status.value = _status.value.copy(idle = true, paused = true) }
        override fun close() {}
    }

    private class FakeStreams : StreamSource {
        val resolves = mutableListOf<Pair<String, Boolean>>()
        override val lastSource get() = "fake"
        override val lastSourceNote get() = ""
        override suspend fun resolve(videoId: String, forceRefresh: Boolean): PlaybackStream {
            resolves += videoId to forceRefresh
            return PlaybackStream("fake://$videoId", "ua")
        }
        override fun consumeCutOff(videoId: String) = false
    }

    private class Rig(dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        val scope = TestScope(dispatcher + SupervisorJob())
        val engine = FakeEngine()
        val streams = FakeStreams()
        val library = LocalLibrary(createTempDirectory("kasane-test").resolve("library.json").toFile())
        val player = DesktopPlayer(scope, engine, streams, RadioSource { _, _ -> RadioPage(emptyList(), null) }, library)
        val adapter: ConnectPlayerAdapter = DesktopPlayerAdapter(player, scope)
        fun done() = scope.cancel()
    }

    private fun rig(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = Rig(dispatcher)

    private fun trackArg(t: Track) = ConnectJson.json.encodeToJsonElement(
        cord.kasane.music.connect.TrackMeta.serializer(), t.toMeta())

    @Test fun remotePlayTrackLoadsIt() = runTest {
        val r = rig(UnconfinedTestDispatcher())
        r.adapter.execute("playTrack", buildJsonObject { put("track", trackArg(track("v1"))) })
        assertEquals("fake://v1", r.engine.loads.single().url)
        assertEquals("v1", r.player.state.value.current?.id)
        r.done()
    }

    @Test fun remotePausePlaySeekVolume() = runTest {
        val r = rig(UnconfinedTestDispatcher())
        r.adapter.execute("playTrack", buildJsonObject { put("track", trackArg(track("v1"))) })
        r.adapter.execute("pause", buildJsonObject {})
        assertFalse(r.player.state.value.isPlaying)
        r.adapter.execute("play", buildJsonObject {})
        assertTrue(r.player.state.value.isPlaying)
        r.adapter.execute("seek", buildJsonObject { put("positionMs", 61_500L) })
        assertEquals(61.5, r.player.state.value.positionSec, 0.01)
        r.adapter.execute("setVolume", buildJsonObject { put("volume", 0.4) })
        assertEquals(0.4f, r.player.state.value.volume)
        r.done()
    }

    @Test fun remoteQueueCommands() = runTest {
        val r = rig(UnconfinedTestDispatcher())
        r.adapter.execute("playTrack", buildJsonObject { put("track", trackArg(track("a"))) })
        r.adapter.execute("addToQueue", buildJsonObject { put("track", trackArg(track("b"))) })
        r.adapter.execute("addToQueue", buildJsonObject { put("track", trackArg(track("c"))) })
        r.adapter.execute("playQueueIndex", buildJsonObject { put("index", 2L) })
        assertEquals("c", r.player.state.value.current?.id)
        r.adapter.execute("setShuffle", buildJsonObject { put("enabled", true) })
        assertTrue(r.player.state.value.shuffle)
        r.adapter.execute("setRepeat", buildJsonObject { put("mode", "all") })
        assertEquals(RepeatMode.ALL, r.player.state.value.repeat)
        r.done()
    }

    @Test fun takeoverHandoffInstallsQueueAtPosition() = runTest {
        val r = rig(UnconfinedTestDispatcher())
        // What `handleTakeover` calls: the new performer plays from the source's position.
        r.adapter.handoff(Frame.Handoff("t1", listOf(track("x"), track("y")).map { it.toMeta() },
            index = 1, positionMs = 45_000, playing = true, shuffle = false, repeat = "off"))
        val s = r.player.state.value
        assertEquals(listOf("x", "y"), s.queue.map { it.id })
        assertEquals(1, s.index)
        assertEquals("y", s.current?.id)
        assertEquals(45.0, r.engine.loads.last().startSec)
        r.done()
    }

    @Test fun unknownActionFailsUnsupported() = runTest {
        val r = rig(UnconfinedTestDispatcher())
        val e = assertFailsWith<cord.kasane.music.connect.CmdException> {
            r.adapter.execute("teleport", buildJsonObject {})
        }
        assertEquals("unsupported", e.code)
        r.done()
    }

    @Test fun localTracksAreRefused() = runTest {
        val r = rig(UnconfinedTestDispatcher())
        val local = Track("l1", "Local", "Artist", isLocal = true)
        val e = assertFailsWith<cord.kasane.music.connect.CmdException> {
            r.adapter.execute("playTrack", buildJsonObject { put("track", trackArg(local)) })
        }
        assertEquals("unsupported_media", e.code)
        r.done()
    }
}
