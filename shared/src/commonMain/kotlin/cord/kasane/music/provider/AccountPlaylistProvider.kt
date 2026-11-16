package cord.kasane.music.provider

import cord.kasane.music.auth.AccountSession

/** These destinations come from YouTube's per-video picker, not from saved/non-owned playlists. */
data class PlaylistDestination(val id: String, val title: String, val privacy: String, val artwork: String?, val alreadyAdded: Boolean)

interface AccountPlaylistProvider {
    suspend fun playlistDestinations(account: AccountSession, videoId: String): List<PlaylistDestination>
    suspend fun addVideoToPlaylist(account: AccountSession, playlistId: String, videoId: String)
}
