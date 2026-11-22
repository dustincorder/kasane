package cord.kasane.music.library

import kotlin.math.abs
import kotlin.math.min

enum class MatchKind { ID, METADATA }

/** A library or catalog track the importer may resolve to. [videoId] is the stable identity. */
data class MatchCandidate(val videoId: String, val title: String, val artist: String, val durationSeconds: Double = 0.0)

data class MatchResult(val candidate: MatchCandidate, val confidence: Double, val kind: MatchKind) {
    val needsReview: Boolean get() = kind == MatchKind.METADATA && confidence < TrackMatcher.AUTO_ACCEPT
}

object TrackMatcher {
    const val AUTO_ACCEPT = 0.85
    const val MINIMUM = 0.60

    private val NOISE = Regex("""\s*[\(\[][^)\]]*(official|video|audio|lyric|lyrics|visualizer|remaster(ed)?|hd|hq|4k|mv)[^)\]]*[\)\]]""", RegexOption.IGNORE_CASE)
    private val FEAT = Regex("""\s*[\(\[]?\b(feat\.?|ft\.?|featuring)\b.*$""", RegexOption.IGNORE_CASE)

    fun normalize(text: String): String = text.lowercase()
        .replace(NOISE, " ").replace(FEAT, " ").replace(" - topic", " ").replace('&', ' ')
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun tokens(text: String) = normalize(text).split(' ').filter { it.isNotEmpty() }.toSet()

    private fun dice(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else 2.0 * a.intersect(b).size / (a.size + b.size)

    /** Stable id first (confidence 1.0); otherwise a weighted score of title, artist and duration. */
    fun best(track: ImportedTrack, candidates: List<MatchCandidate>): MatchResult? {
        track.videoId?.let { id -> candidates.firstOrNull { it.videoId == id }?.let { return MatchResult(it, 1.0, MatchKind.ID) } }
        if (track.title.isBlank()) return null
        return candidates.map { MatchResult(it, score(track, it), MatchKind.METADATA) }
            .filter { it.confidence >= MINIMUM }.maxByOrNull { it.confidence }
    }

    fun score(track: ImportedTrack, candidate: MatchCandidate): Double {
        val title = dice(tokens(track.title), tokens(candidate.title))
        if (title == 0.0) return 0.0
        val artistKnown = track.artist.isNotBlank() && candidate.artist.isNotBlank()
        val artist = if (artistKnown) maxOf(dice(tokens(track.artist), tokens(candidate.artist)),
            if (normalize(candidate.artist).contains(normalize(track.artist)) || normalize(track.artist).contains(normalize(candidate.artist))) 0.9 else 0.0) else 0.5
        val duration = if (track.durationSeconds != null && track.durationSeconds > 0 && candidate.durationSeconds > 0) {
            val d = abs(track.durationSeconds - candidate.durationSeconds)
            when { d <= 3 -> 1.0; d <= 8 -> 0.7; d <= 20 -> 0.3; else -> 0.0 }
        } else 0.6
        // A very different length is strong evidence of a different recording (live, remix, cover).
        val score = title * 0.55 + artist * 0.30 + duration * 0.15
        return if (duration == 0.0 && track.durationSeconds != null) min(score, MINIMUM + 0.05) else score
    }
}
