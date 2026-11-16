package cord.kasane.music.provider

import kotlinx.serialization.json.*

/** Reads only statuses scoped to the requested video, never an unrelated recommended row. */
object CloudLikeJsonParser {
    fun parse(root: JsonObject, videoId: String): CloudLikeState {
        fun status(value: String) = when (value) {
            "LIKE" -> CloudLikeState.LIKED
            "DISLIKE" -> CloudLikeState.DISLIKED
            "INDIFFERENT" -> CloudLikeState.NEUTRAL
            else -> CloudLikeState.UNKNOWN
        }
        for (renderer in CatalogJsonParser.objects(root, "likeButtonRenderer")) {
            val target = renderer.optJsonObject("target")?.optString("videoId")
                ?: renderer.optString("videoId")
            if (target == videoId) status(renderer.optString("likeStatus")).takeIf { it != CloudLikeState.UNKNOWN }?.let { return it }
        }
        // Player overlay is the current player, unlike the playlist/recommendation rows.
        root.optJsonObject("playerOverlays")?.let { overlays ->
            CatalogJsonParser.objects(overlays, "likeButtonRenderer").forEach { renderer ->
                val target = renderer.optJsonObject("target")?.optString("videoId").orEmpty()
                if (target.isEmpty() || target == videoId) {
                    val state = status(renderer.optString("likeStatus"))
                    if (state != CloudLikeState.UNKNOWN) return state
                }
            }
        }
        val toggles = mutableMapOf<String, Boolean>()
        for (renderer in CatalogJsonParser.objects(root, "toggleButtonRenderer")) {
            val endpoint = renderer.optJsonObject("defaultServiceEndpoint")?.optJsonObject("likeEndpoint") ?: continue
            if (endpoint.optJsonObject("target")?.optString("videoId") != videoId) continue
            val kind = endpoint.optString("status")
            if (kind in setOf("LIKE", "DISLIKE") && renderer.has("isToggled")) toggles[kind] = renderer.optBoolean("isToggled")
        }
        return when {
            toggles["LIKE"] == true -> CloudLikeState.LIKED
            toggles["DISLIKE"] == true -> CloudLikeState.DISLIKED
            toggles["LIKE"] == false && toggles["DISLIKE"] == false -> CloudLikeState.NEUTRAL
            else -> CloudLikeState.UNKNOWN
        }
    }
}
