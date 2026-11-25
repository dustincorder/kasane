package cord.kasane.desktop.playback

import cord.kasane.desktop.offline.DesktopOffline
import java.io.IOException

/**
 * Offline-first stream resolution, like Android's `OfflinePlayback.local()` gate: a complete saved or
 * cached copy plays from a file with no request at all; Offline Only blocks streaming with a readable
 * error instead of trying the network.
 */
class OfflineStreamSource(private val delegate: StreamSource, private val offline: DesktopOffline) : StreamSource {
    @Volatile private var servedLocal = false
    override val lastSource: String get() = if (servedLocal) "offline" else delegate.lastSource
    override val lastSourceNote: String get() = delegate.lastSourceNote

    override suspend fun resolve(videoId: String, forceRefresh: Boolean): PlaybackStream {
        val file = offline.localFile(videoId)
        if (file != null) {
            servedLocal = true
            // mpv rejects the opaque `file:/path` form File.toURI produces; a plain path loads.
            return PlaybackStream(file.absolutePath)
        }
        servedLocal = false
        if (offline.offlineOnly) throw IOException("Offline Only is on. Songs saved on this computer still play.")
        return delegate.resolve(videoId, forceRefresh)
    }

    override fun consumeCutOff(videoId: String): Boolean = delegate.consumeCutOff(videoId)
}
