package cord.kasane.music.provider

import kotlinx.serialization.json.*

object PlaylistJsonParser {
    fun playlistId(value: String): String {
        val id=value.removePrefix("VL")
        require(id.matches(Regex("[A-Za-z0-9_-]{2,256}")) && id !in setOf("LM","LL")) {"Not an editable playlist identifier."}
        return id
    }
    fun destinations(root: JsonObject, videoId: String): List<PlaylistDestination> {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        val renderers=CatalogJsonParser.objects(root,"playlistAddToOptionRenderer")
        if(renderers.isEmpty() && CatalogJsonParser.objects(root,"addToPlaylistRenderer").isEmpty())
            throw CatalogFailure(CatalogFailureReason.INVALID_PLAYLIST_OPTIONS)
        if (renderers.size > 1000) throw CatalogFailure(CatalogFailureReason.TOO_MANY_PLAYLISTS)
        return renderers.mapNotNull { row->
            if(row.optBoolean("isDisabled"))return@mapNotNull null
            val id=runCatching {playlistId(row.optString("playlistId"))}.getOrNull() ?: return@mapNotNull null
            val title=CatalogJsonParser.text(row.optJsonObject("title")).takeIf {it.isNotBlank() && it.length<=1024} ?: return@mapNotNull null
            val membership=row.optString("containsSelectedVideos")
            if(membership !in setOf("ALL","NONE"))return@mapNotNull null
            if(membership=="NONE") {
                val endpoint=row.optJsonObject("addToPlaylistServiceEndpoint")?.optJsonObject("playlistEditEndpoint")
                    ?: row.optJsonObject("onSelectCommand")?.optJsonObject("playlistEditEndpoint") ?: return@mapNotNull null
                if(runCatching {playlistId(endpoint.optString("playlistId"))}.getOrNull()!=id)return@mapNotNull null
                val actions=endpoint.optJsonArray("actions") ?: return@mapNotNull null
                if(actions.length()!=1)return@mapNotNull null
                val action=actions.optJsonObject(0) ?: return@mapNotNull null
                if(action.optString("action")!="ACTION_ADD_VIDEO" || action.optString("addedVideoId")!=videoId)return@mapNotNull null
            }
            PlaylistDestination(id,title,row.optString("privacy").takeIf {it in setOf("PUBLIC","PRIVATE","UNLISTED")}.orEmpty(),
                ThumbnailJsonParser.bestUrl(row),membership=="ALL")
        }.distinctBy {it.id}
    }
    fun requireAddReceipt(root: JsonObject, videoId: String) {
        val status=root.optString("status")
        if(status.isNotBlank() && status!="STATUS_SUCCEEDED")throw CatalogFailure(CatalogFailureReason.PLAYLIST_REFUSED)
        val confirmed=CatalogJsonParser.objects(root,"playlistEditVideoAddedResultData").any { result->
            val returnedId=result.optString("videoId").ifBlank {result.optString("addedVideoId")}
            (returnedId.isEmpty() || returnedId==videoId) && result.optString("setVideoId").isNotBlank()
        }
        if(!confirmed)throw CatalogFailure(CatalogFailureReason.PLAYLIST_UNCONFIRMED)
    }
}
