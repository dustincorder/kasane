package cord.kasane.music.provider

import cord.kasane.music.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Renderer-aware parsing: browse IDs are never treated as playable video IDs. */
object CatalogParser {
    fun objects(value: Any?, key: String): List<JSONObject> {
        val found = mutableListOf<JSONObject>()
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject(key)?.let { found.add(it); return }
                    node.keys().forEach { visit(node.opt(it)) }
                }
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))
            }
        }
        visit(value)
        return found
    }
    fun text(value: JSONObject?): String = value?.optString("simpleText")?.takeIf { it.isNotEmpty() }
        ?: value?.optJSONArray("runs")?.let { runs ->
            (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
        }.orEmpty()
    private fun kind(id: String, pageType: String): CatalogKind? = when {
        pageType.contains("ARTIST") || id.startsWith("UC") -> CatalogKind.ARTIST
        pageType.contains("ALBUM") || id.startsWith("MPRE") -> CatalogKind.ALBUM
        pageType.contains("PLAYLIST") || id.startsWith("VL") -> CatalogKind.PLAYLIST
        else -> null
    }
    fun item(node: JSONObject, requested: CatalogKind? = null): CatalogItem? {
        val two = node.optJSONObject("musicTwoRowItemRenderer")
        val row = node.optJSONObject("musicResponsiveListItemRenderer")
        val body = two ?: row ?: return null
        val columns = body.optJSONArray("flexColumns")
        val titleObj = two?.optJSONObject("title") ?: columns?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        val subtitleObj = two?.optJSONObject("subtitle") ?: columns?.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        val title = text(titleObj)
        if (title.isBlank()) return null
        // Only the title/row navigation endpoint identifies this result. Subtitle endpoints are related entities.
        val endpoint = body.optJSONObject("navigationEndpoint")
            ?: titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
        val browse = endpoint?.optJSONObject("browseEndpoint")
        val video = endpoint?.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: body.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
        val id: String
        val type: CatalogKind
        if (browse != null) {
            id = browse.optString("browseId")
            val pageType = browse.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()
            type = kind(id, pageType) ?: return null
        } else if (video != null) {
            id = video
            type = if (requested == CatalogKind.VIDEO) CatalogKind.VIDEO else CatalogKind.SONG
        } else return null
        if (id.isBlank()) return null
        val fixed = body.optJSONArray("fixedColumns")?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")
        val duration = text(fixed).split(':').mapNotNull { it.toLongOrNull() }
            .fold(0L) { total, n -> total * 60 + n }.toDouble()
        return CatalogItem(id, type, title, text(subtitleObj), ThumbnailParser.bestUrl(body), duration)
    }
    private fun rows(value: Any?, requested: CatalogKind? = null): List<CatalogItem> {
        val result = mutableListOf<CatalogItem>()
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    if (node.has("musicResponsiveListItemRenderer") || node.has("musicTwoRowItemRenderer")) {
                        item(node, requested)?.let(result::add)
                    } else node.keys().forEach { visit(node.opt(it)) }
                }
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))
            }
        }
        visit(value)
        return result.distinctBy { it.kind to it.id }
    }
    fun continuation(root: JSONObject): String? =
        objects(root, "nextContinuationData").firstOrNull()?.optString("continuation")?.takeIf { it.isNotBlank() }
            ?: objects(root, "continuationCommand").firstOrNull()?.optString("token")?.takeIf { it.isNotBlank() }
    fun search(root: JSONObject, requested: CatalogKind): CatalogPage =
        CatalogPage("", "", null, listOf(CatalogShelf(requested.label, rows(root, requested).filter { it.kind == requested })), continuation(root))
    fun page(root: JSONObject, fallback: String = ""): CatalogPage {
        val header = sequenceOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer",
            "musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer")
            .flatMap { objects(root, it).asSequence() }.firstOrNull()
        val shelves = mutableListOf<CatalogShelf>()
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val shelf = listOf("musicCarouselShelfRenderer", "musicShelfRenderer", "musicPlaylistShelfRenderer",
                        "musicPlaylistShelfContinuation", "musicShelfContinuation", "gridRenderer")
                        .firstNotNullOfOrNull { node.optJSONObject(it) }
                    if (shelf != null) {
                        val items = rows(shelf.opt("contents") ?: shelf.opt("items"))
                        if (items.isNotEmpty()) {
                            val title = text(shelf.optJSONObject("title")).ifBlank {
                                objects(shelf.opt("header"), "title").firstOrNull()?.let(::text).orEmpty()
                            }
                            shelves.add(CatalogShelf(title, items))
                        }
                    } else node.keys().forEach { visit(node.opt(it)) }
                }
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))
            }
        }
        visit(root)
        if (shelves.isEmpty()) {
            val items = rows(root)
            if (items.isNotEmpty()) shelves.add(CatalogShelf("", items))
        }
        // A playlist can have a separate continuation for recommendations. Only the
        // playlist shelf's token can extend its track list.
        val playlistShelf = objects(root, "musicPlaylistShelfRenderer").firstOrNull()
            ?: objects(root, "musicPlaylistShelfContinuation").firstOrNull()
        val nextToken = if (playlistShelf != null) continuation(playlistShelf) else continuation(root)
        return CatalogPage(text(header?.optJSONObject("title")).ifBlank { fallback },
            text(header?.optJSONObject("subtitle")), header?.let(ThumbnailParser::bestUrl), shelves, nextToken,
            text(header?.optJSONObject("description")))
    }
}
