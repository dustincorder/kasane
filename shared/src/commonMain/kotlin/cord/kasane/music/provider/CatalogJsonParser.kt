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
    internal class Byline(val artist: String?, val album: String?)

    private val TYPE_LABELS = setOf("Song", "Video", "Single", "EP", "Album", "Playlist", "Artist")
    private val NOT_AN_ARTIST = Regex("(?i)\\b(plays?|views?|listeners?|subscribers?|songs?|tracks?)\\b|^\\d+(:\\d+)+$")

    /** Splits a byline's runs at its " • " separators into groups: [artists], [album], [year/duration]... */
    internal fun bylineParts(source: JsonObject?): List<List<JsonObject>> {
        val groups = mutableListOf<MutableList<JsonObject>>(mutableListOf())
        source?.optJsonArray("runs")?.forEach { run ->
            val obj = run as? JsonObject ?: return@forEach
            if (obj.optString("text").trim() == "•") groups += mutableListOf<JsonObject>() else groups.last() += obj
        }
        return groups.filter { it.isNotEmpty() }
    }
    private fun partText(part: List<JsonObject>) = part.joinToString("") { it.optString("text") }.trim()
    private fun linksTo(part: List<JsonObject>, prefix: String) = part.any {
        it.optJsonObject("navigationEndpoint")?.optJsonObject("browseEndpoint")?.optString("browseId").orEmpty().startsWith(prefix)
    }
    /** Artist and album named by a song row, from the structure YouTube sends rather than by guessing at free text. */
    private fun bylineFromParts(parts: List<List<JsonObject>>, extraAlbum: List<JsonObject>? = null): Byline {
        val useful = parts.dropWhile { part -> part.size == 1 && partText(part) in TYPE_LABELS }
        val artistPart = useful.firstOrNull()?.takeIf { part -> linksTo(part, "UC") || (partText(part).isNotBlank() && !NOT_AN_ARTIST.containsMatchIn(partText(part)) && !linksTo(part, "MPRE")) }
        val albumPart = useful.drop(if (artistPart != null) 1 else 0).firstOrNull { linksTo(it, "MPRE") } ?: extraAlbum?.takeIf { linksTo(it, "MPRE") }
        return Byline(artistPart?.let(::partText), albumPart?.let(::partText))
    }
    internal fun rowByline(columns: kotlinx.serialization.json.JsonArray?): Byline {
        fun col(i: Int) = columns?.optJsonObject(i)?.optJsonObject("musicResponsiveListItemFlexColumnRenderer")?.optJsonObject("text")
        return bylineFromParts(bylineParts(col(1)), col(2)?.optJsonArray("runs")?.mapNotNull { it as? JsonObject })
    }
    /** Names and ids of every artist/album/channel run in a renderer, in order. */
    internal fun linkedRuns(root: JsonElement?): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        fun visit(node: JsonElement?) {
            when (node) {
                is JsonObject -> {
                    val id = node.optJsonObject("navigationEndpoint")?.optJsonObject("browseEndpoint")?.optString("browseId")
                    if (!id.isNullOrBlank() && node.optString("text").isNotBlank()) out += node.optString("text") to id
                    node.values.forEach(::visit)
                }
                is JsonArray -> node.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return out
    }
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
        val byline = if (row != null && type == CatalogKind.SONG) rowByline(columns) else null
        return CatalogItem(id, type, title, if (row != null && type == CatalogKind.SONG) byline?.artist.orEmpty() else text(subtitleObj), ThumbnailJsonParser.bestUrl(body), duration, album = byline?.album)
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
            if (id.isBlank()) null else {
                // longBylineText is "Artist • Album • Year": split it instead of storing the whole line as the artist.
                val by = bylineFromParts(bylineParts(it.optJsonObject("longBylineText")))
                Track(id, text(it.optJsonObject("title")), by.artist ?: text(it.optJsonObject("shortBylineText")), by.album,
                    thumbnailUri = ThumbnailJsonParser.bestUrl(it.optJsonObject("thumbnail")))
            }
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
        val creators = creators(header)
        val pageTitle = text(header?.optJsonObject("title")).ifBlank { fallback }
        val kind = text(header?.optJsonObject("subtitle")).substringBefore('•').trim()
        // An album's own rows carry no artist or album column; the album page itself names both.
        val filled = if (kind in setOf("Album", "Single", "EP") && pageTitle.isNotBlank()) shelves.map { shelf ->
            shelf.copy(items = shelf.items.map { item ->
                if (item.kind != CatalogKind.SONG) item else item.copy(
                    subtitle = item.subtitle.ifBlank { creators.joinToString(", ") { it.name } }, album = item.album ?: pageTitle)
            })
        } else shelves
        return CatalogPage(pageTitle,
            text(header?.optJsonObject("subtitle")), header?.let(ThumbnailJsonParser::bestUrl), filled, nextToken,
            text(header?.optJsonObject("description")), chips(root),
            creators, text(header?.optJsonObject("secondSubtitle")),
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
