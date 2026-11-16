package cord.kasane.music.provider

import cord.kasane.music.model.*
import kotlinx.serialization.json.*

/** Only browse endpoints in the exact track renderer; never a title-based catalog guess. */
object TrackLinksJsonParser {
    fun parse(root: JsonObject): Map<String, TrackLinks> {
        val result = mutableMapOf<String, TrackLinks>()
        val rows = CatalogJsonParser.objects(root, "musicResponsiveListItemRenderer").mapNotNull { row ->
            val track = CatalogJsonParser.item(JsonObject(mapOf("musicResponsiveListItemRenderer" to row)))?.track()
            track?.let { it to row }
        } + CatalogJsonParser.objects(root, "playlistPanelVideoRenderer").mapNotNull { row ->
            row.optString("videoId").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let {
                Track(it, CatalogJsonParser.text(row.optJsonObject("title")), CatalogJsonParser.text(row.optJsonObject("longBylineText"))) to row
            }
        }
        rows.forEach { (track, row) ->
            val endpoints = CatalogJsonParser.objects(row, "browseEndpoint")
            val artist = endpoints.firstOrNull { it.optString("browseId").startsWith("UC") }?.optString("browseId")
            val album = endpoints.firstOrNull { it.optString("browseId").startsWith("MPRE") }?.optString("browseId")
            if (artist != null || album != null) result[track.id] = TrackLinks(
                artist?.let { CatalogItem(it, CatalogKind.ARTIST, track.artist.ifBlank { "Artist" }) },
                album?.let { CatalogItem(it, CatalogKind.ALBUM, track.album ?: "Album") })
        }
        return result
    }
}
