package cord.kasane.music.provider

import cord.kasane.music.model.CatalogPage

enum class AccountCollection(val label: String, val browseId: String) {
    LIKES("Liked songs", "VLLM"),
    PLAYLISTS("Playlists", "FEmusic_liked_playlists"),
    ALBUMS("Saved albums", "FEmusic_liked_albums"),
    ARTISTS("Subscriptions", "FEmusic_library_corpus_artists")
}
interface AccountLibraryProvider {
    suspend fun collection(collection: AccountCollection, continuation: String? = null): CatalogPage
    suspend fun setLiked(videoId: String, liked: Boolean)
}
