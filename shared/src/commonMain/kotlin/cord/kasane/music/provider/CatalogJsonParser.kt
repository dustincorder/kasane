package cord.kasane.music.provider

import cord.kasane.music.model.*
import kotlinx.serialization.json.*

/** Renderer-aware parsing: browse IDs are never treated as playable video IDs. */
object CatalogJsonParser {
    fun objects(value: JsonElement?, key: String): List<JsonObject> {
        val found = mutableListOf<JsonObject>()
        fun visit(node: JsonElement?) {
            when (node) {
                is JsonObject -> {
                    node.optJsonObject(key)?.let { found.add(it); return }
                    node.values.forEach { visit(it) }
                }
                is JsonArray -> node.forEach(::visit)
                else -> Unit
            }
        }
        visit(value)
        return found
    }
    fun text(value: JsonObject?): String = value?.optString("simpleText")?.takeIf { it.isNotEmpty() }
        ?: value?.optJsonArray("runs")?.let { runs ->
            (0 until runs.length()).joinToString("") { runs.optJsonObject(it)?.optString("text").orEmpty() }
        }.orEmpty()
    private fun kind(id: String, pageType: String): CatalogKind? = when {
        pageType.contains("ARTIST") || id.startsWith("UC") -> CatalogKind.ARTIST
        pageType.contains("ALBUM") || id.startsWith("MPRE") -> CatalogKind.ALBUM
        pageType.contains("PLAYLIST") || id.startsWith("VL") -> CatalogKind.PLAYLIST
        else -> null
    }
    fun item(node: JsonObject, requested: CatalogKind? = null): CatalogItem? {
        val two = node.optJsonObject("musicTwoRowItemRenderer")
        val row = node.optJsonObject("musicResponsiveListItemRenderer")
        val body = two ?: row ?: return null
        val columns = body.optJsonArray("flexColumns")
        val titleObj = two?.optJsonObject("title") ?: columns?.optJsonObject(0)
            ?.optJsonObject("musicResponsiveListItemFlexColumnRenderer")?.optJsonObject("text")
        val subtitleObj = two?.optJsonObject("subtitle") ?: columns?.optJsonObject(1)
            ?.optJsonObject("musicResponsiveListItemFlexColumnRenderer")?.optJsonObject("text")
        val title = text(titleObj)
        if (title.isBlank()) return null
        // Only the title/row navigation endpoint identifies this result. Subtitle endpoints are related entities.
        val endpoint = body.optJsonObject("navigationEndpoint")
            ?: titleObj?.optJsonArray("runs")?.optJsonObject(0)?.optJsonObject("navigationEndpoint")
        val browse = endpoint?.optJsonObject("browseEndpoint")
        val video = endpoint?.optJsonObject("watchEndpoint")?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: body.optJsonObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
        val id: String
        val type: CatalogKind
        if (browse != null) {
            id = browse.optString("browseId")
            val pageType = browse.optJsonObject("browseEndpointContextSupportedConfigs")
                ?.optJsonObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()
            type = kind(id, pageType) ?: return null
        } else if (video != null) {
            id = video
            type = if (requested == CatalogKind.VIDEO) CatalogKind.VIDEO else CatalogKind.SONG
        } else return null
        if (id.isBlank()) return null
        val fixed = body.optJsonArray("fixedColumns")?.optJsonObject(0)
            ?.optJsonObject("musicResponsiveListItemFixedColumnRenderer")?.optJsonObject("text")
        val duration = text(fixed).split(':').mapNotNull { it.toLongOrNull() }
            .fold(0L) { total, n -> total * 60 + n }.toDouble()
        return CatalogItem(id, type, title, text(subtitleObj), ThumbnailJsonParser.bestUrl(body), duration)
    }
    private fun rows(value: JsonElement?, requested: CatalogKind? = null): List<CatalogItem> {
        val result = mutableListOf<CatalogItem>()
        fun visit(node: JsonElement?) {
            when (node) {
                is JsonObject -> {
                    if (node.has("musicResponsiveListItemRenderer") || node.has("musicTwoRowItemRenderer")) {
                        item(node, requested)?.let(result::add)
                    } else node.values.forEach { visit(it) }
                }
                is JsonArray -> node.forEach(::visit)
                else -> Unit
            }
        }
        visit(value)
        return result.distinctBy { it.kind to it.id }
    }
    fun continuation(root: JsonObject): String? =
        objects(root, "nextContinuationData").firstOrNull()?.optString("continuation")?.takeIf { it.isNotBlank() }
            ?: objects(root, "continuationCommand").firstOrNull()?.optString("token")?.takeIf { it.isNotBlank() }
    fun search(root: JsonObject, requested: CatalogKind): CatalogPage =
        CatalogPage("", "", null, listOf(CatalogShelf(requested.label, rows(root, requested).filter { it.kind == requested })), continuation(root))
    fun libraryPage(root: JsonObject): CatalogPage {
        val supported = listOf("sectionListRenderer", "gridRenderer", "gridContinuation", "musicShelfRenderer",
            "musicCarouselShelfRenderer", "musicPlaylistShelfRenderer", "musicShelfContinuation",
            "musicPlaylistShelfContinuation", "appendContinuationItemsAction", "musicResponsiveListItemRenderer",
            "musicTwoRowItemRenderer", "messageRenderer").any { objects(root, it).isNotEmpty() }
        if (!supported) throw CatalogFailure(CatalogFailureReason.INVALID_LIBRARY)
        return page(root)
    }
    fun radio(root: JsonObject): RadioPage {
        val tracks = objects(root, "playlistPanelVideoRenderer").mapNotNull {
            val id = it.optString("videoId")
            if (id.isBlank()) null else Track(id, text(it.optJsonObject("title")),
                text(it.optJsonObject("longBylineText")),
                thumbnailUri = ThumbnailJsonParser.bestUrl(it.optJsonObject("thumbnail")))
        }.distinctBy { it.id }
        val token = objects(root, "nextRadioContinuationData").firstOrNull()
            ?.optString("continuation")?.takeIf { it.isNotBlank() } ?: continuation(root)
        return RadioPage(tracks, token)
    }
    fun page(root: JsonObject, fallback: String = ""): CatalogPage {
        val header = sequenceOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer",
            "musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer")
            .flatMap { objects(root, it).asSequence() }.firstOrNull()
        val shelves = mutableListOf<CatalogShelf>()
        fun visit(node: JsonElement?) {
            when (node) {
                is JsonObject -> {
                    val shelf = listOf("musicCarouselShelfRenderer", "musicShelfRenderer", "musicPlaylistShelfRenderer",
                        "musicPlaylistShelfContinuation", "musicShelfContinuation", "gridRenderer")
                        .firstNotNullOfOrNull { node.optJsonObject(it) }
                    if (shelf != null) {
                        val items = rows(shelf.opt("contents") ?: shelf.opt("items"))
                        if (items.isNotEmpty()) {
                            val title = text(shelf.optJsonObject("title")).ifBlank {
                                objects(shelf.opt("header"), "title").firstOrNull()?.let(::text).orEmpty()
                            }
                            val more = objects(shelf.opt("header"), "moreContentButton").firstOrNull()
                                ?.let { objects(it, "browseEndpoint").firstOrNull()?.optString("browseId") }?.takeIf { it.isNotBlank() }
                            shelves.add(CatalogShelf(title, items, more))
                        }
                    } else node.values.forEach { visit(it) }
                }
                is JsonArray -> node.forEach(::visit)
                else -> Unit
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
        return CatalogPage(text(header?.optJsonObject("title")).ifBlank { fallback },
            text(header?.optJsonObject("subtitle")), header?.let(ThumbnailJsonParser::bestUrl), shelves, nextToken,
            text(header?.optJsonObject("description")), chips(root),
            creators(header), text(header?.optJsonObject("secondSubtitle")),
            objects(root, "musicEditablePlaylistDetailHeaderRenderer").isNotEmpty())
    }

    private val SEPARATORS = setOf("&", ",", "•", "and", "·")

    /**
     * Creators from the header: the strapline (albums, most playlists) or the owner facepile (playlists), else
     * artist/channel links inside the subtitle (older album headers). Nothing is inferred from track artists.
     */
    fun creators(header: JsonObject?): List<CatalogCreator> {
        if (header == null) return emptyList()
        fun kindOf(endpoint: JsonObject?): Pair<String?, Boolean> {
            val browse = endpoint?.let { objects(it, "browseEndpoint").firstOrNull() } ?: return null to false
            val id = browse.optString("browseId").takeIf { it.isNotBlank() } ?: return null to false
            val type = objects(browse, "browseEndpointContextMusicConfig").firstOrNull()?.optString("pageType").orEmpty()
            return if (type == "MUSIC_PAGE_TYPE_ARTIST" || type == "MUSIC_PAGE_TYPE_USER_CHANNEL" || id.startsWith("UC")) id to (type == "MUSIC_PAGE_TYPE_USER_CHANNEL") else null to false
        }
        fun fromRuns(source: JsonObject?): List<CatalogCreator> = source?.optJsonArray("runs").orEmpty().mapNotNull { run ->
            val name = (run as? JsonObject)?.optString("text")?.trim().orEmpty()
            if (name.isBlank() || name in SEPARATORS) return@mapNotNull null
            val (id, user) = kindOf((run as JsonObject).optJsonObject("navigationEndpoint"))
            CatalogCreator(name, id, null, user)
        }
        val strap = fromRuns(header.optJsonObject("straplineTextOne"))
        if (strap.isNotEmpty()) {
            val avatar = ThumbnailJsonParser.bestUrl(header.optJsonObject("straplineThumbnail"))
            return if (strap.size == 1 && avatar != null) listOf(strap[0].copy(avatar = avatar)) else strap
        }
        val facepile = objects(header.optJsonObject("facepile"), "avatarStackViewModel").firstOrNull()
        if (facepile != null) {
            val name = facepile.optJsonObject("text")?.optString("content").orEmpty().trim()
            if (name.isNotBlank()) {
                val (id, user) = kindOf(facepile.optJsonObject("rendererContext"))
                val url = facepile.optJsonArray("avatars")?.optJsonObject(0)?.let { a ->
                    objects(a, "image").firstOrNull()?.optJsonArray("sources")?.optJsonObject(0)?.optString("url")
                }?.takeIf { it.isNotBlank() && it.startsWith("https://") }
                return listOf(CatalogCreator(name, id, url, user))
            }
        }
        return fromRuns(header.optJsonObject("subtitle")).filter { it.browseId != null }
    }
    /** Mood and activity filters. Only chips that carry a browse token are usable; the rest are ignored. */
    fun chips(root: JsonObject): List<CatalogChip> = objects(root, "chipCloudChipRenderer").mapNotNull { chip ->
        val params = objects(chip.optJsonObject("navigationEndpoint"), "browseEndpoint").firstOrNull()?.optString("params")
        val title = text(chip.optJsonObject("text"))
        if (params.isNullOrBlank() || title.isBlank()) null else CatalogChip(title, params)
    }.distinctBy { it.params }
}
