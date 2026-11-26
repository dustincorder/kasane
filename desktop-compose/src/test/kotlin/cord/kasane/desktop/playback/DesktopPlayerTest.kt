package cord.kasane.desktop.playback

import cord.kasane.desktop.audio.AudioEngine
import cord.kasane.desktop.library.LocalLibrary
import cord.kasane.music.model.Track
import cord.kasane.music.provider.RadioPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Queue semantics against a fake engine and a fake stream source — no mpv, network or YouTube.
 * Covers the contract Android's Media3 timeline gives the phone: removing the playing track,
 * EOF advance, repeat/shuffle, radio append.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPlayerTest {
    private fun track(id: String, title: String = "Title $id", artist: String = "Artist") =
        Track(id, title, artist, durationSeconds = 200.0)

    private class FakeEngine : AudioEngine {
        data class Load(val url: String, val userAgent: String?, val startSec: Double, val play: Boolean)
        val loads = mutableListOf<Load>()
        var stopped = false
        private val _status = MutableStateFlow(AudioEngine.Status())
        override val status: StateFlow<AudioEngine.Status> = _status
        private val _events = MutableSharedFlow<AudioEngine.Event>(extraBufferCapacity = 16)
        override val events: SharedFlow<AudioEngine.Event> = _events.asSharedFlow()

        override fun load(url: String, userAgent: String?, startSec: Double, play: Boolean) {
            loads += Load(url, userAgent, startSec, play)
            _status.value = _status.value.copy(idle = false, paused = !play, buffering = false, positionSec = startSec, durationSec = 200.0)
        }
        override fun setPaused(paused: Boolean) { _status.value = _status.value.copy(paused = paused, idle = false) }
        override fun seek(sec: Double) { _status.value = _status.value.copy(positionSec = sec) }
        override fun setVolume(percent: Double) { _status.value = _status.value.copy(volume = percent) }
        override fun stop() { stopped = true; _status.value = _status.value.copy(idle = true, paused = true) }
        override fun close() {}
        suspend fun emitEnded() = _events.emit(AudioEngine.Event.Ended)
        suspend fun emitFailed(reason: String) = _events.emit(AudioEngine.Event.Failed(reason))
    }

    private class FakeStreams : StreamSource {
        var source = "verified"
        var note = ""
        var failure: java.io.IOException? = null
        val resolves = mutableListOf<Pair<String, Boolean>>()
        val cutOff = mutableSetOf<String>()
        override val lastSource get() = source
        override val lastSourceNote get() = note
        override suspend fun resolve(videoId: String, forceRefresh: Boolean): PlaybackStream {
            resolves += videoId to forceRefresh
            failure?.let { throw it }
            return PlaybackStream("fake://$videoId", "ua")
        }
        override fun consumeCutOff(videoId: String) = cutOff.remove(videoId)
    }

    private class Rig(scheduler: kotlinx.coroutines.CoroutineDispatcher) {
        val scope = TestScope(scheduler + SupervisorJob())
        val engine = FakeEngine()
        val streams = FakeStreams()
        var radioPages = ArrayDeque<RadioPage>()
        val radioCalls = mutableListOf<Pair<String, String?>>()
        var radioBlock: CompletableDeferred<RadioPage>? = null
        val radio = RadioSource { id, cont ->
            radioCalls += id to cont
            radioBlock?.await() ?: radioPages.removeFirstOrNull() ?: RadioPage(emptyList(), null)
        }
        val library = LocalLibrary(createTempDirectory("kasane-test").resolve("library.json").toFile())
        val player = DesktopPlayer(scope, engine, streams, radio, library)
        fun done() = scope.cancel()
    }

    private fun rig() = Rig(UnconfinedTestDispatcher())

    private fun tracks(prefix: String, n: Int) = (1..n).map { track("$prefix$it") }

    @Test fun singleTrackStartsRadioContext() = runTest {
        val r = rig(); r.radioPages += RadioPage(tracks("r", 5), "cont1")
        val t = track("seed")
        r.player.playTrack(t)
        assertEquals(listOf("seed") + (1..5).map { "r$it" }, r.player.state.value.queue.map { it.id })
        assertEquals(0, r.player.state.value.index)
        assertEquals("fake://seed", r.engine.loads.single().url)
        assertTrue(r.player.state.value.isPlaying)
        assertEquals("seed" to null, r.radioCalls.single())
        r.done()
    }

    @Test fun aStaleRadioPageCannotAppendToTheNextContext() = runTest {
        val r = rig()
        val slow = CompletableDeferred<RadioPage>()
        r.radioBlock = slow
        r.player.playTrack(track("a"))              // radio fetch for "a" suspends on `slow`
        r.radioBlock = null
        r.radioPages += RadioPage(tracks("b", 3), null)
        r.player.playTrack(track("x"))              // cancels the pending fetch, starts a new context
        slow.complete(RadioPage(tracks("stale", 3), null)) // the late page arrives but is dropped
        assertEquals(listOf("x", "b1", "b2", "b3"), r.player.state.value.queue.map { it.id })
        r.done()
    }

    @Test fun collectionKeepsItsOrderAndStartIndex() = runTest {
        val r = rig()
        r.player.playCollection(tracks("t", 5), startIndex = 2)
        assertEquals((1..5).map { "t$it" }, r.player.state.value.queue.map { it.id })
        assertEquals(2, r.player.state.value.index)
        assertEquals("fake://t3", r.engine.loads.single().url)
        r.done()
    }

    @Test fun removingThePlayingTrackPlaysTheNextOneLikeMedia3() = runTest {
        val r = rig()
        r.player.playCollection(tracks("t", 3), startIndex = 0)
        r.player.removeAt(0)
        assertEquals(listOf("t2", "t3"), r.player.state.value.queue.map { it.id })
        assertEquals(0, r.player.state.value.index)
        assertEquals("fake://t2", r.engine.loads.last().url)
        assertTrue(r.engine.loads.last().play)
        r.done()
    }

    @Test fun removingThePlayingLastTrackFallsBackToThePrevious() = runTest {
        val r = rig()
        r.player.playCollection(tracks("t", 2), startIndex = 1)
        r.player.removeAt(1)
        assertEquals(listOf("t1"), r.player.state.value.queue.map { it.id })
        assertEquals(0, r.player.state.value.index)
        assertEquals("fake://t1", r.engine.loads.last().url)
        r.done()
    }

    @Test fun removingTheOnlyTrackStopsTheEngine() = runTest {
        val r = rig()
        r.player.playCollection(tracks("t", 1))
        r.player.removeAt(0)
        assertTrue(r.player.state.value.queue.isEmpty())
        assertEquals(-1, r.player.state.value.index)
        assertTrue(r.engine.stopped)
        assertTrue(!r.player.state.value.isPlaying)
        r.done()
    }

    @Test fun removingAroundTheCurrentTrackKeepsIt() = runTest {
        val r = rig()
        r.player.playCollection(tracks("t", 4), startIndex = 2)
        r.player.removeAt(0)
        assertEquals(1, r.player.state.value.index)
        assertEquals("t3", r.player.state.value.current?.id)
        r.player.removeAt(2)
        assertEquals(1, r.player.state.value.index)
        assertEquals("t3", r.player.state.value.current?.id)
        assertEquals(1, r.engine.loads.size) // nothing reloaded for edits away from the current item
        r.done()
    }

    @Test fun endOfFileAdvancesToTheNextTrack() = runTest {
        val r = rig()
        r.player.playCollection(tracks("t", 3))
        r.engine.emitEnded()
        assertEquals(1, r.player.state.value.index)
        assertEquals("fake://t2", r.engine.loads.last().url)
        r.engine.emitEnded()
        assertEquals(2, r.player.state.value.index)
        r.engine.emitEnded()
        assertTrue(!r.player.state.value.isPlaying)
        r.done()
    }

    @Test fun repeatAllWrapsAround() = runTest {
        val r = rig()
        r.player.playCollection(tracks("t", 2), startIndex = 1)
        r.player.cycleRepeat() // ALL
        r.engine.emitEnded()
        assertEquals(0, r.player.state.value.index)
        assertEquals("fake://t1", r.engine.loads.last().url)
        r.done()
    }

    @Test fun repeatOneReloadsTheSameTrack() = runTest {
        val r = rig()
        r.player.playCollection(tracks("t", 2))
        r.player.cycleRepeat(); r.player.cycleRepeat() // ONE
        r.engine.emitEnded()
        assertEquals(0, r.player.state.value.index)
        assertEquals(2, r.engine.loads.count { it.url == "fake://t1" })
        r.done()
    }

    @Test fun aCutOffGuestStreamEndsWithAnHonestError() = runTest {
        val r = rig(); r.streams.source = "guest"; r.streams.note = "bot check"
        r.player.playCollection(tracks("t", 2))
        r.streams.cutOff += "t1"
        r.engine.emitEnded()
        assertNotEquals(null, r.player.state.value.error)
        assertTrue(r.player.state.value.error!!.contains("first part"))
        assertEquals(0, r.player.state.value.index) // did not silently skip to the next track
        r.done()
    }

    @Test fun anEngineFailureRefreshesOnceThenReports() = runTest {
        val r = rig()
        r.player.playCollection(tracks("t", 1))
        r.engine.emitFailed("gone")
        assertEquals(listOf("t1" to false, "t1" to true), r.streams.resolves)
        assertNull(r.player.state.value.error)
        r.engine.emitFailed("gone again")
        assertNotEquals(null, r.player.state.value.error)
        r.done()
    }

    @Test fun aNonVerifiedSourceTellsTheUser() = runTest {
        val r = rig(); r.streams.source = "ios-hls"; r.streams.note = "webkit missing"
        r.player.playCollection(tracks("t", 1))
        assertTrue(r.player.state.value.notice!!.contains("ios-hls"))
        assertTrue(r.player.state.value.notice!!.contains("webkit missing"))
        r.done()
    }

    @Test fun nextAndPreviousStayInBounds() = runTest {
        val r = rig()
        r.player.playCollection(tracks("t", 2))
        r.player.previous() // position 0 at index 0: restart, not move
        assertEquals(0, r.player.state.value.index)
        r.player.next()
        assertEquals(1, r.player.state.value.index)
        r.player.next() // at the end, repeat off: nothing happens
        assertEquals(1, r.player.state.value.index)
        r.player.seek(30.0)
        r.player.previous() // past 3 s: restart the track
        assertEquals(1, r.player.state.value.index)
        assertEquals(0.0, r.player.state.value.positionSec)
        r.player.previous() // now at 0: previous track
        assertEquals(0, r.player.state.value.index)
        r.done()
    }

    @Test fun seekPausesAndResumesThroughTheEngine() = runTest {
        val r = rig()
        r.player.playCollection(tracks("t", 1))
        r.player.seek(42.0)
        assertEquals(42.0, r.engine.status.value.positionSec)
        r.player.toggle()
        assertTrue(r.engine.status.value.paused)
        r.player.toggle()
        assertTrue(!r.engine.status.value.paused)
        r.done()
    }

    @Test fun shuffleKeepsTheAnchorAndRoundTrips() = runTest {
        val r = rig()
        val items = tracks("t", 10)
        r.player.playCollection(items, startIndex = 0)
        r.player.toggleShuffle()
        val s = r.player.state.value
        assertTrue(s.shuffle)
        assertEquals("t1", s.current?.id)
        assertEquals(items.map { it.id }.toSet(), s.queue.map { it.id }.toSet())
        r.player.toggleShuffle()
        assertEquals(items.map { it.id }, r.player.state.value.queue.map { it.id })
        assertTrue(!r.player.state.value.shuffle)
        r.done()
    }

    @Test fun moveAndPlayNextEditTheQueueLikeMedia3() = runTest {
        val r = rig()
        r.player.playCollection(tracks("t", 4))
        r.player.move(3, 1)
        assertEquals(listOf("t1", "t4", "t2", "t3"), r.player.state.value.queue.map { it.id })
        assertEquals(0, r.player.state.value.index)
        r.player.playNext(track("x"))
        assertEquals(listOf("t1", "x", "t4", "t2", "t3"), r.player.state.value.queue.map { it.id })
        r.player.playNext(track("t3")) // existing entry moves to index+1 instead of duplicating
        assertEquals(listOf("t1", "t3", "x", "t4", "t2"), r.player.state.value.queue.map { it.id })
        r.player.addToQueue(track("y"))
        r.player.addToQueue(track("y"))
        assertEquals(listOf("t1", "t3", "x", "t4", "t2", "y"), r.player.state.value.queue.map { it.id })
        r.done()
    }

    @Test fun radioExtendsNearTheEnd() = runTest {
        val r = rig()
        r.radioPages += RadioPage(tracks("r", 3), "cont")
        r.radioPages += RadioPage(tracks("n", 3), null)
        r.player.playTrack(track("seed"))
        // Move close to the end: loading the third-from-last track triggers the next page.
        r.player.playIndex(3)
        assertEquals(listOf("seed", "r1", "r2", "r3", "n1", "n2", "n3"), r.player.state.value.queue.map { it.id })
        assertEquals(listOf("seed" to null, "seed" to "cont"), r.radioCalls)
        r.done()
    }

    // "Start radio" on the track that is already playing anchors there: no reload, position kept.
    @Test fun radioOnThePlayingTrackKeepsItPlaying() = runTest {
        val r = rig()
        r.radioPages += RadioPage(tracks("r", 3), null)
        r.radioPages += RadioPage(tracks("n", 3), null)
        r.player.playTrack(track("seed"))
        val loadsBefore = r.engine.loads.size
        r.player.startRadio(track("seed"))
        assertEquals(loadsBefore, r.engine.loads.size)
        assertEquals(listOf("seed") + (1..3).map { "n$it" }, r.player.state.value.queue.map { it.id })
        r.done()
    }

    @Test fun radioOnAnotherTrackStartsFromTheBeginning() = runTest {
        val r = rig()
        r.radioPages += RadioPage(tracks("r", 3), null)
        r.player.playTrack(track("a"))
        r.player.startRadio(track("b"))
        assertEquals("fake://b", r.engine.loads.last().url)
        assertEquals(0.0, r.engine.loads.last().startSec)
        r.done()
    }

    @Test fun handoffInstallsTheRemoteQueue() = runTest {
        val r = rig()
        r.player.startHandoff(tracks("h", 3), index = 1, positionSec = 12.0, play = true, shuffle = false, repeat = RepeatMode.ALL)
        val s = r.player.state.value
        assertEquals((1..3).map { "h$it" }, s.queue.map { it.id })
        assertEquals(1, s.index)
        assertEquals(RepeatMode.ALL, s.repeat)
        assertEquals(12.0, r.engine.loads.single().startSec)
        r.done()
    }

    @Test fun historyRecordsThePlayedTrack() = runTest {
        val r = rig()
        r.player.playCollection(tracks("t", 2))
        // Merely loading a track is not a listen — the count waits for the shared threshold.
        assertTrue(r.library.history.value.none { it.kind == "track" })
        r.engine.seek(35.0) // crosses the 30 s threshold on the fake engine
        assertEquals(listOf("t1"), r.library.history.value.filter { it.kind == "track" }.map { it.track.id })
        r.done()
    }

    @Test fun selectingAnotherTrackUnderRepeatOneLoadsItRightAway() = runTest {
        val r = rig()
        r.player.playCollection(tracks("t", 2))
        r.player.cycleRepeat(); r.player.cycleRepeat() // ONE
        r.player.playIndex(1)
        assertEquals("fake://t2", r.engine.loads.last().url)
        r.done()
    }

    /**
     * The reported defect: with repeat-one on, picking another track left mpv streaming the old
     * one to its end when the new track's stream failed to resolve — the state had already moved,
     * so the stale `end-file` then reloaded the *new* track ("starts after the old one ends").
     * A failed switch must stop the engine instead of playing the previous track behind the UI.
     */
    @Test fun aFailedSwitchStopsTheOldTrackInsteadOfPlayingItToTheEnd() = runTest {
        val r = rig()
        r.player.playCollection(tracks("t", 2))
        r.player.cycleRepeat(); r.player.cycleRepeat() // ONE
        r.streams.failure = java.io.IOException("offline")
        r.player.playIndex(1)
        val s = r.player.state.value
        assertEquals("t2", s.current?.id)
        assertNotEquals(null, s.error)
        assertTrue(r.engine.stopped)
        // Nothing plays, so no stale end-of-file arrives; the user retries explicitly.
        r.streams.failure = null
        r.player.toggle()
        assertEquals("fake://t2", r.engine.loads.last().url)
        r.done()
    }

    @Test fun toggleAfterAnErrorRetriesTheLoad() = runTest {
        val r = rig()
        r.streams.failure = java.io.IOException("offline")
        r.player.playCollection(tracks("t", 1))
        assertNotEquals(null, r.player.state.value.error)
        r.streams.failure = null
        r.player.toggle()
        assertEquals(2, r.streams.resolves.size)
        assertNull(r.player.state.value.error)
        r.done()
    }
}
