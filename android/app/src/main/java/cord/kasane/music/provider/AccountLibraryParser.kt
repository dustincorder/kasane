package cord.kasane.music.provider

import cord.kasane.music.model.CatalogPage
import org.json.JSONObject
import java.io.IOException

/** A malformed/unsupported response is not an empty personal library. */
object AccountLibraryParser {
    fun page(root: JSONObject): CatalogPage {
        val supported = listOf("sectionListRenderer", "gridRenderer", "gridContinuation", "musicShelfRenderer",
            "musicCarouselShelfRenderer", "musicPlaylistShelfRenderer", "musicShelfContinuation",
            "musicPlaylistShelfContinuation", "appendContinuationItemsAction", "musicResponsiveListItemRenderer",
            "musicTwoRowItemRenderer", "messageRenderer")
            .any { CatalogParser.objects(root, it).isNotEmpty() }
        if (!supported) throw IOException("YouTube Music returned an unsupported library response. Refresh to try again.")
        return CatalogParser.page(root)
    }
}
