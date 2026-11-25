package cord.kasane.desktop.playback

import cord.kasane.desktop.audio.StreamProxy
import cord.kasane.music.provider.GuestPlayerResolver
import cord.kasane.music.provider.IosHlsResolver
import cord.kasane.music.provider.ResolvedAudioStream
import cord.kasane.music.provider.verification.VerifiedPlayerResolver
import cord.kasane.music.provider.verification.VerifiedResult
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** One URL the engine can open: a loopback proxy URL, or a direct manifest with its own User-Agent. */
data class PlaybackStream(
    val url: String,
    val userAgent: String? = null,
    /** Watch-reporting endpoints from the player response; the player pings them when a play counts. */
    val playbackStatsUrl: String? = null,
    val watchtimeUrl: String? = null,
    val itag: Int = 0,
)

/** What the player asks for a playable stream; [StreamSelector] in the app, a fake in tests. */
interface StreamSource {
    /** Which path served the last resolve (verified, ios-hls, guest); for diagnostics. */
    val lastSource: String
    /** Why the verified path was skipped, when it was (no URLs or credentials in it). */
    val lastSourceNote: String
    suspend fun resolve(videoId: String, forceRefresh: Boolean): PlaybackStream
    /** True once if YouTube stopped serving [videoId] part-way (the engine then reports a normal end of file). */
    fun consumeCutOff(videoId: String): Boolean
}

/**
 * The desktop stream chain, in order: verified WEB_REMIX (BotGuard in the system WebKitGTK
 * helper — full length), iOS HLS when YouTube offers it, then the guest direct stream, which
 * YouTube may cut after its first part (detected via [StreamProxy.consumeCutOff], never
 * skipped silently).
 */
class StreamSelector(
    private val proxy: StreamProxy,
    private val guest: GuestPlayerResolver,
    private val hls: IosHlsResolver,
    private val verified: VerifiedPlayerResolver?,
) : StreamSource {
    @Volatile override var lastSource: String = ""
        private set
    @Volatile override var lastSourceNote: String = ""
        private set

    override suspend fun resolve(videoId: String, forceRefresh: Boolean): PlaybackStream {
        val verifiedResult = verified?.let { v ->
            runCatching { v.resolve(videoId) }.getOrElse {
                if (it is CancellationException) throw it
                lastSourceNote = "verification error: ${it.message}"
                null
            }
        }
        if (verifiedResult is VerifiedResult.Unavailable) lastSourceNote = "verification unavailable: ${verifiedResult.code} ${verifiedResult.reason}"
        if (verifiedResult is VerifiedResult.Verified) {
            lastSource = "verified"
            return PlaybackStream(proxy.register(videoId, verifiedResult.audio) {
                (verified.resolve(videoId) as? VerifiedResult.Verified)?.audio ?: throw IOException("verification expired")
            }, playbackStatsUrl = verifiedResult.audio.playbackStatsUrl,
                watchtimeUrl = verifiedResult.audio.watchtimeUrl, itag = verifiedResult.audio.itag)
        }
        val manifest = runCatching { hls.resolve(videoId) }.getOrElse {
            if (it is CancellationException) throw it
            null
        }
        if (manifest != null) {
            lastSource = "ios-hls"
            return PlaybackStream(manifest.url, manifest.userAgent,
                playbackStatsUrl = manifest.playbackStatsUrl, watchtimeUrl = manifest.watchtimeUrl)
        }
        lastSource = "guest"
        val stream = guest.resolve(videoId, forceRefresh = forceRefresh)
        return PlaybackStream(proxy.register(videoId, stream),
            playbackStatsUrl = stream.playbackStatsUrl, watchtimeUrl = stream.watchtimeUrl, itag = stream.itag)
    }

    override fun consumeCutOff(videoId: String): Boolean = proxy.consumeCutOff(videoId)

    /**
     * Progressive audio for saving offline: only the verified WEB_REMIX stream, like
     * Android's `resolveForSave` — the anonymous guest stream is not storable (YouTube
     * stops it part-way or demands sign-in) and HLS is streaming-only. Null means nothing
     * savable.
     */
    suspend fun resolveForSave(videoId: String): ResolvedAudioStream? {
        val verifiedResult = verified?.let { v ->
            runCatching { v.resolve(videoId) }.getOrElse { if (it is CancellationException) throw it; null }
        }
        return (verifiedResult as? VerifiedResult.Verified)?.audio
    }
}
