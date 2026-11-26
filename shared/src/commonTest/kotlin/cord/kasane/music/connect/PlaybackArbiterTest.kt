package cord.kasane.music.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackArbiterTest {

    private fun arbiter(localId: String = "local-b") = PlaybackArbiter(localId)

    @Test
    fun idleEverywhereIsSoloWithNoActiveDevice() {
        val a = arbiter()
        assertNull(a.activeDeviceId())
        assertEquals(PlaybackRole.SOLO, a.localRole())
    }

    @Test
    fun localPlayingAloneMakesThisDevicePerformer() {
        val a = arbiter()
        a.localPlayback = "playing"
        assertEquals("local-b", a.activeDeviceId())
        assertEquals(PlaybackRole.PERFORMER, a.localRole())
    }

    @Test
    fun remotePlayingMakesThisDeviceListener() {
        val a = arbiter()
        a.updateRemote("remote-a", "playing", null)
        assertEquals("remote-a", a.activeDeviceId())
        assertEquals(PlaybackRole.LISTENER, a.localRole())
    }

    @Test
    fun localPlayIntentYieldsRemotePerformerBeforePlaying() {
        val a = arbiter()
        a.updateRemote("remote-a", "playing", null)
        val actions = a.onLocalPlayIntent(nowMs = 1000)
        assertEquals(2, actions.size)
        assertIs<ArbiterAction.YieldRemote>(actions[0])
        assertEquals("remote-a", (actions[0] as ArbiterAction.YieldRemote).deviceId)
        assertEquals("local-b", (actions[0] as ArbiterAction.YieldRemote).newActiveId)
        assertIs<ArbiterAction.PlayLocally>(actions[1])
        // The claim is ours immediately: broadcasts already name this device the performer.
        assertEquals("local-b", a.claimedActiveId)
    }

    @Test
    fun localPlayIntentWithoutRemotePerformerPlaysDirectly() {
        val a = arbiter()
        val actions = a.onLocalPlayIntent()
        assertEquals(listOf(ArbiterAction.PlayLocally), actions)
        assertEquals("local-b", a.claimedActiveId)
    }

    @Test
    fun claimedPerformerWinsOverAnotherPlayingDevice() {
        val a = arbiter()
        a.updateRemote("remote-a", "playing", null)
        a.onLocalPlayIntent(nowMs = 1000)
        a.localPlayback = "playing"
        // Remote still reports playing but our explicit claim wins.
        assertEquals("local-b", a.activeDeviceId(nowMs = 1000))
        assertTrue(!a.shouldLocalYield())
    }

    @Test
    fun remoteTakeoverClaimIsAdoptedWhenItPlays() {
        val a = arbiter()
        a.localPlayback = "playing"
        // Remote announces itself as performer and it actually plays.
        a.updateRemote("remote-a", "playing", "remote-a")
        assertEquals("remote-a", a.activeDeviceId())
        assertTrue(a.shouldLocalYield(), "remote performer must make local playback stop")
        assertEquals(PlaybackRole.LISTENER, a.localRole())
    }

    @Test
    fun conflictingPlayingStatesResolveBySmallestId() {
        val a = PlaybackArbiter("b-device")
        a.localPlayback = "playing"
        // Remote has the smaller id: it wins, local must yield.
        a.updateRemote("a-device", "playing", null)
        assertEquals("a-device", a.activeDeviceId())
        assertTrue(a.shouldLocalYield())
    }

    @Test
    fun conflictingPlayingStatesKeepLargerIdLocal() {
        val a = PlaybackArbiter("a-device")
        a.localPlayback = "playing"
        a.updateRemote("b-device", "playing", null)
        assertEquals("a-device", a.activeDeviceId())
        assertTrue(!a.shouldLocalYield())
    }

    @Test
    fun explicitYieldDemotesLocalToListener() {
        val a = arbiter()
        a.localPlayback = "paused"
        a.acceptRemoteClaim("remote-a", nowMs = 1000)
        // New performer is not yet reporting: the pending claim still names it.
        assertEquals("remote-a", a.activeDeviceId(nowMs = 1000))
        assertEquals(PlaybackRole.LISTENER, a.localRole())
    }

    @Test
    fun pendingClaimExpiresAfterGrace() {
        val a = arbiter()
        a.acceptRemoteClaim("remote-a", nowMs = 1000)
        assertEquals("remote-a", a.activeDeviceId(nowMs = 1000))
        // 31 s later the claimed performer never reported: drop the claim.
        assertNull(a.activeDeviceId(nowMs = 32_000))
    }

    @Test
    fun aPausedPerformerKeepsTheSourceRole() {
        val a = arbiter()
        a.updateRemote("remote-a", "playing", "remote-a")
        // The user pauses on the remote: it stays the accepted performer until an explicit switch.
        a.updateRemote("remote-a", "paused", "remote-a")
        assertEquals("remote-a", a.activeDeviceId())
        assertEquals(PlaybackRole.LISTENER, a.localRole())
        // Playing locally is still an explicit claim — it yields the paused source, not silently wins.
        val actions = a.onLocalPlayIntent(nowMs = 1000)
        assertIs<ArbiterAction.YieldRemote>(actions[0])
        assertEquals("remote-a", (actions[0] as ArbiterAction.YieldRemote).deviceId)
    }

    @Test
    fun claimIsDroppedWhenPerformerStops() {
        val a = arbiter()
        a.updateRemote("remote-a", "playing", "remote-a")
        for (stop in listOf("idle", "ended", "error")) {
            a.updateRemote("remote-a", "playing", "remote-a")
            a.updateRemote("remote-a", stop, null)
            assertNull(a.activeDeviceId(), "claim must be released on $stop")
            assertEquals(PlaybackRole.SOLO, a.localRole())
        }
    }

    @Test
    fun removingRemoteDropsItsClaim() {
        val a = arbiter()
        a.updateRemote("remote-a", "playing", "remote-a")
        a.removeRemote("remote-a")
        assertNull(a.activeDeviceId())
    }

    @Test
    fun bufferingCountsAsPlaying() {
        val a = arbiter()
        a.updateRemote("remote-a", "buffering", null)
        assertEquals("remote-a", a.activeDeviceId())
        a.localPlayback = "buffering"
        assertTrue(PlaybackArbiter.plays("buffering"))
    }

    @Test
    fun takeoverSequenceNeverHasTwoPerformers() {
        // Simulate: remote performs → we intend to play → remote is yielded → remote reports paused+activeId=us.
        val a = arbiter()
        a.updateRemote("remote-a", "playing", null)
        a.onLocalPlayIntent(nowMs = 1000)
        a.localPlayback = "playing"
        // Remote confirms our claim while finishing its pause.
        a.updateRemote("remote-a", "paused", "local-b")
        assertEquals("local-b", a.activeDeviceId(nowMs = 1000))
        assertEquals(PlaybackRole.PERFORMER, a.localRole())
        assertTrue(!a.shouldLocalYield())
    }
}
