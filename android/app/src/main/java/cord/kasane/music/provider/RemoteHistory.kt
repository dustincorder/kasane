package cord.kasane.music.provider

import cord.kasane.music.auth.AccountSession
import cord.kasane.music.model.CatalogPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/** The signed-in YouTube Music history feed (browse id FEmusic_history), day-grouped by the service itself. */
suspend fun OnlineCatalog.accountHistory(account: AccountSession): CatalogPage = withContext(Dispatchers.Default) {
    val response = request("browse", JSONObject().put("browseId", "FEmusic_history"), account)
    CatalogJsonParser.page(Json.parseToJsonElement(response.toString()) as JsonObject)
}
