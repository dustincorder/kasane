package cord.kasane.music

import cord.kasane.music.playback.QueuePolicy
import cord.kasane.music.model.Track
import org.junit.Assert.*
import org.junit.Test

class RadioMergeTest {
    @Test fun lateResponseKeepsManualAdditionsAndNeverDuplicatesSeed() {
        val existing = linkedSetOf("seed", "manual-added-while-request-was-running")
        val candidates = listOf("seed", "a", "a", "manual-added-while-request-was-running", "b").map { Track(it, it, "Artist") }
        val additions = QueuePolicy.radioAppend(existing, candidates, 20)
        assertEquals(listOf("a", "b"), additions.map { it.id })
        assertEquals(listOf("seed", "manual-added-while-request-was-running"), existing.toList())
    }
    @Test fun queueCapacityIsRespected() {
        val tracks = listOf(Track("a", "A", "Artist"), Track("b", "B", "Artist"))
        assertEquals(listOf("a"), QueuePolicy.radioAppend(emptySet(), tracks, 1).map { it.id })
        assertTrue(QueuePolicy.radioAppend(emptySet(), tracks, 0).isEmpty())
    }
    @Test fun singlePrefetchesImmediatelyButCollectionWaitsUntilNearEnd() {
        assertTrue(QueuePolicy.shouldPrefetchRadio(false, true, false, false, true, false))
        assertFalse(QueuePolicy.shouldPrefetchRadio(false, true, true, false, true, false))
        assertTrue(QueuePolicy.shouldPrefetchRadio(true, true, true, false, true, false))
    }
    @Test fun autoplayAndRepeatAreRespectedWhileExplicitRadioCanOverrideAutoplay() {
        assertFalse(QueuePolicy.shouldPrefetchRadio(true, true, false, false, false, false))
        assertTrue(QueuePolicy.shouldPrefetchRadio(true, true, false, false, false, true))
        assertFalse(QueuePolicy.shouldPrefetchRadio(true, true, false, true, true, true))
    }

}
