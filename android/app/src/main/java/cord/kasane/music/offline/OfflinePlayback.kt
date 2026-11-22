package cord.kasane.music.offline

import androidx.media3.datasource.DataSource
import cord.kasane.music.provider.ResolvedAudioStream

data class LocalAudio(val cacheKey: String, val mimeType: String)

/** What the playback pipeline needs from the offline library. */
interface OfflinePlayback {
    val offlineOnly: Boolean
    /** A fully stored copy of this video, or null. Never touches the network. */
    suspend fun local(videoId: String): LocalAudio?
    /** A stream URL was resolved for streaming playback; remember its identity so a later play can skip the network. */
    fun resolved(videoId: String, audio: ResolvedAudioStream)
    fun playbackFactory(network: DataSource.Factory): DataSource.Factory
    fun cacheOnlyFactory(): DataSource.Factory
}
