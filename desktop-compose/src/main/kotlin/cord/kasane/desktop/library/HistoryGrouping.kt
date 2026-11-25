package cord.kasane.desktop.library

import cord.kasane.music.library.HistoryDaySection
import java.util.Locale

/**
 * Local plays grouped into Today / Yesterday / localized date sections, newest day first.
 * The grouping rule is shared (`jvmCommonMain`); this adapter binds [HistoryEntry] fields.
 */
fun groupHistoryByDay(history: List<HistoryEntry>, now: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault()): List<HistoryDaySection<HistoryEntry>> =
    cord.kasane.music.library.groupHistoryByDay(history, { it.playedAt }, { it.track.id }, now, locale)
