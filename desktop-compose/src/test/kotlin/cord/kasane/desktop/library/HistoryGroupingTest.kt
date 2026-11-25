package cord.kasane.desktop.library

import cord.kasane.music.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Day-bucket semantics shared with Android's history screen: Today / Yesterday / dated, newest first. */
class HistoryGroupingTest {
    private fun entry(id: String, at: Long, kind: String = "track") = HistoryEntry(Track(id, "T$id", "A"), at, kind)

    private val now = 1_800_000_000_000L // arbitrary fixed instant

    @Test fun sameDayEntriesFormToday() {
        val day = java.util.Calendar.getInstance().apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 12) }.timeInMillis
        val sections = groupHistoryByDay(listOf(entry("a", day + 60_000), entry("b", day)), now)
        assertEquals(1, sections.size)
        assertEquals("Today", sections[0].label)
        assertEquals(listOf("a", "b"), sections[0].entries.map { it.track.id })
    }

    @Test fun yesterdayAndOlderDaysGetTheirOwnSections() {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val yesterday = cal.apply { add(java.util.Calendar.DAY_OF_YEAR, -1); set(java.util.Calendar.HOUR_OF_DAY, 15) }.timeInMillis
        cal.timeInMillis = now; cal.add(java.util.Calendar.DAY_OF_YEAR, -4)
        val older = cal.timeInMillis
        val sections = groupHistoryByDay(listOf(entry("old", older), entry("new", now), entry("yes", yesterday)), now)
        assertEquals(listOf("Today", "Yesterday"), sections.take(2).map { it.label })
        assertEquals(3, sections.size)
        assertEquals("new", sections[0].entries.single().track.id)
        assertEquals("yes", sections[1].entries.single().track.id)
        assertEquals("old", sections[2].entries.single().track.id)
        assertTrue(sections[2].label != "Today" && sections[2].label != "Yesterday")
    }

    @Test fun mixesAndTracksStayInTheirDayInPlayedOrder() {
        val sections = groupHistoryByDay(listOf(
            entry("t2", now - 1_000), entry("mix", now - 500, kind = "mix"), entry("t1", now - 2_000)), now)
        assertEquals(listOf("mix", "t2", "t1"), sections.single().entries.map { it.track.id })
        assertEquals("mix", sections.single().entries[0].kind)
    }

    @Test fun emptyHistoryYieldsNoSections() = assertTrue(groupHistoryByDay(emptyList(), now).isEmpty())
}
