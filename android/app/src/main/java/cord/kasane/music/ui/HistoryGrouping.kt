package cord.kasane.music.ui

import cord.kasane.music.model.HistoryEntity
import java.util.Locale

data class HistoryDaySection(val label: String, val entries: List<HistoryEntity>)

/** Local plays grouped into Today / Yesterday / localized date sections — shared rule lives in
 *  `shared/jvmCommonMain` ([cord.kasane.music.library.groupHistoryByDay]). */
fun groupHistoryByDay(history: List<HistoryEntity>, now: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault()): List<HistoryDaySection> =
    cord.kasane.music.library.groupHistoryByDay(history, { it.playedAt }, { it.id }, now, locale)
        .map { HistoryDaySection(it.label, it.entries) }
