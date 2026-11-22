package cord.kasane.music.mix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/** Equal-power crossfade math and the loudness/clipping guards a mixer needs. Pure functions, no I/O. */
object Crossfade {
    /** Gains at position `t` in 0..1. `out² + in² == 1` for every `t`, so perceived loudness stays level. */
    fun gains(t: Double): Pair<Double, Double> {
        val x = t.coerceIn(0.0, 1.0) * PI / 2
        return cos(x) to sin(x)
    }

    /** Incoming-track gain that brings its level to the outgoing track's, bounded so quiet tracks are not blown up. */
    fun levelMatch(outgoingRms: Double, incomingRms: Double, maxBoostDb: Double = 6.0): Double {
        if (outgoingRms <= 0 || incomingRms <= 0) return 1.0
        val limit = 10.0.pow(maxBoostDb / 20.0)
        return (outgoingRms / incomingRms).coerceIn(1.0 / limit, limit)
    }

    private fun Double.pow(e: Double) = Math.pow(this, e)

    /** Transparent below the threshold; a smooth tanh knee above it so the sum of two loud tracks never hard-clips. */
    fun softLimit(x: Float, threshold: Float = 0.9f): Float {
        val a = abs(x)
        if (a <= threshold) return x
        val room = 1f - threshold
        return sign(x) * (threshold + room * tanh((a - threshold) / room))
    }

    /**
     * Mixes `frames` mono samples of the fade region. [outgoing] and [incoming] start at the transition point.
     * [incomingGain] is the level-match gain from [levelMatch].
     */
    fun mix(outgoing: FloatArray, incoming: FloatArray, frames: Int, incomingGain: Double = 1.0): FloatArray {
        require(outgoing.size >= frames && incoming.size >= frames) { "Both buffers must cover the fade." }
        return FloatArray(frames) { i ->
            val (go, gi) = gains(if (frames <= 1) 1.0 else i.toDouble() / (frames - 1))
            softLimit((outgoing[i] * go + incoming[i] * gi * incomingGain).toFloat())
        }
    }

    fun rms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size)
    }
}
