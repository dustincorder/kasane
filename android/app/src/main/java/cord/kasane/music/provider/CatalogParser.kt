package cord.kasane.music.provider

import cord.kasane.music.model.*
import org.json.JSONObject
import kotlinx.serialization.json.*

/** Temporary org.json boundary for existing Android account APIs; parsing lives in shared. */
object CatalogParser {
    fun objects(value: Any?, key: String): List<JSONObject> =
        CatalogJsonParser.objects(legacyCatalogJson(value), key).map { JSONObject(it.toString()) }
    fun text(value: JSONObject?): String = CatalogJsonParser.text(legacyCatalogJson(value) as? JsonObject)
    fun item(node: JSONObject, requested: CatalogKind? = null): CatalogItem? =
        CatalogJsonParser.item(legacyCatalogJson(node) as JsonObject, requested)
    fun continuation(root: JSONObject): String? = CatalogJsonParser.continuation(legacyCatalogJson(root) as JsonObject)
    fun search(root: JSONObject, requested: CatalogKind): CatalogPage = CatalogJsonParser.search(legacyCatalogJson(root) as JsonObject, requested)
    fun page(root: JSONObject, fallback: String = ""): CatalogPage = CatalogJsonParser.page(legacyCatalogJson(root) as JsonObject, fallback)
}

internal fun legacyCatalogJson(value: Any?): JsonElement? = when {
    value == null || value === JSONObject.NULL -> null
    value is JSONObject || value is org.json.JSONArray -> Json.parseToJsonElement(value.toString())
    else -> JsonPrimitive(value.toString())
}
