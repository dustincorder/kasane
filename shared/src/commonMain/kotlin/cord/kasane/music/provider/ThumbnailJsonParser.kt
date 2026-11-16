package cord.kasane.music.provider

import kotlinx.serialization.json.*

/** Reads only artwork fields, never thumbnails from a row's menus or related items. */
object ThumbnailJsonParser {
    private val wrappers = listOf("thumbnail", "thumbnailRenderer", "musicThumbnailRenderer",
        "croppedSquareThumbnailRenderer", "foregroundThumbnail", "image", "thumbnails")

    fun bestUrl(renderer: JsonElement?): String? {
        data class Candidate(val url: String, val area: Long)
        val candidates = mutableListOf<Candidate>()
        fun visit(value: JsonElement?) {
            when (value) {
                is JsonObject -> {
                    normalize(value.optString("url"))?.let { url ->
                        val width = value.optLong("width").coerceIn(0, 100_000)
                        val height = value.optLong("height").coerceIn(0, 100_000)
                        candidates.add(Candidate(url, width * height))
                    }
                    wrappers.forEach { name -> visit(value.opt(name)) }
                }
                is JsonArray -> value.forEach(::visit)
                else -> Unit
            }
        }
        visit(renderer)
        // Arrays are not guaranteed to be sorted, and the largest entry can have no URL.
        return candidates.withIndex().maxWithOrNull(compareBy<IndexedValue<Candidate>> { it.value.area }
            .thenBy { it.index })?.value?.url
    }

    private fun normalize(raw: String): String? {
        val value = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        val https = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", ignoreCase = true) -> "https://" + value.substring(7)
            else -> value
        }
        return validatedArtworkUrl(https)
    }
}

internal expect fun validatedArtworkUrl(value: String): String?
