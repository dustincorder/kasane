package cord.kasane.music.connect

import cord.kasane.music.model.Track
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

class CmdException(val code: String, message: String) : Exception(message)

fun cmdFail(code: String, message: String): Nothing = throw CmdException(code, message)

/** What the local player reports right now; the manager assigns rev/queueRev. */
data class PlayerSnapshot(
    val playback: String = "idle", // idle|buffering|playing|paused|ended|error
    val track: TrackMeta? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float? = null,
    val shuffle: Boolean = false,
    val repeat: String = "off", // off|all|one
    val queueIndex: Long = -1,
    val queue: List<TrackMeta> = emptyList(),
    val error: String? = null,
)

/** Bridge between protocol sessions and the host player. */
interface ConnectPlayerAdapter {
    suspend fun snapshot(): PlayerSnapshot

    /** Bumped whenever a non-position field may have changed. */
    val changed: StateFlow<Long>

    @Throws(CmdException::class)
    suspend fun execute(action: String, args: JsonObject)

    @Throws(CmdException::class)
    suspend fun handoff(request: Frame.Handoff)

    fun capabilities(): List<String>
}

fun Track.toMeta(): TrackMeta = TrackMeta(
    id = id,
    title = title,
    artist = artist,
    artists = emptyList(),
    album = album?.let { AlbumMeta(title = it) },
    durationMs = (durationSeconds * 1000).toLong(),
    artworkUrl = thumbnailUri,
    source = if (isLocal) "local" else "youtube",
)

fun TrackMeta.toTrack(): Track = Track(
    id = id,
    title = title,
    artist = artist,
    album = album?.title,
    durationSeconds = durationMs / 1000.0,
    thumbnailUri = artworkUrl,
    streamUri = null,
    isLocal = source == "local",
    sourceProvider = if (source == "local") "local" else "youtube",
)
