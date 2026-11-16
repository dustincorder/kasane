package cord.kasane.music.provider

import org.json.JSONObject
import kotlinx.serialization.json.JsonObject

object TrackLinksParser {
    fun parse(root: JSONObject): Map<String, TrackLinks> = TrackLinksJsonParser.parse(legacyCatalogJson(root) as JsonObject)
}
