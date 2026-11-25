package cord.kasane.desktop.library

import cord.kasane.music.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One history entry: a played track or a started mix (radio), like Android's HistoryEntity. */
@Serializable
data class HistoryEntry(
    val track: Track,
    val playedAt: Long,
    /** "track" for played songs; "mix" records a radio started from the track so it can be relaunched. */
    val kind: String = "track",
    val plays: Int = 1,
)

/**
 * Listening history kept on this computer. Signed-in history is persisted per account, keyed by
 * [setAccount]; a guest session is memory-only — it is never written to disk and never mixes
 * into an account's feed. YouTube Music likes live on the server, not here.
 */
class LocalLibrary(private val file: File) {
    @Serializable
    private data class Data(val accounts: Map<String, List<HistoryEntry>> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var data = runCatching { json.decodeFromString<Data>(file.readText()) }.getOrDefault(Data())
    private var accountKey: String? = null
    private var guestHistory = listOf<HistoryEntry>()
    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    /** Switches the visible history: an account key (channel id / name) or null for the guest session. */
    fun setAccount(key: String?) {
        accountKey = key
        _history.value = if (key == null) guestHistory else data.accounts[key].orEmpty()
    }

    @Synchronized
    fun recordPlayed(track: Track, kind: String = "track", now: Long = System.currentTimeMillis()) {
        val list = (if (accountKey == null) guestHistory else data.accounts[accountKey].orEmpty())
        val existing = list.firstOrNull { it.track.id == track.id && it.kind == kind }
        val next = listOf(
            HistoryEntry(track, now, kind, (existing?.plays ?: 0) + 1)
        ) + list.filterNot { it.track.id == track.id && it.kind == kind }
        if (accountKey == null) guestHistory = next.take(MAX_ENTRIES) else save(next.take(MAX_ENTRIES))
        _history.value = next.take(MAX_ENTRIES)
    }

    /** Most recently played tracks first, mixes excluded. */
    fun recentTracks(limit: Int = 12): List<Track> =
        _history.value.filter { it.kind == "track" }.sortedByDescending { it.playedAt }.map { it.track }.take(limit)

    /** Most played first — the habits that feed Home's picks and radio seeds. One play is not yet a preference. */
    fun topPlayed(limit: Int = 8): List<Track> =
        _history.value.filter { it.kind == "track" && it.plays >= 2 }.sortedWith(
            compareByDescending<HistoryEntry> { it.plays }.thenByDescending { it.playedAt }).map { it.track }.take(limit)

    private fun save(accountHistory: List<HistoryEntry>) {
        val key = accountKey ?: return
        data = data.copy(accounts = data.accounts + (key to accountHistory))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val text = json.encodeToString(Data.serializer(), data)
        tmp.writeText(text)
        if (!tmp.renameTo(file)) file.writeText(text)
    }

    companion object { private const val MAX_ENTRIES = 300 }
}
