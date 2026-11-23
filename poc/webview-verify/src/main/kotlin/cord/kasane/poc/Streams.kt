package cord.kasane.music.provider

// The two PlaybackStream shapes VerifiedPlayerResolver returns (Android defines more in PlaybackStream.kt).
sealed interface PlaybackStream
class ProgressiveStream(val audio: ResolvedAudioStream, val clientProfile: String) : PlaybackStream {
    override fun toString() = "ProgressiveStream(client=$clientProfile, url=[redacted])"
}
class UnavailableStream(val reason: String, val code: String = "NO_COMPATIBLE_STREAM") : PlaybackStream
