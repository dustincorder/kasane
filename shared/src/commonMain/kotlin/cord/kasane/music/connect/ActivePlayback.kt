package cord.kasane.music.connect

/**
 * Which side of a Kasane Connect session this device is on right now.
 * SOLO: no group performer exists (nothing plays anywhere, or no session).
 * PERFORMER: this device is the accepted playback source.
 * LISTENER: a remote device is the performer; this one may control it.
 */
enum class PlaybackRole { SOLO, PERFORMER, LISTENER }

/** What a paired device last told us about itself. */
data class RemotePlayback(
    val playback: String = "idle",
    /** The performer the remote itself currently accepts, or null. */
    val activeId: String? = null,
)

/** An action the local side must take, in order. */
sealed interface ArbiterAction {
    /** Tell this remote performer to stand down and accept a new activeId. */
    data class YieldRemote(val deviceId: String, val newActiveId: String) : ArbiterAction
    /** Local playback may start now (issued only after any required yield). */
    data object PlayLocally : ArbiterAction
}

/**
 * Single-performer arbitration for Kasane Connect (docs/connect/protocol.md §Active device).
 *
 * One group has at most one performer. The active device is resolved deterministically so every
 * participant reaches the same answer without comparing clocks:
 * 1. An explicit claim (local play intent, `takeover`, or `yield`) wins while the claimed device
 *    is actually playing.
 * 2. Otherwise every device reporting playback is a candidate; the lexicographically smallest
 *    device id keeps playing and the rest must yield.
 */
class PlaybackArbiter(val localId: String) {

    var localPlayback: String = "idle"

    /** Device explicitly accepted as the group performer; lapses when it goes silent. */
    var claimedActiveId: String? = null
        private set

    /** Local timestamp of the current claim; a pending claim expires after [claimGraceMs]. */
    private var claimedAtMs: Long = 0

    /** How long a claimed performer may stay silent before the claim is dropped. */
    var claimGraceMs: Long = 30_000

    private val remotes = LinkedHashMap<String, RemotePlayback>()

    companion object {
        fun plays(state: String): Boolean = state == "playing" || state == "buffering"
    }

    fun updateRemote(id: String, playback: String, activeId: String?, nowMs: Long = 0) {
        if (id == localId) return
        remotes[id] = RemotePlayback(playback, activeId)
        // Adopt a peer's explicit claim only when it points at a device we know is playing.
        if (activeId != null && activeId != claimedActiveId && isPlayingNow(activeId)) {
            claimedActiveId = activeId; claimedAtMs = nowMs
        }
    }

    fun removeRemote(id: String) {
        remotes.remove(id)
        if (claimedActiveId == id) claimedActiveId = null
    }

    /**
     * The accepted group performer, or null when nothing plays.
     * `nowMs` is this device's own clock: it only expires a claim whose performer never reported —
     * timestamps are never compared across devices.
     */
    fun activeDeviceId(nowMs: Long = 0): String? {
        val claim = claimedActiveId
        if (claim != null) {
            if (isPlayingNow(claim)) return claim
            val reported = claim == localId || remotes.containsKey(claim)
            if (reported || nowMs - claimedAtMs > claimGraceMs) {
                claimedActiveId = null
            } else {
                // Takeover in flight: the new performer has not pushed its first state yet.
                return claim
            }
        }
        val playing = buildList {
            if (plays(localPlayback)) add(localId)
            for ((id, r) in remotes) if (plays(r.playback)) add(id)
        }
        return playing.minOrNull()
    }

    fun localRole(): PlaybackRole = when (val active = activeDeviceId()) {
        null -> PlaybackRole.SOLO
        localId -> PlaybackRole.PERFORMER
        else -> PlaybackRole.LISTENER
    }

    /** Local playback must stop: a remote device is the accepted performer. */
    fun shouldLocalYield(): Boolean =
        plays(localPlayback) && activeDeviceId()?.let { it != localId } == true

    /** Local playback (re)starts here; we are the performer from now on. */
    fun claimLocal(nowMs: Long = 0) {
        claimedActiveId = localId; claimedAtMs = nowMs
    }

    /** A remote device is now the accepted performer (its `yield`/`takeover` ack). */
    fun acceptRemoteClaim(id: String, nowMs: Long = 0) {
        if (id != localId) { claimedActiveId = id; claimedAtMs = nowMs }
    }

    /**
     * The user asked to play locally. Never produces double playback: when a remote performer
     * exists it is yielded BEFORE PlayLocally is returned.
     */
    fun onLocalPlayIntent(nowMs: Long = 0): List<ArbiterAction> {
        val active = activeDeviceId(nowMs)
        val actions = mutableListOf<ArbiterAction>()
        if (active != null && active != localId) actions += ArbiterAction.YieldRemote(active, localId)
        actions += ArbiterAction.PlayLocally
        claimLocal(nowMs)
        return actions
    }

    private fun isPlayingNow(id: String): Boolean =
        if (id == localId) plays(localPlayback) else remotes[id]?.playback?.let(::plays) == true
}
