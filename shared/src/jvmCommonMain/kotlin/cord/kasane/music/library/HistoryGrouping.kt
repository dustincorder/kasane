package cord.kasane.music.library

import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One day of history entries, newest first. */
data class HistoryDaySection<T>(val label: String, val entries: List<T>)

/**
 * Local plays grouped into Today / Yesterday / localized date sections, newest day first.
 * Shared contract: Android passes `HistoryEntity` (`playedAt`, `id`), Desktop `HistoryEntry`
 * (`playedAt`, `track.id`) — the grouping rule is identical.
 */
fun <T> groupHistoryByDay(history: List<T>, playedAt: (T) -> Long, orderKey: (T) -> Comparable<*>,
    now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): List<HistoryDaySection<T>> {
    if (history.isEmpty()) return emptyList()
    val today = startOfDay(now)
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
    return history.sortedWith(compareByDescending(playedAt).thenByDescending(orderKey))
        .groupBy { startOfDay(playedAt(it)) }
        .entries.sortedByDescending { it.key }
        .map { (day, entries) ->
            val label = when ((today - day) / DAY_MS) {
                0L -> "Today"
                1L -> "Yesterday"
                else -> dateFormat.format(Date(day))
            }
            HistoryDaySection(label, entries)
        }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
