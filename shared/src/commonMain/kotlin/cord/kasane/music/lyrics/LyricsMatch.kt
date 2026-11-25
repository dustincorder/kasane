package cord.kasane.music.lyrics

/**
 * Title/artist normalization shared by lyrics providers: feature-markers and bracketed
 * descriptors removed so a YouTube-side title can meet a provider-side one, plus token
 * sets for artist overlap checks. Matching stays conservative: a weaker title key only
 * helps when the artist overlap still confirms the same recording.
 */
object LyricsMatch {
    private val FEATURED = Regex("(?i)\\s*[(\\[](feat|ft|with|featuring)\\..*?[)\\]]|\\s+(feat|ft)\\.\\s+.*$")
    private val BRACKETED = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val JOINER_WORDS = setOf("feat", "ft", "featuring", "with", "vs", "and", "the")

    /** Full normalization: case-folded, feature-markers removed, punctuation collapsed to spaces. */
    fun normalize(value: String): String = value.lowercase()
        .replace(FEATURED, "")
        .replace(NON_WORD, " ")
        .trim()

    /**
     * A weaker key: all bracketed parts removed, so "Title (Live)", "Title [Remastered]" and
     * "Title" collide. Only meaningful together with an artist overlap check.
     */
    fun coreTitle(value: String): String = value.lowercase()
        .replace(FEATURED, "")
        .replace(BRACKETED, " ")
        .replace(NON_WORD, " ")
        .trim()

    /** Artist names as a comparable token set; joiner words and separators never decide a match. */
    fun artistTokens(value: String): Set<String> =
        normalize(value).split(' ').filter { it.isNotBlank() && it !in JOINER_WORDS }.toSet()

    fun artistsOverlap(a: String, b: String): Boolean {
        val left = artistTokens(a); val right = artistTokens(b)
        return left.isNotEmpty() && right.isNotEmpty() && left.any { it in right }
    }

    /** Same recording for lyrics purposes: same normalized or core title plus an artist overlap. */
    fun sameRecording(queryTitle: String, queryArtist: String, candidateTitle: String, candidateArtist: String?): Boolean {
        val titleHit = normalize(candidateTitle) == normalize(queryTitle) || coreTitle(candidateTitle) == coreTitle(queryTitle)
        if (!titleHit) return false
        if (queryArtist.isBlank() || candidateArtist.isNullOrBlank()) return true
        return artistsOverlap(queryArtist, candidateArtist)
    }

    /** Absolute duration difference in ms; unknown durations never disqualify. */
    fun durationClose(candidateMs: Long?, queryMs: Long?, toleranceMs: Long = 5_000): Boolean =
        candidateMs == null || queryMs == null || kotlin.math.abs(candidateMs - queryMs) <= toleranceMs
}
