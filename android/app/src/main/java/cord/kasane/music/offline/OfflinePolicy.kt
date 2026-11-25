package cord.kasane.music.offline

import cord.kasane.music.provider.HlsStream
import cord.kasane.music.provider.PlaybackStream
import cord.kasane.music.provider.ProgressiveStream
import cord.kasane.music.provider.SabrStream
import cord.kasane.music.provider.UnavailableStream

/**
 * Stream-dependent save rules — need the PlaybackStream sealed types which carry
 * Android transports. Everything else moved to `shared/commonMain/offline/OfflinePolicy.kt`.
 */
object StreamEligibility {
    sealed interface Eligibility {
        data object Direct : Eligibility
        data class NotSavable(val reason: String) : Eligibility
    }

    /** Only a plain progressive audio file can be stored byte for byte. HLS/SABR are streaming-only transports. */
    fun eligibility(stream: PlaybackStream): Eligibility = when (stream) {
        is ProgressiveStream ->
            if (stream.audio.contentLength > 0) Eligibility.Direct
            else Eligibility.NotSavable("YouTube did not report a file size for this track, so it cannot be verified as complete.")
        is HlsStream -> Eligibility.NotSavable("This track is delivered as a streaming-only format and cannot be saved offline.")
        is SabrStream -> Eligibility.NotSavable("This track is delivered as a streaming-only format and cannot be saved offline.")
        is UnavailableStream -> Eligibility.NotSavable(stream.reason.ifBlank { "This track is unavailable." })
    }

    /**
     * A resolve outcome caused by connectivity is worth retrying later; a playability or
     * format rejection is permanent. Transient codes: no HTTP response at all, rate limiting,
     * server errors and verifier failures (the verifier needs the network).
     */
    fun transient(stream: PlaybackStream): Boolean {
        val code = (stream as? UnavailableStream)?.code ?: return false
        if (code == "NETWORK_ERROR" || code == "SOURCE_ERROR" || code == "VERIFICATION_FAILED") return true
        val http = code.removePrefix("PLAYER_HTTP_").toIntOrNull() ?: return false
        return http == 429 || http >= 500
    }
}
