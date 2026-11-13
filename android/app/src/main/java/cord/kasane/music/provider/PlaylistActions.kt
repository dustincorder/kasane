package cord.kasane.music.provider

import cord.kasane.music.auth.AccountSession
import org.json.JSONObject
import java.io.IOException

/** These destinations come from YouTube's per-video picker, not from saved/non-owned playlists. */
data class PlaylistDestination(val id: String, val title: String, val privacy: String, val artwork: String?, val alreadyAdded: Boolean)

interface AccountPlaylistProvider {
    suspend fun playlistDestinations(account: AccountSession, videoId: String): List<PlaylistDestination>
    suspend fun addVideoToPlaylist(account: AccountSession, playlistId: String, videoId: String)
}

object PlaylistActionParser {
    fun playlistId(value: String): String {
        val id=value.removePrefix("VL")
        require(id.matches(Regex("[A-Za-z0-9_-]{2,256}")) && id !in setOf("LM","LL")) {"Not an editable playlist identifier."}
        return id
    }
    fun destinations(root: JSONObject, videoId: String): List<PlaylistDestination> {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        val renderers=CatalogParser.objects(root,"playlistAddToOptionRenderer")
        if(renderers.isEmpty() && CatalogParser.objects(root,"addToPlaylistRenderer").isEmpty())
            throw IOException("YouTube Music did not return a supported playlist picker.")
        if (renderers.size > 1000) throw IOException("YouTube Music returned too many playlist choices. Open a smaller collection and retry.")
        return renderers.mapNotNull { row->
            if(row.optBoolean("isDisabled"))return@mapNotNull null
            val id=runCatching {playlistId(row.optString("playlistId"))}.getOrNull() ?: return@mapNotNull null
            val title=CatalogParser.text(row.optJSONObject("title")).takeIf {it.isNotBlank() && it.length<=1024} ?: return@mapNotNull null
            val membership=row.optString("containsSelectedVideos")
            if(membership !in setOf("ALL","NONE"))return@mapNotNull null
            if(membership=="NONE") {
                val endpoint=row.optJSONObject("addToPlaylistServiceEndpoint")?.optJSONObject("playlistEditEndpoint")
                    ?: row.optJSONObject("onSelectCommand")?.optJSONObject("playlistEditEndpoint") ?: return@mapNotNull null
                if(runCatching {playlistId(endpoint.optString("playlistId"))}.getOrNull()!=id)return@mapNotNull null
                val actions=endpoint.optJSONArray("actions") ?: return@mapNotNull null
                if(actions.length()!=1)return@mapNotNull null
                val action=actions.optJSONObject(0) ?: return@mapNotNull null
                if(action.optString("action")!="ACTION_ADD_VIDEO" || action.optString("addedVideoId")!=videoId)return@mapNotNull null
            }
            PlaylistDestination(id,title,row.optString("privacy").takeIf {it in setOf("PUBLIC","PRIVATE","UNLISTED")}.orEmpty(),
                ThumbnailParser.bestUrl(row),membership=="ALL")
        }.distinctBy {it.id}
    }
    fun requireAddReceipt(root: JSONObject, videoId: String) {
        val status=root.optString("status")
        if(status.isNotBlank() && status!="STATUS_SUCCEEDED")throw IOException("YouTube Music refused the playlist change.")
        val confirmed=CatalogParser.objects(root,"playlistEditVideoAddedResultData").any { result->
            val returnedId=result.optString("videoId").ifBlank {result.optString("addedVideoId")}
            (returnedId.isEmpty() || returnedId==videoId) && result.optString("setVideoId").isNotBlank()
        }
        if(!confirmed)throw IOException("YouTube Music did not confirm the addition. Refresh the playlist before retrying.")
    }
}
