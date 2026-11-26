package cord.kasane.music.ota

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Update checks against GitHub Releases (default repo: dustincorder/kasane).
 * The channel comes from the build variant: [UpdateChannel.STABLE] follows regular
 * releases, [UpdateChannel.NIGHTLY] follows pre-releases. Automatic checks are
 * rate-limited through a persisted timestamp owned by the caller; manual checks
 * from settings always reach the API.
 */
class UpdateChecker(
    private val http: UpdateHttpTransport,
    private val channel: UpdateChannel,
    private val current: SemVersion,
    private val repo: String = "dustincorder/kasane",
    private val nowMs: () -> Long,
    /** At most one automatic check per this window; the persisted instant is read
     *  through [readLastCheckMs] and written through [writeLastCheckMs]. */
    private val autoIntervalMs: Long = 24 * 3_600_000L,
    private val readLastCheckMs: () -> Long = { 0 },
    private val writeLastCheckMs: (Long) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Startup check: a no-op until [autoIntervalMs] has passed since the last recorded check. */
    suspend fun checkIfDue(assetMatchers: List<String> = emptyList()): UpdateOutcome {
        val last = readLastCheckMs()
        if (last > 0 && nowMs() - last < autoIntervalMs) return UpdateOutcome.Skipped
        return fetch(assetMatchers)
    }

    /** Manual "check for updates" — always queries the API and re-arms the auto timer. */
    suspend fun checkNow(assetMatchers: List<String> = emptyList()): UpdateOutcome =
        fetch(assetMatchers)

    private suspend fun fetch(assetMatchers: List<String>): UpdateOutcome {
        val body = http.get(releasesUrl(), mapOf(
            "Accept" to "application/vnd.github+json",
            "User-Agent" to "kasane-update-checker",
        )) ?: return UpdateOutcome.Failed("Could not reach the update server.")
        writeLastCheckMs(nowMs())
        val releases = parseReleases(body)
            ?: return UpdateOutcome.Failed("The update server returned an unreadable response.")
        val wantPrerelease = channel == UpdateChannel.NIGHTLY
        val latest = releases.filter { it.prerelease == wantPrerelease }
            .maxWithOrNull(compareBy { it.version })
            ?: return UpdateOutcome.UpToDate(null)
        return if (latest.version > current)
            UpdateOutcome.Available(latest, selectAsset(latest, assetMatchers))
        else UpdateOutcome.UpToDate(latest)
    }

    /** Best asset for this platform: the first whose name contains every matcher, case-insensitive;
     *  a `-name` matcher requires the substring to be absent (channel/product exclusion). */
    fun selectAsset(release: ReleaseInfo, matchers: List<String>): ReleaseAsset? =
        release.assets.firstOrNull { a ->
            matchers.all { m ->
                if (m.startsWith("-")) !a.name.contains(m.drop(1), ignoreCase = true)
                else a.name.contains(m, ignoreCase = true)
            }
        }

    /**
     * Expected SHA-256 for a download: the asset `digest` when GitHub published it, else a
     * checksum file — `<name>.sha256`/`.sha256sum`, or a shared `SHA256SUMS` listing where
     * the matching line names this asset. Null = unverifiable.
     */
    suspend fun resolveChecksum(asset: ReleaseAsset, release: ReleaseInfo): String? {
        asset.sha256?.let { return it }
        val siblings = listOfNotNull(
            release.assets.firstOrNull { it.name == "${asset.name}.sha256" || it.name == "${asset.name}.sha256sum" },
            release.assets.firstOrNull { it.name.equals("SHA256SUMS", ignoreCase = true) || it.name.equals("SHA256SUMS.txt", ignoreCase = true) },
        )
        for (sibling in siblings) {
            val body = http.get(sibling.downloadUrl) ?: continue
            val line = body.lineSequence().firstOrNull { it.contains(asset.name) } ?: body
            SHA256_HEX.find(line)?.let { return it.value.lowercase() }
        }
        return null
    }

    private fun releasesUrl() = "https://api.github.com/repos/$repo/releases?per_page=30"

    /** Newest-first list from `GET /repos/{repo}/releases`; drafts are skipped. */
    fun parseReleases(body: String): List<ReleaseInfo>? = runCatching {
        (json.parseToJsonElement(body) as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o["draft"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val version = SemVersion.parse(tag) ?: return@mapNotNull null
            ReleaseInfo(
                tag = tag,
                version = version,
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: tag,
                changelog = o["body"]?.jsonPrimitive?.contentOrNull ?: "",
                htmlUrl = o["html_url"]?.jsonPrimitive?.contentOrNull ?: "",
                prerelease = o["prerelease"]?.jsonPrimitive?.booleanOrNull == true,
                assets = (o["assets"] as? JsonArray)?.mapNotNull(::parseAsset) ?: emptyList(),
            )
        }
    }.getOrNull()

    private fun parseAsset(el: kotlinx.serialization.json.JsonElement): ReleaseAsset? {
        val o = el as? JsonObject ?: return null
        val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val url = o["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
        val digest = o["digest"]?.jsonPrimitive?.contentOrNull
            ?.removePrefix("sha256:")?.takeIf { SHA256_HEX.matches(it) }
        return ReleaseAsset(name, url, o["size"]?.jsonPrimitive?.longOrNull ?: -1L, digest)
    }

    private companion object { val SHA256_HEX = Regex("[0-9a-fA-F]{64}") }
}
