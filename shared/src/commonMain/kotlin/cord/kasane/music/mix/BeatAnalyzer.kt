package cord.kasane.music.mix

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class BeatAnalysis(
    val bpm: Double,
    /** 0..1: how much stronger the chosen tempo is than the rest of the tempo range. Below ~0.35 treat as unreliable. */
    val confidence: Double,
    val beatTimesSec: List<Double>,
    val rmsLinear: Double,
)

/**
 * Local, model-free tempo and beat-grid estimation. Pipeline: high-pass transient emphasis -> log-energy
 * onset envelope -> autocorrelation over 70-180 BPM with a mild prior around 120 BPM (octave disambiguation)
 * -> parabolic refinement -> comb-filter phase search for the beat grid. Mono float PCM in, no allocation
 * proportional to anything but the input length.
 */
object BeatAnalyzer {
    private const val HOP = 256
    private const val FRAME = 512
    const val MIN_BPM = 70.0
    const val MAX_BPM = 180.0

    fun analyze(samples: FloatArray, sampleRate: Int): BeatAnalysis? {
        if (sampleRate <= 0 || samples.size < sampleRate * 8) return null
        val frames = (samples.size - FRAME) / HOP
        if (frames < 64) return null
        val envelope = DoubleArray(frames)
        var total = 0.0
        for (f in 0 until frames) {
            var e = 0.0
            val start = f * HOP
            for (i in start + 1 until start + FRAME) { val d = (samples[i] - samples[i - 1]).toDouble(); e += d * d }
            envelope[f] = ln(1.0 + e * 1000.0 / FRAME)
        }
        for (s in samples) total += s.toDouble() * s
        val rms = sqrt(total / samples.size)
        if (rms < 1e-4) return null

        val onset = DoubleArray(frames)
        val window = (0.4 * sampleRate / HOP).toInt().coerceAtLeast(4)
        var running = 0.0
        for (f in 0 until frames) {
            running += envelope[f]; if (f >= window) running -= envelope[f - window]
            val mean = running / min(f + 1, window)
            onset[f] = max(0.0, envelope[f] - mean)
        }
        val framesPerSecond = sampleRate.toDouble() / HOP
        val minLag = (framesPerSecond * 60.0 / MAX_BPM).toInt()
        val maxLag = (framesPerSecond * 60.0 / MIN_BPM).toInt() + 1
        if (maxLag >= frames / 2) return null
        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) {
            var sum = 0.0
            for (i in lag until frames) sum += onset[i] * onset[i - lag]
            ac[lag] = sum / (frames - lag)
        }
        var zero = 0.0
        for (v in onset) zero += v * v
        zero /= frames
        if (zero <= 1e-9) return null

        fun prior(lag: Int): Double {
            val bpm = framesPerSecond * 60.0 / lag
            val octaves = ln(bpm / 120.0) / ln(2.0)
            return exp(-0.5 * (octaves / 0.9).pow(2))
        }
        var best = minLag; var bestScore = -1.0
        for (lag in minLag..maxLag) { val s = ac[lag] * prior(lag); if (s > bestScore) { bestScore = s; best = lag } }
        val y0 = ac[best - 1]; val y1 = ac[best]; val y2 = ac[best + 1]
        val denom = y0 - 2 * y1 + y2
        val shift = if (abs(denom) > 1e-12) (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5) else 0.0
        val period = best + shift
        val bpm = framesPerSecond * 60.0 / period
        var mean = 0.0; var count = 0
        for (lag in minLag..maxLag) { mean += ac[lag]; count++ }
        mean /= count
        val confidence = ((ac[best] - mean) / zero).coerceIn(0.0, 1.0)

        var bestPhase = 0.0; var bestPhaseScore = -1.0
        val steps = period.toInt().coerceAtLeast(1)
        for (p in 0 until steps) {
            var sum = 0.0; var k = 0
            var pos = p.toDouble()
            while (pos < frames - 1) { sum += onset[pos.toInt()]; pos += period; k++ }
            val score = if (k > 0) sum / k else 0.0
            if (score > bestPhaseScore) { bestPhaseScore = score; bestPhase = p.toDouble() }
        }
        val beats = ArrayList<Double>()
        var pos = bestPhase
        // The envelope peak sits at the end of an onset's frame; report the transient's start.
        while (pos < frames) { beats += (pos * HOP + 0.0) / sampleRate; pos += period }
        return BeatAnalysis(bpm, confidence, beats, rms)
    }
}
