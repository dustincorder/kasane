package cord.kasane.music

import cord.kasane.music.model.Track
import cord.kasane.music.playback.QueuePolicy
import kotlin.test.*

class QueueBehaviorTest {
    private fun t(id: String) = Track(id, "Title $id", "Artist")
    private fun ids(tracks: List<Track>) = tracks.map { it.id }

    /** A queue as the app builds it: apply the same policy steps PlaybackManager and PlaybackService use. */
    private class Queue(var items: List<Track> = emptyList(), var contextId: String? = null, var current: Int = 0) {
        fun start(plan: QueuePolicy.ContextStart, context: String) { items = plan.items; contextId = context; current = plan.startIndex }
        fun appendRadio(page: List<Track>, launched: String?): Boolean {
            if (!QueuePolicy.acceptRadioPage(launched, setOfNotNull(contextId))) return false
            items = items + QueuePolicy.radioAppend(items.map { it.id }.toSet(), page, QueuePolicy.LIMIT - items.size)
            return true
        }
    }

    @Test fun playingAPlaylistKeepsItsOrderAndDropsDuplicates() {
        val plan = assertNotNull(QueuePolicy.collectionStart(listOf(t("c"), t("a"), t("b"), t("a")), shuffle = false))
        assertEquals(listOf("c", "a", "b"), ids(plan.items))
        assertEquals(0, plan.startIndex); assertFalse(plan.shuffle)
        assertNull(QueuePolicy.collectionStart(emptyList(), false))
        assertNull(QueuePolicy.collectionStart(listOf(t("")), false))
    }

    @Test fun shufflingAPlaylistStartsAtARandomEntryButNeverReordersTheList() {
        val tracks = (1..10).map { t("t$it") }
        val plan = assertNotNull(QueuePolicy.collectionStart(tracks, shuffle = true) { 7 })
        assertEquals(7, plan.startIndex); assertTrue(plan.shuffle)
        assertEquals(ids(tracks), ids(plan.items))
        assertEquals(9, assertNotNull(QueuePolicy.collectionStart(tracks, true) { 99 }).startIndex, "out-of-range picks are clamped")
    }

    @Test fun anUnrelatedTrackStartsAFreshRadioAnchoredOnIt() {
        val queue = Queue()
        queue.start(assertNotNull(QueuePolicy.collectionStart(listOf(t("p1"), t("p2"), t("p3")), shuffle = true) { 1 }), "playlist")
        queue.start(QueuePolicy.newContext(t("picked")), "radio-1")
        assertEquals(listOf("picked"), ids(queue.items), "nothing from the previous playlist survives")
        assertEquals(0, queue.current)
        assertTrue(queue.appendRadio(listOf(t("picked"), t("r1"), t("p2"), t("r2")), "radio-1"))
        assertEquals("picked", queue.items.first().id, "the selected track stays the anchor")
        assertEquals(listOf("picked", "r1", "p2", "r2"), ids(queue.items))
        assertEquals(queue.items.size, queue.items.map { it.id }.toSet().size, "regeneration must not duplicate entries")
        assertFalse(QueuePolicy.newContext(t("x")).shuffle, "a radio context is never shuffled")
    }

    @Test fun aSlowRadioResponseForThePreviousTrackIsDiscarded() {
        val queue = Queue()
        queue.start(QueuePolicy.newContext(t("old")), "radio-old")
        queue.start(QueuePolicy.newContext(t("new")), "radio-new")
        assertFalse(queue.appendRadio(listOf(t("stale1"), t("stale2")), "radio-old"))
        assertEquals(listOf("new"), ids(queue.items))
        assertTrue(queue.appendRadio(listOf(t("fresh")), "radio-new"))
        assertEquals(listOf("new", "fresh"), ids(queue.items))
        assertFalse(QueuePolicy.acceptRadioPage(null, setOf("radio-new")))
    }

    @Test fun radioAppendRespectsTheQueueLimitAndExistingEntries() {
        val existing = setOf("a", "b")
        assertEquals(listOf("c", "d"), ids(QueuePolicy.radioAppend(existing, listOf(t("a"), t("c"), t("c"), t("d"), t("e")), 2)))
        assertTrue(QueuePolicy.radioAppend(existing, listOf(t("c")), 0).isEmpty())
        assertTrue(QueuePolicy.radioAppend(existing, listOf(t("c")), -5).isEmpty())
    }

