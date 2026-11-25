package cord.kasane.music.provider

import cord.kasane.music.auth.AccountSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject

data class TrackLyrics(val text: String, val source: String?)

/**
 * YouTube Music lyrics for a track: the `next` response's tabbed renderer names the lyrics
 * browse id, and browsing it returns a description shelf with the text and its source.
 * Null when YouTube has no lyrics tab for the video.
 */
suspend fun OnlineCatalog.lyrics(videoId: String, account: AccountSession? = null): TrackLyrics? =
    withContext(Dispatchers.Default) {
        val next = Json.parseToJsonElement(request("next", JSONObject().put("videoId", videoId), account).toString()) as JsonObject
        val browseId = TrackLyricsParser.lyricsBrowseId(next) ?: return@withContext null
        val page = Json.parseToJsonElement(request("browse", JSONObject().put("browseId", browseId), account).toString()) as JsonObject
        TrackLyricsParser.lyrics(page)
    }

object TrackLyricsParser {
    fun lyricsBrowseId(next: JsonObject): String? {
        fun tabBrowseId(node: JsonObject): String? =
            node["tabRenderer"]?.jsonObject?.takeIf {
                it["pageType"]?.jsonPrimitive?.content == "MUSIC_PAGE_TYPE_TRACK_LYRICS"
            }?.get("endpoint")?.jsonObject?.get("browseEndpoint")?.jsonObject?.get("browseId")?.jsonPrimitive?.content
        val root = next["contents"]?.jsonObject
            ?.get("singleColumnMusicWatchNextResultsRenderer")?.jsonObject
            ?.get("tabbedRenderer")?.jsonObject
            ?.get("watchNextTabbedResultsRenderer")?.jsonObject
        return root?.get("tabs")?.jsonArray?.firstNotNullOfOrNull { (it as? JsonObject)?.let(::tabBrowseId) }
    }

    fun lyrics(page: JsonObject): TrackLyrics? {
        val shelves = page["contents"]?.jsonObject?.get("sectionListRenderer")?.jsonObject
            ?.get("contents")?.jsonArray ?: return null
        for (entry in shelves) {
            val shelf = (entry as? JsonObject)?.get("musicDescriptionShelfRenderer")?.jsonObject ?: continue
            val text = shelf["description"]?.jsonObject?.let(::runsText).orEmpty().trim()
            if (text.isBlank()) continue
            val source = shelf["footer"]?.jsonObject?.let(::runsText)?.trim()
                ?.removePrefix("Source:")?.removePrefix("Source")?.trim()?.takeIf { it.isNotBlank() }
            return TrackLyrics(text, source)
        }
        return null
    }

    private fun runsText(node: JsonObject): String =
        node["runs"]?.jsonArray?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }?.joinToString("")
            ?: node["text"]?.jsonPrimitive?.content.orEmpty()
}
