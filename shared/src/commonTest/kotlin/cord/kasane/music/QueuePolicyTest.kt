package cord.kasane.music
import cord.kasane.music.model.Track
import cord.kasane.music.playback.QueuePolicy
import kotlin.test.*
class QueuePolicyTest {
    @Test fun keepsOrderAndRemovesDuplicateMediaIds() {
        val a = Track("a", "A", "Artist")
        val b = Track("b", "B", "Artist")
        assertEquals(listOf(a,b), QueuePolicy.prepare(listOf(a,a,b)))
    }
    @Test fun movesEarlierItemImmediatelyAfterCurrent() { assertEquals(1, QueuePolicy.nextInsertionIndex(1, 0, 4)) }
    @Test fun movesLaterItemAfterCurrent() { assertEquals(2, QueuePolicy.nextInsertionIndex(1, 3, 4)) }
    @Test fun insertsNewItemAfterCurrent() { assertEquals(2, QueuePolicy.nextInsertionIndex(1, null, 4)) }
    @Test fun rejectsBlankIds() { assertTrue(QueuePolicy.prepare(listOf(Track("", "", ""))).isEmpty()) }
    @Test fun capsUnboundedLists() {
        assertEquals(QueuePolicy.LIMIT, QueuePolicy.prepare((0..3000).map { Track("$it", "$it", "") }).size)
    }

    @Test fun radioOnCurrentTrackAnchorsAtIt() {
        val tracks = (0..4).map { Track("t$it", "T$it", "") }
        val start = QueuePolicy.anchoredRadio(tracks, index = 2, seedId = "t2")!!
        // The playing item stays put with its position — only the upcoming tail is replaced.
        assertEquals(tracks.take(3), start.items)
        assertEquals(2, start.startIndex)
        assertFalse(start.shuffle)
    }

    @Test fun radioOnAnotherTrackStartsFresh() {
        val tracks = (0..4).map { Track("t$it", "T$it", "") }
        assertNull(QueuePolicy.anchoredRadio(tracks, index = 1, seedId = "t3"))
        assertNull(QueuePolicy.anchoredRadio(tracks, index = 9, seedId = "t1"))
        assertNull(QueuePolicy.anchoredRadio(emptyList(), index = 0, seedId = "t0"))
    }
}
