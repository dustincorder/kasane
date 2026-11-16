package cord.kasane.music.provider

import org.json.JSONObject
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/** Existing Android entrypoints use the shared action validation/receipt parser. */
object PlaylistActionParser {
    fun playlistId(value: String) = PlaylistJsonParser.playlistId(value)
    fun destinations(root: JSONObject, videoId: String): List<PlaylistDestination> = try {
        PlaylistJsonParser.destinations(legacyCatalogJson(root) as JsonObject, videoId)
    } catch (failure: CatalogFailure) { throw IOException(failure.message) }
    fun requireAddReceipt(root: JSONObject, videoId: String) = try {
        PlaylistJsonParser.requireAddReceipt(legacyCatalogJson(root) as JsonObject, videoId)
    } catch (failure: CatalogFailure) { throw IOException(failure.message) }
}
