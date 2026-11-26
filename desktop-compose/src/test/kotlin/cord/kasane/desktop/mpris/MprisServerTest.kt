package cord.kasane.desktop.mpris

import cord.kasane.desktop.audio.AudioEngine
import cord.kasane.desktop.library.LocalLibrary
import cord.kasane.desktop.playback.DesktopPlayer
import cord.kasane.desktop.playback.PlaybackStream
import cord.kasane.desktop.playback.RepeatMode
import cord.kasane.desktop.playback.StreamSource
import cord.kasane.music.model.Track
import cord.kasane.music.provider.RadioPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real MPRIS path end to end: a private `dbus-daemon` session bus, the real
 * [MprisServer] over it, and a dbus-java client calling Properties and methods —
 * the same round trips `playerctl`/the KDE applet make. Skipped where
 * `dbus-daemon` is absent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MprisServerTest {
    private fun track(id: String) = Track(id, "Title $id", "Artist $id", album = "Album", durationSeconds = 200.0)

    private class FakeEngine : AudioEngine {
        private val _status = MutableStateFlow(AudioEngine.Status())
        override val status: StateFlow<AudioEngine.Status> = _status
        private val _events = MutableSharedFlow<AudioEngine.Event>(extraBufferCapacity = 16)
        override val events: SharedFlow<AudioEngine.Event> = _events.asSharedFlow()
        override fun load(url: String, userAgent: String?, startSec: Double, play: Boolean) {
            _status.value = _status.value.copy(idle = false, paused = !play, buffering = false, positionSec = startSec, durationSec = 200.0)
        }
        override fun setPaused(paused: Boolean) { _status.value = _status.value.copy(paused = paused, idle = false) }
        override fun seek(sec: Double) { _status.value = _status.value.copy(positionSec = sec) }
        override fun setVolume(percent: Double) { _status.value = _status.value.copy(volume = percent) }
        override fun stop() { _status.value = _status.value.copy(idle = true, paused = true) }
        override fun close() {}
    }

    private class FakeStreams : StreamSource {
        override val lastSource get() = "verified"
        override val lastSourceNote get() = ""
        override suspend fun resolve(videoId: String, forceRefresh: Boolean) = PlaybackStream("fake://$videoId")
        override fun consumeCutOff(videoId: String) = false
    }

    private class Rig(dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        val scope = TestScope(dispatcher + SupervisorJob())
        val player = DesktopPlayer(scope, FakeEngine(), FakeStreams(),
            cord.kasane.desktop.playback.RadioSource { _, _ -> RadioPage(emptyList(), null) },
            LocalLibrary(createTempDirectory("kasane-mpris-test").resolve("library.json").toFile()))
        fun done() = scope.cancel()
    }

    /** A private session bus; null where dbus-daemon is not installed. */
    private fun daemon(): Pair<Process, String>? = runCatching {
        val proc = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address")
            .redirectErrorStream(true).start()
        val address = proc.inputStream.bufferedReader().readLine()
        if (address == null || !address.startsWith("unix:")) { proc.destroyForcibly(); null } else proc to address.trim()
    }.getOrNull()

    @DBusInterfaceName(MprisServer.PLAYER_IFACE)
    interface RemotePlayer : DBusInterface {
        fun PlayPause()
        fun Seek(offsetUs: Long)
    }

    private fun awaitPlayerctl(condition: (Properties) -> Boolean, props: Properties) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition(props)) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(50)
        }
    }

    @Test fun publishesStatusAndMetadataAndDrivesPlayback() = runTest {
        val daemon = daemon() ?: return@runTest
        val (proc, address) = daemon
        val rig = Rig(UnconfinedTestDispatcher())
        try {
            MprisServer(rig.player, rig.scope, busAddress = address).use { mpris ->
                assertTrue(mpris.start())
                val client = DBusConnectionBuilder.forAddress(address).build()
                val props = client.getRemoteObject("org.mpris.MediaPlayer2.kasane",
                    MprisServer.OBJECT_PATH, Properties::class.java, false)
                val remote = client.getRemoteObject("org.mpris.MediaPlayer2.kasane",
                    MprisServer.OBJECT_PATH, RemotePlayer::class.java, false)

                assertEquals("Stopped", props.Get<String>(MprisServer.PLAYER_IFACE, "PlaybackStatus"))
                assertEquals("Kasane", props.Get<String>(MprisServer.ROOT_IFACE, "Identity"))
                assertEquals(true, props.Get<Boolean>(MprisServer.PLAYER_IFACE, "CanControl"))

                rig.player.playCollection(listOf(track("t1"), track("t2")))
                awaitPlayerctl({ p -> p.Get<String>(MprisServer.PLAYER_IFACE, "PlaybackStatus") == "Playing" }, props)

                @Suppress("UNCHECKED_CAST")
                val rawMeta = (props.Get<Any>(MprisServer.PLAYER_IFACE, "Metadata") as? Variant<*>)?.value
                    ?: props.Get<Any>(MprisServer.PLAYER_IFACE, "Metadata")
                val meta = rawMeta as Map<String, Any?>
                fun metaValue(key: String) = (meta[key] as? Variant<*>)?.value ?: meta[key]
                assertEquals("Title t1", metaValue("xesam:title"))
                assertEquals(listOf("Artist t1"), metaValue("xesam:artist"))
                assertEquals(200_000_000L, metaValue("mpris:length"))

                remote.PlayPause()
                awaitPlayerctl({ p -> p.Get<String>(MprisServer.PLAYER_IFACE, "PlaybackStatus") == "Paused" }, props)
                assertTrue(!rig.player.state.value.isPlaying)
                remote.PlayPause()
                awaitPlayerctl({ p -> p.Get<String>(MprisServer.PLAYER_IFACE, "PlaybackStatus") == "Playing" }, props)

                remote.Seek(42_000_000L)
                awaitPlayerctl({ p -> (p.Get<Any>(MprisServer.PLAYER_IFACE, "Position") as? Number)?.toLong() == 42_000_000L }, props)

                props.Set(MprisServer.PLAYER_IFACE, "LoopStatus", "Track")
                assertEquals(RepeatMode.ONE, rig.player.state.value.repeat)
                props.Set(MprisServer.PLAYER_IFACE, "Shuffle", true)
                assertTrue(rig.player.state.value.shuffle)

                assertNotNull(props.GetAll(MprisServer.PLAYER_IFACE)["CanControl"])
                client.disconnect()
            }
        } finally {
            rig.done(); proc.destroyForcibly()
        }
    }
}