    @Test fun selectingThePlayingTrackNeverRebuildsTheQueue() {
        assertFalse(QueuePolicy.startsNewContext("a", "a", forcedRadio = false))
        assertTrue(QueuePolicy.startsNewContext("a", "b", forcedRadio = false))
        assertTrue(QueuePolicy.startsNewContext("a", "a", forcedRadio = true), "an explicit Start radio rebuilds around the track")
        assertTrue(QueuePolicy.startsNewContext(null, "a", forcedRadio = false))
    }

    @Test fun radioPrefetchHonoursRepeatAutoplayAndCollections() {
        assertTrue(QueuePolicy.shouldPrefetchRadio(nearEnd = true, initial = false, collection = false, repeat = false, autoplay = true, forced = false))
        assertFalse(QueuePolicy.shouldPrefetchRadio(true, false, false, repeat = true, autoplay = true, forced = true), "repeat stops radio growth")
        assertFalse(QueuePolicy.shouldPrefetchRadio(true, false, false, repeat = false, autoplay = false, forced = false))
        assertTrue(QueuePolicy.shouldPrefetchRadio(false, true, false, false, true, false), "a fresh single track asks for its radio immediately")
        assertFalse(QueuePolicy.shouldPrefetchRadio(false, true, true, false, true, false), "a collection does not")
    }

    @Test fun upNextFollowsPlayOrderAndSeparatesPlayedEntries() {
        val plain = QueuePolicy.sections(5, 2, listOf(3, 4))
        assertEquals(QueuePolicy.QueueSections(2, listOf(3, 4), listOf(0, 1)), plain)
        val shuffled = QueuePolicy.sections(5, 2, listOf(4, 0, 3))
        assertEquals(listOf(4, 0, 3), shuffled.upNext); assertEquals(listOf(1), shuffled.played)
        val dirty = QueuePolicy.sections(3, 1, listOf(1, 2, 2, 9, -1))
        assertEquals(listOf(2), dirty.upNext); assertEquals(listOf(0), dirty.played)
        assertEquals(QueuePolicy.QueueSections(-1, emptyList(), emptyList()), QueuePolicy.sections(0, 0, emptyList()))
        assertEquals(-1, QueuePolicy.sections(3, 7, emptyList()).currentIndex)
    }

    @Test fun repeatAllAndOneDoNotChangeWhatIsListed() {
        // Media3 supplies the real upcoming order; with repeat-all the wrap-around entries are the played ones.
        val s = QueuePolicy.sections(4, 3, emptyList())
        assertEquals(listOf(0, 1, 2), s.played); assertTrue(s.upNext.isEmpty())
    }

    @Test fun reorderingIsValidatedAndIndicesFollowTheMovedItems() {
        assertNull(QueuePolicy.validMove(5, 2, 2)); assertNull(QueuePolicy.validMove(5, -1, 2)); assertNull(QueuePolicy.validMove(5, 1, 5))
        assertEquals(1 to 3, QueuePolicy.validMove(5, 1, 3))
        // Queue [a b c d e], move b (1) to 3 -> [a c d b e]
        assertEquals(3, QueuePolicy.indexAfterMove(1, 1, 3)); assertEquals(1, QueuePolicy.indexAfterMove(2, 1, 3))
        assertEquals(2, QueuePolicy.indexAfterMove(3, 1, 3)); assertEquals(0, QueuePolicy.indexAfterMove(0, 1, 3)); assertEquals(4, QueuePolicy.indexAfterMove(4, 1, 3))
        // Move d (3) to 1 -> [a d b c e]
        assertEquals(1, QueuePolicy.indexAfterMove(3, 3, 1)); assertEquals(2, QueuePolicy.indexAfterMove(1, 3, 1))
        assertEquals(3, QueuePolicy.indexAfterMove(2, 3, 1)); assertEquals(4, QueuePolicy.indexAfterMove(4, 3, 1))
    }

    @Test fun removingEntriesKeepsTheCurrentTrackIdentified() {
        assertEquals(1, QueuePolicy.indexAfterRemove(2, 0)); assertEquals(2, QueuePolicy.indexAfterRemove(2, 4))
        assertEquals(-1, QueuePolicy.indexAfterRemove(2, 2)); assertEquals(0, QueuePolicy.indexAfterRemove(0, 3))
    }
}
