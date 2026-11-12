package cord.kasane.music.provider

import cord.kasane.music.model.*
import org.json.JSONObject

data class TrackLinks(val artist: CatalogItem? = null, val album: CatalogItem? = null)

/** Only browse endpoints in the exact track renderer; never a title-based catalog guess. */
object TrackLinksParser {
    fun parse(root: JSONObject): Map<String, TrackLinks> {
        val result = mutableMapOf<String, TrackLinks>()
        val rows = CatalogParser.objects(root, "musicResponsiveListItemRenderer").mapNotNull { row ->
            val track = CatalogParser.item(JSONObject().put("musicResponsiveListItemRenderer", row))?.track()
            track?.let { it to row }
        } + CatalogParser.objects(root, "playlistPanelVideoRenderer").mapNotNull { row ->
            row.optString("videoId").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let {
                Track(it, CatalogParser.text(row.optJSONObject("title")), CatalogParser.text(row.optJSONObject("longBylineText"))) to row
            }
        }
        rows.forEach { (track, row) ->
            val endpoints = CatalogParser.objects(row, "browseEndpoint")
            val artist = endpoints.firstOrNull { it.optString("browseId").startsWith("UC") }?.optString("browseId")
            val album = endpoints.firstOrNull { it.optString("browseId").startsWith("MPRE") }?.optString("browseId")
            if (artist != null || album != null) result[track.id] = TrackLinks(
                artist?.let { CatalogItem(it, CatalogKind.ARTIST, track.artist.ifBlank { "Artist" }) },
                album?.let { CatalogItem(it, CatalogKind.ALBUM, track.album ?: "Album") })
        }
        return result
    }
}
