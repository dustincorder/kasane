package cord.kasane.music.data

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.CatalogPage
import cord.kasane.music.provider.AccountCollection

interface CatalogRemote {
    suspend fun home(account: AccountSession? = null): CatalogPage
    suspend fun browse(id: String, continuation: String? = null): CatalogPage
    suspend fun search(query: String, kind: CatalogKind, continuation: String? = null): CatalogPage
    suspend fun collectionFor(account: AccountSession, collection: AccountCollection, continuation: String? = null): CatalogPage
    suspend fun playlistFor(account: AccountSession, playlistId: String, continuation: String? = null): CatalogPage
}
