package cord.kasane.music.provider

import cord.kasane.music.model.CatalogPage
import org.json.JSONObject
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/** Legacy boundary; the supported-library schema check lives in shared code. */
object AccountLibraryParser {
    fun page(root: JSONObject): CatalogPage = try {
        CatalogJsonParser.libraryPage(legacyCatalogJson(root) as JsonObject)
    } catch (failure: CatalogFailure) { throw IOException(failure.message) }
}
