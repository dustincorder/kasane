package cord.kasane.music.provider

/** All thumbnail formats, size selection and URL rules live in shared code. */
object ThumbnailParser {
    fun bestUrl(renderer: Any?): String? = ThumbnailJsonParser.bestUrl(legacyCatalogJson(renderer))
}
