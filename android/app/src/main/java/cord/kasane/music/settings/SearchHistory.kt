package cord.kasane.music.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Recent search queries, newest first. Stored only on this device and never sent anywhere. */
class SearchHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("kasane_search", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(decode(prefs.getString(KEY, null)))
    val recent: StateFlow<List<String>> = mutable.asStateFlow()

    fun add(query: String) = save(SearchHistoryPolicy.add(mutable.value, query))
    fun remove(query: String) = save(mutable.value.filterNot { it.equals(query, ignoreCase = true) })
    fun clear() = save(emptyList())

    private fun save(list: List<String>) { prefs.edit().putString(KEY, list.joinToString("\n")).apply(); mutable.value = list }
    private fun decode(raw: String?) = raw.orEmpty().split('\n').filter { it.isNotBlank() }.take(SearchHistoryPolicy.LIMIT)

    private companion object { const val KEY = "recent" }
}

object SearchHistoryPolicy {
    const val LIMIT = 8
    /** Newest first, case-insensitive de-duplication, single-line, bounded. */
    fun add(current: List<String>, query: String): List<String> {
        val clean = query.replace('\n', ' ').trim().take(120)
        if (clean.isEmpty()) return current
        return (listOf(clean) + current.filterNot { it.equals(clean, ignoreCase = true) }).take(LIMIT)
    }
}
