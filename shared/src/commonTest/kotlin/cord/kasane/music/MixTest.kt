package cord.kasane.music

import cord.kasane.music.mix.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.*

class MixTest {
    private val sampleRate = 22050

    /** Click track: a decaying 1.2 kHz burst on every beat over low-level noise, starting at [firstBeat]. */
    private fun clicks(bpm: Double, seconds: Int, firstBeat: Double = 0.2, noise: Float = 0.02f, seed: Int = 7): FloatArray {
        val random = Random(seed)
        val out = FloatArray(sampleRate * seconds) { (random.nextFloat() - 0.5f) * noise }
        val period = 60.0 / bpm
        var t = firstBeat
        while (t < seconds - 0.1) {
            val start = (t * sampleRate).toInt()
            for (i in 0 until (0.03 * sampleRate).toInt()) {
                if (start + i >= out.size) break
                out[start + i] += (sin(2 * PI * 1200 * i / sampleRate) * exp(-i / (0.006 * sampleRate))).toFloat() * 0.8f
            }
            t += period
        }
        return out
    }

    @Test fun estimatesTempoWithinOneBpmAcrossTheRange() {
        for (bpm in listOf(76.0, 92.0, 120.0, 128.0, 140.0, 174.0)) {
            val analysis = assertNotNull(BeatAnalyzer.analyze(clicks(bpm, 30), sampleRate), "no analysis at $bpm")
            // Octave errors are the classic failure; the range prior must keep us on the right one for these tempos.
            assertTrue(abs(analysis.bpm - bpm) <= 1.0, "expected $bpm but got ${analysis.bpm}")
            assertTrue(analysis.confidence > 0.35, "confidence ${analysis.confidence} at $bpm")
        }
    }

    @Test fun beatGridLandsOnTheClicks() {
        val analysis = assertNotNull(BeatAnalyzer.analyze(clicks(128.0, 30, firstBeat = 0.2), sampleRate))
        val first = analysis.beatTimesSec.first()
        val period = 60.0 / 128.0
        val offset = ((first - 0.2) % period + period) % period
        val error = minOf(offset, period - offset)
        assertTrue(error < 0.03, "beat grid off by ${error * 1000} ms")
    }

    @Test fun silenceAndNoiseDoNotInventAConfidentTempo() {
        assertNull(BeatAnalyzer.analyze(FloatArray(sampleRate * 20), sampleRate))
        val random = Random(1)
        val noise = FloatArray(sampleRate * 30) { (random.nextFloat() - 0.5f) * 0.5f }
        val analysis = BeatAnalyzer.analyze(noise, sampleRate)
        assertTrue(analysis == null || analysis.confidence < 0.35, "noise confidence ${analysis?.confidence}")
        assertNull(BeatAnalyzer.analyze(FloatArray(100) { 0.5f }, sampleRate))
    }

    @Test fun equalPowerGainsKeepConstantPower() {
        for (i in 0..100) {
            val (out, inn) = Crossfade.gains(i / 100.0)
            assertEquals(1.0, out * out + inn * inn, 1e-9)
        }
        assertEquals(1.0, Crossfade.gains(0.0).first, 1e-12)
        assertEquals(1.0, Crossfade.gains(1.0).second, 1e-12)
    }

    @Test fun mixingTwoFullScaleSignalsNeverClips() {
        val a = FloatArray(4000) { if (it % 2 == 0) 1f else -1f }
        val mixed = Crossfade.mix(a, a, 4000, incomingGain = 2.0)
        assertTrue(mixed.all { abs(it) <= 1f }, "limiter must keep samples inside [-1, 1]")
        val quiet = FloatArray(100) { 0.1f }
        assertEquals(0.1f, Crossfade.softLimit(quiet[0]))
    }

    @Test fun levelMatchIsBoundedAndSymmetric() {
        assertEquals(1.0, Crossfade.levelMatch(0.2, 0.2), 1e-12)
        assertEquals(2.0, Crossfade.levelMatch(0.4, 0.2), 0.05)
        assertTrue(Crossfade.levelMatch(0.5, 0.001) <= 2.0 + 1e-9, "never boosts more than +6 dB")
        assertEquals(1.0, Crossfade.levelMatch(0.0, 0.3))
    }

    private fun track(bpm: Double, seconds: Int, firstBeat: Double) =
        TrackAnalysis(seconds.toDouble(), BeatAnalyzer.analyze(clicks(bpm, seconds, firstBeat), sampleRate))

    @Test fun plannerAlignsBeatsWhenTemposAreCloseAndConfident() {
        val out = track(128.0, 40, 0.2); val inn = track(129.0, 40, 0.35)
        val plan = assertNotNull(TransitionPlanner.plan(out, inn, fadeSec = 8.0))
        assertTrue(plan.beatAligned, plan.reason)
        assertTrue(plan.startOutgoingSec + plan.fadeSec <= out.durationSec, "fade must fit before the end")
        assertTrue(out.beats!!.beatTimesSec.any { abs(it - plan.startOutgoingSec) < 1e-9 }, "fade starts on an outgoing beat")
        assertEquals(inn.beats!!.beatTimesSec.first(), plan.startIncomingSec, 1e-9)
        assertTrue(abs(plan.tempoRatio - 1.0) < 0.02)
    }

    @Test fun plannerFallsBackToPlainCrossfadeWhenTemposClash() {
        val plan = assertNotNull(TransitionPlanner.plan(track(90.0, 40, 0.2), track(140.0, 40, 0.2)))
        assertFalse(plan.beatAligned); assertEquals(0.0, plan.startIncomingSec); assertTrue(plan.reason.contains("differ"))
    }

    @Test fun plannerRefusesToMixWhenTracksAreTooShortOrFadeIsZero() {
        val short = TrackAnalysis(10.0)
        assertNull(TransitionPlanner.plan(short, TrackAnalysis(200.0)))
        assertNull(TransitionPlanner.plan(TrackAnalysis(200.0), TrackAnalysis(200.0), fadeSec = 0.0))
        val unanalysed = assertNotNull(TransitionPlanner.plan(TrackAnalysis(200.0), TrackAnalysis(200.0)))
        assertFalse(unanalysed.beatAligned)
    }
}
