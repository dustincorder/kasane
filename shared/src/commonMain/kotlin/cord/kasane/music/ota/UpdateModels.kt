package cord.kasane.music.ota

/** Which GitHub releases this build follows: a build variant picks exactly one channel. */
enum class UpdateChannel {
    /** Public releases only (`prerelease=false`). */
    STABLE,
    /** Pre-releases only — nightly builds. */
    NIGHTLY,
}

/** Minimal transport so the checker is platform-free; OkHttp impl lives in jvmCommonMain. */
fun interface UpdateHttpTransport {
    /** GET body as text, or null on non-2xx/network failure. */
    suspend fun get(url: String, headers: Map<String, String>): String?
}

suspend fun UpdateHttpTransport.get(url: String): String? = get(url, emptyMap())

/** One downloadable file attached to a release. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** SHA-256 hex from the asset `digest` field; null when GitHub did not publish one
     *  (check for a `<name>.sha256` sibling asset via [UpdateChecker.resolveChecksum]). */
    val sha256: String?,
)

data class ReleaseInfo(
    val tag: String,
    val version: SemVersion,
    val name: String,
    /** Release body — Markdown changelog shown to the user. */
    val changelog: String,
    val htmlUrl: String,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

sealed interface UpdateOutcome {
    /** A newer release exists on this channel; [asset] is the best platform match, if any. */
    data class Available(val release: ReleaseInfo, val asset: ReleaseAsset?) : UpdateOutcome
    /** Nothing newer on this channel. [latest] is the newest release seen, when one exists. */
    data class UpToDate(val latest: ReleaseInfo?) : UpdateOutcome
    /** Automatic check held back by the rate limit — no request was sent. */
    data object Skipped : UpdateOutcome
    data class Failed(val reason: String) : UpdateOutcome
}
