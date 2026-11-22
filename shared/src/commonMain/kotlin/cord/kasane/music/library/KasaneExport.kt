package cord.kasane.music.library

import kotlinx.serialization.json.*

/**
 * Kasane's portable library format (documented in docs/kasane-export-format.md).
 * Plain JSON, versioned, no account data, no cookies, no stream URLs and no audio.
 */
object KasaneExport {
    const val FORMAT = "kasane-library"
    const val VERSION = 1

    fun encode(bundle: ImportBundle, appVersion: String, exportedAtMillis: Long): String {
        fun track(t: ImportedTrack) = buildJsonObject {
            put("title", t.title); put("artist", t.artist)
            t.album?.let { put("album", it) }
            t.durationSeconds?.let { put("durationSeconds", it) }
            t.videoId?.let { put("videoId", it) }
        }
        val root = buildJsonObject {
            put("format", FORMAT); put("version", VERSION); put("app", "Kasane $appVersion"); put("exportedAt", exportedAtMillis)
            put("favorites", JsonArray(bundle.favorites.map(::track)))
            put("history", JsonArray(bundle.history.map { h -> buildJsonObject { put("track", track(h.track)); put("playedAt", h.playedAtMillis) } }))
            put("playlists", JsonArray(bundle.playlists.map { p -> buildJsonObject { put("name", p.name); put("tracks", JsonArray(p.tracks.map(::track))) } }))
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
    }

    fun decode(root: JsonObject): ImportBundle {
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: throw ImportFormatException("The Kasane export has no version.")
        if (version > VERSION) throw ImportFormatException("This export was made by a newer Kasane (format version $version). Update Kasane to import it.")
        fun tracks(e: JsonElement?) = (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(PlaylistFormats::trackOf) }
        val history = (root["history"] as? JsonArray).orEmpty().mapNotNull { h ->
            val o = h as? JsonObject ?: return@mapNotNull null
            val t = (o["track"] as? JsonObject)?.let(PlaylistFormats::trackOf) ?: return@mapNotNull null
            ImportedPlay(t, o["playedAt"]?.jsonPrimitive?.longOrNull ?: 0L)
        }
        val playlists = (root["playlists"] as? JsonArray).orEmpty().mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            ImportedPlaylist(o["name"]?.jsonPrimitive?.contentOrNull ?: "Imported playlist", tracks(o["tracks"]))
        }
        return ImportBundle(FORMAT, playlists, tracks(root["favorites"]), history)
    }
}
