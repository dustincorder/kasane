package cord.kasane.desktop.mpris

import cord.kasane.desktop.BuildInfo
import cord.kasane.desktop.playback.DesktopPlayer
import cord.kasane.desktop.playback.PlayerState
import cord.kasane.desktop.playback.RepeatMode
import cord.kasane.music.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.errors.NotSupported
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

/**
 * MPRIS2 endpoint (`org.mpris.MediaPlayer2.kasane` / `.kasane.nightly`) so the desktop
 * environment — KDE media applet, media keys, headsets — sees and drives Kasane like any
 * other player. Uses dbus-java on the session bus; when no bus is reachable the app simply
 * runs without MPRIS. `OpenUri` is intentionally absent (Kasane opens no foreign URIs), and
 * Rate is fixed at 1.0 — mpv speed control is not exposed by [DesktopPlayer].
 */
class MprisServer(
    private val player: DesktopPlayer,
    private val scope: CoroutineScope,
    /** Well-known name; the nightly build variant takes its own so both can run and play. */
    private val busName: String = "org.mpris.MediaPlayer2.kasane${BuildInfo.nightlySuffix}",
    /** Explicit bus address for tests (a private `dbus-daemon`); null = the real session bus. */
    private val busAddress: String? = null,
) : AutoCloseable {

    /** Window callbacks — wired from the UI process; headless modes keep the no-ops. */
    var onRaise: () -> Unit = {}
    var onQuit: () -> Unit = {}

    @Volatile var running = false
        private set
    private var conn: DBusConnection? = null
    private var watch: Job? = null
    private var lastPositionSec = 0.0

    fun start(): Boolean {
        if (running) return true
        val connection = try {
            (if (busAddress != null) DBusConnectionBuilder.forAddress(busAddress)
            else DBusConnectionBuilder.forSessionBus()).build()
        } catch (e: Exception) {
            System.err.println("mpris: no session bus (${e.message}) — media keys/applets stay dark")
            return false
        }
        try {
            connection.requestBusName(busName)
            connection.exportObject(OBJECT_PATH, MprisObject())
        } catch (e: Exception) {
            System.err.println("mpris: could not claim $busName (${e.message})")
            runCatching { connection.disconnect() }
            return false
        }
        conn = connection
        running = true
        watch = scope.launch {
            var last = published(player.state.value)
            player.state.collect { s ->
                val now = published(s)
                val changed = now.filter { (k, v) -> last[k]?.value != v.value }
                if (changed.isNotEmpty()) {
                    last = now
                    runCatching {
                        conn?.sendMessage(Properties.PropertiesChanged(OBJECT_PATH, PLAYER_IFACE, changed, emptyList()))
                    }
                }
                if (kotlin.math.abs(s.positionSec - lastPositionSec) > SEEK_JUMP_SEC) emitSeeked(s)
            }
        }
        return true
    }

    private fun emitSeeked(s: PlayerState) {
        lastPositionSec = s.positionSec
        if (s.current == null) return
        runCatching { conn?.sendMessage(Seeked(OBJECT_PATH, (s.positionSec * 1_000_000).toLong())) }
    }

    private fun playbackStatus(s: PlayerState): String = when {
        s.current == null -> "Stopped"
        s.isPlaying || s.isLoading -> "Playing"
        else -> "Paused"
    }

    private fun loopStatus(s: PlayerState): String = when (s.repeat) {
        RepeatMode.ONE -> "Track"; RepeatMode.ALL -> "Playlist"; RepeatMode.OFF -> "None"
    }

    private fun trackPath(id: String) = "/org/mpris/MediaPlayer2/track/" + id.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

    private fun metadata(t: Track?): Map<String, Variant<*>> {
        if (t == null) return mapOf("mpris:trackid" to Variant(DBusPath("/org/mpris/MediaPlayer2/track/none")))
        return buildMap {
            put("mpris:trackid", Variant(DBusPath(trackPath(t.id))))
            put("xesam:title", Variant(t.title))
            put("xesam:artist", Variant(listOf(t.artist), "as"))
            t.album?.let { put("xesam:album", Variant(it)) }
            put("xesam:url", Variant("https://music.youtube.com/watch?v=${t.id}"))
            t.thumbnailUri?.let { put("mpris:artUrl", Variant(it)) }
            if (t.durationSeconds > 0) put("mpris:length", Variant((t.durationSeconds * 1_000_000).toLong()))
        }
    }

    /** Current values of the Player interface's published properties. */
    private fun published(s: PlayerState): Map<String, Variant<*>> = mapOf(
        "PlaybackStatus" to Variant(playbackStatus(s)),
        "LoopStatus" to Variant(loopStatus(s)),
        "Shuffle" to Variant(s.shuffle),
        "Metadata" to Variant(metadata(s.current), "a{sv}"),
        "Volume" to Variant(s.volume.toDouble()),
        "Position" to Variant((s.positionSec * 1_000_000).toLong()),
        "Rate" to Variant(1.0),
        "MinimumRate" to Variant(1.0),
        "MaximumRate" to Variant(1.0),
        "CanGoNext" to Variant(s.canNext),
        "CanGoPrevious" to Variant(s.canPrevious),
        "CanPlay" to Variant(s.current != null),
        "CanPause" to Variant(s.isPlaying),
        "CanSeek" to Variant(s.current != null),
        "CanControl" to Variant(true),
    )

    private fun rootProps(): Map<String, Variant<*>> = mapOf(
        "CanQuit" to Variant(true),
        "CanRaise" to Variant(true),
        "HasTrackList" to Variant(false),
        "Identity" to Variant(BuildInfo.appName),
        "DesktopEntry" to Variant("cord.kasane.desktop${BuildInfo.nightlySuffix}"),
        "SupportedUriSchemes" to Variant(emptyList<String>(), "as"),
        "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
    )

    private fun props(iface: String) = when (iface) {
        ROOT_IFACE -> rootProps()
        PLAYER_IFACE -> published(player.state.value)
        else -> emptyMap()
    }

    // ---- Exported D-Bus interfaces ----------------------------------------------------------------------

    @DBusInterfaceName(ROOT_IFACE)
    interface Root : DBusInterface { fun Raise(); fun Quit() }

    @DBusInterfaceName(PLAYER_IFACE)
    interface Player : DBusInterface {
        fun Play(); fun Pause(); fun PlayPause(); fun Stop(); fun Next(); fun Previous()
        fun Seek(offsetUs: Long); fun SetPosition(trackId: DBusPath, positionUs: Long)
    }

    /** `org.mpris.MediaPlayer2.Player.Seeked` — member name is the class name. */
    class Seeked(path: String, positionUs: Long) : DBusSignal(path, positionUs)

    /** Exported object — kept non-private so reflective dispatch can invoke its methods. */
    inner class MprisObject : Root, Player, Properties {
        override fun isRemote() = false
        override fun getObjectPath() = OBJECT_PATH

        // org.mpris.MediaPlayer2
        override fun Raise() = onRaise()
        override fun Quit() = onQuit()

        // org.mpris.MediaPlayer2.Player
        override fun Play() { if (!player.state.value.isPlaying) player.toggle() }
        override fun Pause() = player.pause()
        override fun PlayPause() = player.toggle()
        override fun Stop() { player.pause(); player.seek(0.0) }
        override fun Next() = player.next()
        override fun Previous() = player.previous()
        override fun Seek(offsetUs: Long) = player.seek(player.state.value.positionSec + offsetUs / 1e6)
        override fun SetPosition(trackId: DBusPath, positionUs: Long) = player.seek(positionUs / 1e6)

        // org.freedesktop.DBus.Properties
        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            (props(interfaceName)[propertyName]
                ?: throw org.freedesktop.dbus.errors.UnknownProperty("$interfaceName.$propertyName")) as A
        override fun GetAll(interfaceName: String): MutableMap<String, Variant<*>> =
            props(interfaceName).toMutableMap()
        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
            if (interfaceName != PLAYER_IFACE) throw PropertyReadOnly("$interfaceName.$propertyName is read-only")
            val v = (value as? Variant<*>)?.value ?: value
            when (propertyName) {
                "LoopStatus" -> {
                    val target = when (v) {
                        "None" -> RepeatMode.OFF; "Playlist" -> RepeatMode.ALL; "Track" -> RepeatMode.ONE
                        else -> throw org.freedesktop.dbus.errors.InvalidMethodArgument("LoopStatus: $v")
                    }
                    while (player.state.value.repeat != target) player.cycleRepeat()
                }
                "Shuffle" -> { if (v is Boolean && player.state.value.shuffle != v) player.toggleShuffle() }
                "Volume" -> (v as? Number)?.let { player.setVolume(it.toFloat()) }
                    ?: throw org.freedesktop.dbus.errors.InvalidMethodArgument("Volume: $v")
                "Rate" -> { if ((v as? Number)?.toDouble() != 1.0) throw NotSupported("Kasane plays at 1.0x only") }
                else -> throw PropertyReadOnly("org.mpris.MediaPlayer2.Player.$propertyName is read-only")
            }
        }
    }

    override fun close() {
        running = false
        watch?.cancel()
        conn?.let { c -> runCatching { c.releaseBusName(busName) }; runCatching { c.disconnect() } }
        conn = null
    }

    companion object {
        const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
        const val ROOT_IFACE = "org.mpris.MediaPlayer2"
        const val PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"
        private const val SEEK_JUMP_SEC = 2.0
    }
}
