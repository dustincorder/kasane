package cord.kasane.music.provider

import org.json.JSONObject

/** Existing Android entrypoint delegates status semantics to common code.
 *  The [CloudLikes] controller itself lives in `shared/commonMain`. */
object CloudLikeParser {
    fun parse(root: JSONObject, videoId: String): CloudLikeState =
        CloudLikeJsonParser.parse(legacyCatalogJson(root) as kotlinx.serialization.json.JsonObject, videoId)
}
