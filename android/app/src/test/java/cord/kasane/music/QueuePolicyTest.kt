package cord.kasane.music
import cord.kasane.music.model.Track
import cord.kasane.music.playback.QueuePolicy
import org.junit.Assert.*
import org.junit.Test
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
}
