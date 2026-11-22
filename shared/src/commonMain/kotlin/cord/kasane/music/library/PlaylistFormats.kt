package cord.kasane.music.library

import kotlinx.serialization.json.*

/** Parsers for portable playlist files. They never fetch anything: a URL is only mined for a video id. */
object PlaylistFormats {
    fun parseM3u(text: String, fallbackName: String = "Imported playlist"): ImportBundle {
        val tracks = mutableListOf<ImportedTrack>()
        val warnings = mutableListOf<String>()
        var name = fallbackName
        var pendingTitle: String? = null
        var pendingDuration: Double? = null
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#PLAYLIST:", true) -> name = line.substringAfter(':').trim().ifEmpty { name }
                line.startsWith("#EXTINF:", true) -> {
                    val body = line.substringAfter(':')
                    pendingDuration = body.substringBefore(',').trim().toDoubleOrNull()?.takeIf { it > 0 }
                    pendingTitle = body.substringAfter(',', "").trim().ifEmpty { null }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val id = VideoIds.extract(line)
                    val label = pendingTitle ?: line.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')
                    val (artist, title) = splitArtistTitle(label)
                    if (id == null && pendingTitle == null && label.isBlank()) warnings += "Skipped an entry without a title or a YouTube id."
                    else tracks += ImportedTrack(title, artist, durationSeconds = pendingDuration, videoId = id)
                    pendingTitle = null; pendingDuration = null
                }
            }
        }
        return ImportBundle("m3u", listOf(ImportedPlaylist(name, tracks)), warnings = warnings)
    }

    fun parsePls(text: String, fallbackName: String = "Imported playlist"): ImportBundle {
        val entries = sortedMapOf<Int, MutableMap<String, String>>()
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            val match = Regex("""^(file|title|length)(\d+)$""").matchEntire(key) ?: continue
            entries.getOrPut(match.groupValues[2].toInt()) { mutableMapOf() }[match.groupValues[1]] = line.substring(eq + 1).trim()
        }
        if (entries.isEmpty()) throw ImportFormatException("This PLS file has no entries.")
        val tracks = entries.values.mapNotNull { e ->
            val file = e["file"].orEmpty()
            val label = e["title"]?.takeIf { it.isNotBlank() } ?: file.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')
            if (label.isBlank() && VideoIds.extract(file) == null) return@mapNotNull null
            val (artist, title) = splitArtistTitle(label)
            ImportedTrack(title, artist, durationSeconds = e["length"]?.toDoubleOrNull()?.takeIf { it > 0 }, videoId = VideoIds.extract(file))
        }
        return ImportBundle("pls", listOf(ImportedPlaylist(fallbackName, tracks)))
    }

    /** RFC 4180 CSV with header detection. Understands Exportify/Spotify-style and plain title,artist,album exports. */
    fun parseCsv(text: String, fallbackName: String = "Imported playlist"): ImportBundle {
        val rows = readCsv(text.removePrefix("\uFEFF")).filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) throw ImportFormatException("This CSV file is empty.")
        val header = rows.first().map { it.trim().lowercase() }
        fun col(vararg names: String) = header.indexOfFirst { h -> names.any { h == it || h.startsWith(it) } }
        val title = col("track name", "title", "track", "song", "name")
        val artist = col("artist name", "artist", "artists")
        val album = col("album name", "album")
        val durationMs = col("duration (ms)", "duration_ms")
        val durationSec = col("duration (s", "duration_seconds", "duration")
        val link = col("video id", "videoid", "youtube id", "id", "url", "link", "uri")
        if (title < 0 && link < 0) throw ImportFormatException("No title or video id column was found. Expected a header such as: title,artist,album.")
        val warnings = mutableListOf<String>()
        val tracks = rows.drop(1).mapNotNull { r ->
            fun at(i: Int) = r.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() && i >= 0 }
            val id = at(link)?.let(VideoIds::extract)
            val name = at(title)
            if (name == null && id == null) { warnings += "Skipped a row without a title or video id."; return@mapNotNull null }
            val seconds = at(durationMs)?.toDoubleOrNull()?.div(1000) ?: at(durationSec)?.let { parseDuration(it) }
            ImportedTrack(name ?: "", at(artist)?.replace(";", ", ").orEmpty(), at(album), seconds?.takeIf { it > 0 }, id)
        }
        return ImportBundle("csv", listOf(ImportedPlaylist(fallbackName, tracks)), warnings = warnings)
    }

    /** Kasane's own JSON, or a generic array/object of track-like objects. */
    fun parseJson(text: String, fallbackName: String = "Imported playlist"): ImportBundle {
        val root = try { Json.parseToJsonElement(text.removePrefix("\uFEFF")) } catch (e: Exception) { throw ImportFormatException("This file is not valid JSON.") }
        if (root is JsonObject && root["format"]?.jsonPrimitive?.contentOrNull == KasaneExport.FORMAT) return KasaneExport.decode(root)
        val playlists = mutableListOf<ImportedPlaylist>()
        val warnings = mutableListOf<String>()
        fun tracksOf(array: JsonArray) = array.mapNotNull { (it as? JsonObject)?.let(::trackOf) }
        when (root) {
            is JsonArray -> playlists += ImportedPlaylist(fallbackName, tracksOf(root))
            is JsonObject -> {
                val lists = (root["playlists"] as? JsonArray)
                if (lists != null) lists.forEach { p ->
                    val o = p as? JsonObject ?: return@forEach
                    playlists += ImportedPlaylist(o["name"]?.jsonPrimitive?.contentOrNull ?: o["title"]?.jsonPrimitive?.contentOrNull ?: fallbackName,
                        tracksOf((o["tracks"] ?: o["songs"] ?: o["items"]) as? JsonArray ?: JsonArray(emptyList())))
                } else ((root["tracks"] ?: root["songs"] ?: root["items"]) as? JsonArray)?.let {
                    playlists += ImportedPlaylist(root["name"]?.jsonPrimitive?.contentOrNull ?: fallbackName, tracksOf(it))
                }
            }
            else -> Unit
        }
        if (playlists.isEmpty() || playlists.all { it.tracks.isEmpty() }) throw ImportFormatException("No tracks were found in this JSON file.")
        return ImportBundle("json", playlists, warnings = warnings)
    }

    internal fun trackOf(o: JsonObject): ImportedTrack? {
        fun s(vararg keys: String) = keys.firstNotNullOfOrNull { k -> o[k]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }
        val id = VideoIds.extract(s("videoId", "video_id", "id", "url", "link"))
        val title = s("title", "name", "track", "trackName")
        if (title == null && id == null) return null
        val artist = s("artist", "artistName", "artists") ?: (o["artists"] as? JsonArray)?.joinToString(", ") {
            (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull ?: (it as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
        val duration = o["durationSeconds"]?.jsonPrimitive?.doubleOrNull ?: o["duration"]?.jsonPrimitive?.doubleOrNull
        return ImportedTrack(title.orEmpty(), artist.orEmpty(), s("album", "albumName"), duration?.takeIf { it > 0 }, id)
    }

    /** "Artist - Title" is a common M3U/PLS convention. Only split on a spaced dash to avoid breaking "Jay-Z". */
    internal fun splitArtistTitle(label: String): Pair<String, String> {
        val i = label.indexOf(" - ")
        return if (i > 0) label.substring(0, i).trim() to label.substring(i + 3).trim() else "" to label.trim()
    }

    private fun parseDuration(text: String): Double? {
        text.toDoubleOrNull()?.let { return it }
        val parts = text.split(':').map { it.toIntOrNull() ?: return null }
        return parts.fold(0) { acc, p -> acc * 60 + p }.toDouble()
    }

    internal fun readCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        val delimiter = detectDelimiter(text)
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == delimiter -> { row.add(cell.toString()); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(cell.toString()); cell.clear(); rows.add(row); row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    private fun detectDelimiter(text: String): Char {
        val first = text.lineSequence().firstOrNull().orEmpty()
        return listOf(',', ';', '\t').maxByOrNull { d -> first.count { it == d } }?.takeIf { d -> first.contains(d) } ?: ','
    }
}
