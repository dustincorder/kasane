package cord.kasane.music.playback

/**
 * When a listen counts toward history — local recording and the YouTube Music watch
 * report share one threshold so both feeds tell the same story. A listen counts after
 * [MIN_SECONDS] of playback, or after half the track for songs shorter than a minute.
 * Merely loading a track (or skipping past it) never counts.
 */
object PlayThreshold {
    const val MIN_SECONDS = 30.0
    const val SHORT_TRACK_FRACTION = 0.5

    fun counts(positionSec: Double, durationSec: Double): Boolean {
        if (positionSec <= 0) return false
        val needed = if (durationSec > 0) minOf(MIN_SECONDS, durationSec * SHORT_TRACK_FRACTION) else MIN_SECONDS
        return positionSec >= needed
    }
}

/**
 * Completes a `playbackTracking` URL from the player response with the measured watch
 * position — the ping YouTube itself sends to count a play into the account history.
 */
object WatchReport {
    /**
     * The ping URL: the template plus only the keys YouTube fills per watch
     * (`pos`/`len`/`rt`/`cmt`/`fmt`/`ver`); keys the service already set are kept.
     */
    fun url(template: String, positionSec: Double, durationSec: Double, elapsedSec: Double = positionSec, fmt: Int = 0): String? {
        if (!template.startsWith("https://")) return null
        fun has(key: String) = Regex("[?&]${Regex.escape(key)}=").containsMatchIn(template)
        return buildString {
            append(template)
            if (!has("ver")) append("&ver=2")
            if (!has("pos")) append("&pos=").append(positionSec.toInt().coerceAtLeast(0))
            if (!has("len")) append("&len=").append(durationSec.toInt().coerceAtLeast(0))
            if (!has("rt")) append("&rt=").append("%.3f".format(elapsedSec))
            if (!has("cmt")) append("&cmt=").append("%.3f".format(positionSec))
            if (!has("fmt") && fmt > 0) append("&fmt=").append(fmt)
        }
    }
}
