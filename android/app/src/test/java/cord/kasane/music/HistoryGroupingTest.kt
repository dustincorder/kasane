package cord.kasane.music

import cord.kasane.music.model.HistoryEntity
import cord.kasane.music.ui.groupHistoryByDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class HistoryGroupingTest {
    private fun at(hourOffset: Int, daysAgo: Int = 0, base: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = base; add(Calendar.DAY_OF_YEAR, -daysAgo) }
        cal.set(Calendar.HOUR_OF_DAY, 12); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
        return cal.timeInMillis + hourOffset * 3600_000L
    }
    private val now = System.currentTimeMillis()

    @Test fun emptyHistoryProducesNoSections() {
        assertTrue(groupHistoryByDay(emptyList(), now).isEmpty())
    }

    @Test fun todayYesterdayAndOlderDatesGroupInOrder() {
        val entries = listOf(
            HistoryEntity(id = 1, trackId = "a", title = "A", artist = "X", playedAt = at(2, 0, now)),
            HistoryEntity(id = 2, trackId = "b", title = "B", artist = "X", playedAt = at(1, 1, now)),
            HistoryEntity(id = 3, trackId = "c", title = "C", artist = "X", playedAt = at(3, 1, now)),
            HistoryEntity(id = 4, kind = "mix", trackId = "d", title = "Mix — D", artist = "X", playedAt = at(0, 4, now)))
        val days = groupHistoryByDay(entries, now, Locale.US)
        assertEquals(listOf("Today", "Yesterday"), days.take(2).map { it.label })
        assertEquals(1, days[0].entries.size)
        assertEquals(listOf(3L, 2L), days[1].entries.map { it.id }) // newest first within a day
        assertEquals(3, days.size)
        assertEquals("mix", days[2].entries.single().kind)
        assertTrue(days[2].label.isNotBlank())
    }

    @Test fun sameInstantEntriesKeepStableOrder() {
        val entries = (1..3).map { HistoryEntity(id = it.toLong(), trackId = "t$it", title = "T$it", artist = "X", playedAt = now) }
        val days = groupHistoryByDay(entries, now, Locale.US)
        assertEquals(1, days.size)
        assertEquals(listOf(3L, 2L, 1L), days[0].entries.map { it.id })
    }
}
