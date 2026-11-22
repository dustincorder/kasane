package cord.kasane.music.library

/** A track as described by an external source. Nothing here is trusted until matched. */
data class ImportedTrack(
    val title: String,
    val artist: String = "",
    val album: String? = null,
    val durationSeconds: Double? = null,
    /** An 11-character YouTube video id when the source carried one (directly or inside a URL). */
    val videoId: String? = null,
)

data class ImportedPlaylist(val name: String, val tracks: List<ImportedTrack>)
data class ImportedPlay(val track: ImportedTrack, val playedAtMillis: Long)

/** Everything one file or backup could provide, plus honest notes about what was skipped. */
data class ImportBundle(
    val format: String,
    val playlists: List<ImportedPlaylist> = emptyList(),
    val favorites: List<ImportedTrack> = emptyList(),
    val history: List<ImportedPlay> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val trackCount: Int get() = playlists.sumOf { it.tracks.size } + favorites.size + history.size
}

class ImportFormatException(message: String) : Exception(message)

object VideoIds {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val URL_PATTERNS = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""youtube\.com/(?:embed|shorts|live)/([A-Za-z0-9_-]{11})"""),
    )

    fun isId(value: String?) = value != null && ID.matches(value)

    /** Accepts a bare id or a youtube.com / music.youtube.com / youtu.be URL. Anything else yields null. */
    fun extract(value: String?): String? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (isId(text)) return text
        if (!text.contains("youtube.com") && !text.contains("youtu.be")) return null
        return URL_PATTERNS.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }
    }
}
