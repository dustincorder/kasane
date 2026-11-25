package cord.kasane.music.provider

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.lyrics.LyricsCandidate
import cord.kasane.music.lyrics.LyricsHttpTransport
import cord.kasane.music.lyrics.LyricsProvider
import cord.kasane.music.lyrics.LyricsQuery
import cord.kasane.music.lyrics.LyricsSyncKind

/** YouTube Music's own lyrics tab: static text only, so it ranks below timed sources. */
class YtmLyricsProvider(
    private val catalog: OnlineCatalog,
    private val session: () -> AccountSession?,
) : LyricsProvider {
    override val id = "ytmusic"
    override val label = "YouTube Music"
    override val supportedSyncKinds = setOf(LyricsSyncKind.UNSYNCED)

    override suspend fun fetch(query: LyricsQuery, http: LyricsHttpTransport): LyricsCandidate? {
        val lyrics = catalog.lyrics(query.trackId, session()) ?: return null
        return LyricsCandidate(id, label, sourceRef = query.trackId,
            title = query.title, artist = query.artist, album = query.album,
            durationMs = query.durationMs, rawFormat = "plain", rawPayload = lyrics.text)
    }
}
