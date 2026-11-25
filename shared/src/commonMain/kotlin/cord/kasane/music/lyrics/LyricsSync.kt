package cord.kasane.music.lyrics

/** Playback-position → lyrics highlighting math. Pure: the clients only pass positionMs. */
object LyricsSync {

    /** Index of the line active at [positionMs] after the user's offset; -1 before the first line. */
    fun activeLine(document: LyricsDocument, positionMs: Long): Int {
        if (document.syncKind == LyricsSyncKind.UNSYNCED || document.lines.isEmpty()) return -1
        val t = positionMs - document.offsetMs
        var lo = 0; var hi = document.lines.lastIndex; var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (document.lines[mid].startMs <= t) { result = mid; lo = mid + 1 } else hi = mid - 1
        }
        return result
    }

    /** Index of the word being sung at [positionMs] inside [line]; -1 when before the first word. */
    fun activeWord(line: LyricLine, positionMs: Long, offsetMs: Long = 0): Int {
        if (line.words.isEmpty()) return -1
        val t = positionMs - offsetMs
        var index = -1
        for (i in line.words.indices) {
            if (line.words[i].startMs <= t) index = i else break
        }
        return index
    }

    /** 0..1 fill of the word at [index] — drives the karaoke-style animated fill of the active word. */
    fun wordFill(line: LyricLine, index: Int, positionMs: Long, offsetMs: Long = 0): Float {
        val word = line.words.getOrNull(index) ?: return 0f
        val span = word.endMs - word.startMs
        if (span <= 0) return 1f
        val t = positionMs - offsetMs
        return ((t - word.startMs).toFloat() / span).coerceIn(0f, 1f)
    }

    /** True while playback sits in a gap between two sung lines — the instrumental-break indicator. */
    fun inInstrumentalBreak(document: LyricsDocument, positionMs: Long): Boolean {
        val active = activeLine(document, positionMs)
        if (active >= 0) {
            val line = document.lines[active]
            if (!line.isInstrumentalBreak && positionMs - document.offsetMs > line.endMs) {
                val next = document.lines.getOrNull(active + 1)
                if (next != null && next.startMs - line.endMs >= BREAK_MIN_MS) return true
            }
            return line.isInstrumentalBreak
        }
        val first = document.lines.firstOrNull() ?: return false
        return positionMs - document.offsetMs < first.startMs && first.startMs >= BREAK_MIN_MS
    }

    /** Seek target for tap-to-seek on a line: the line's start in player position space. */
    fun seekPosition(line: LyricLine, offsetMs: Long = 0): Long = line.startMs + offsetMs

    const val BREAK_MIN_MS = 3_000L
}
