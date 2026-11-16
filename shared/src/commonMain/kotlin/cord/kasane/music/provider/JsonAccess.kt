package cord.kasane.music.provider

import kotlinx.serialization.json.*

// Read-only helpers keep provider traversal independent of Android org.json.
internal fun JsonObject.optJsonObject(key: String) = this[key] as? JsonObject
internal fun JsonObject.optJsonArray(key: String) = this[key] as? JsonArray
internal fun JsonObject.optString(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.optLong(key: String): Long = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L
internal fun JsonObject.opt(key: String) = this[key]
internal fun JsonObject.has(key: String) = containsKey(key)
internal fun JsonArray.optJsonObject(index: Int) = getOrNull(index) as? JsonObject
internal fun JsonArray.length() = size
internal fun JsonObject.optBoolean(key: String, default: Boolean = false): Boolean =
    when ((this[key] as? JsonPrimitive)?.contentOrNull?.lowercase()) { "true" -> true; "false" -> false; else -> default }
