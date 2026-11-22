package cord.kasane.music.mix

import kotlin.math.abs

/** What the planner needs to know about a track. Positions are seconds from the start of the file. */
data class TrackAnalysis(val durationSec: Double, val beats: BeatAnalysis? = null)

data class MixPlan(
    /** Where on the outgoing track the fade begins. */
    val startOutgoingSec: Double,
    /** Where playback of the incoming track begins; its first audible beat lands on a beat of the outgoing track. */
    val startIncomingSec: Double,
    val fadeSec: Double,
    val beatAligned: Boolean,
    /** Incoming playback-rate ratio needed to lock tempos (1.0 when not aligned). The engine applies it during the fade only. */
    val tempoRatio: Double,
    val reason: String,
)

/**
 * Chooses where a transition happens. Returns null when a mix cannot be justified, in which case the caller
 * must use the normal gapless/track-change path: AutoMix never fakes a blend.
 */
object TransitionPlanner {
    fun plan(
        outgoing: TrackAnalysis,
        incoming: TrackAnalysis,
        fadeSec: Double = 8.0,
        maxTempoShift: Double = 0.04,
        minConfidence: Double = 0.35,
        endMarginSec: Double = 0.25,
    ): MixPlan? {
        if (fadeSec <= 0.0) return null
        // Both tracks must be long enough to hold the fade plus some unmixed material.
        if (outgoing.durationSec < fadeSec * 2 || incoming.durationSec < fadeSec * 2) return null
        val latestStart = outgoing.durationSec - fadeSec - endMarginSec
        val a = outgoing.beats; val b = incoming.beats
        val reliable = a != null && b != null && a.confidence >= minConfidence && b.confidence >= minConfidence &&
            a.beatTimesSec.isNotEmpty() && b.beatTimesSec.isNotEmpty()
        if (reliable) {
            a!!; b!!
            val ratio = a.bpm / b.bpm
            if (abs(ratio - 1.0) <= maxTempoShift) {
                val start = a.beatTimesSec.lastOrNull { it <= latestStart } ?: return plain(latestStart, fadeSec, "No outgoing beat before the fade window.")
                return MixPlan(start, b.beatTimesSec.first(), fadeSec, true, ratio,
                    "Beat-aligned: ${"%.1f".format(a.bpm)} -> ${"%.1f".format(b.bpm)} BPM, tempo shift ${"%.1f".format((ratio - 1) * 100)}%.")
            }
            return plain(latestStart, fadeSec, "Tempos differ by more than ${(maxTempoShift * 100).toInt()}% (${"%.0f".format(a.bpm)} vs ${"%.0f".format(b.bpm)} BPM); plain equal-power crossfade.")
        }
        return plain(latestStart, fadeSec, "Beat analysis was not confident enough; plain equal-power crossfade.")
    }

    private fun plain(start: Double, fadeSec: Double, reason: String) = MixPlan(start, 0.0, fadeSec, false, 1.0, reason)
}
