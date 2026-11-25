package cord.kasane.music.lyrics

/**
 * LRC family parser: plain `[mm:ss.xx]` line timing, metadata tags, repeated
 * timestamps, and the A2 enhanced extension `<mm:ss.xx>` word tags inside a line.
 */
object LrcParser {
    private val LINE_TAG = Regex("""\[(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?\]""")
    private val META_TAG = Regex("""^\[([a-zA-Z]+):(.+)]\s*$""")
    private val WORD_TAG = Regex("""<(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?>""")
    private const val INLINE_SPAN = '\u0000'

    fun parse(raw: String, providerId: String, sourceRef: String? = null,
              title: String? = null, artist: String? = null, album: String? = null): LyricsDocument? {
        var offsetMs = 0L
        var metaTitle = title; var metaArtist = artist; var metaAlbum = album
        val lines = mutableListOf<LyricLine>()
        var anyWordTags = false
        for (row in raw.lineSequence()) {
            val trimmed = row.trim()
            if (trimmed.isEmpty()) continue
            val starts = mutableListOf<Long>()
            var consumed = 0
            while (true) {
                val tag = LINE_TAG.find(trimmed, consumed) ?: break
                if (tag.range.first != consumed) break
                starts += lrcTimeMs(tag.groupValues) ?: break
                consumed = tag.range.last + 1
            }
            if (starts.isEmpty()) {
                META_TAG.find(trimmed)?.let { meta ->
                    val value = meta.groupValues[2].trim()
                    when (meta.groupValues[1].lowercase()) {
                        "ti" -> if (metaTitle == null) metaTitle = value
                        "ar" -> if (metaArtist == null) metaArtist = value
                        "al" -> if (metaAlbum == null) metaAlbum = value
                        "offset" -> offsetMs = value.toLongOrNull() ?: 0L
                    }
                }
                continue
            }
            val text = trimmed.substring(consumed).trim()
            val words = parseEnhanced(text)
            if (words != null) anyWordTags = true
            val plainText = words?.joinToString("") { it.text }?.trim() ?: text
            for (start in starts) {
                val end = words?.lastOrNull()?.endMs ?: start
                lines += LyricLine(start, end, plainText, words.orEmpty())
            }
        }
        if (lines.isEmpty()) return null
        lines.sortBy { it.startMs }
        val bounded = boundLineEnds(lines)
        val kind = when {
            anyWordTags || bounded.any { it.words.isNotEmpty() } -> LyricsSyncKind.WORD
            else -> LyricsSyncKind.LINE
        }
        return LyricsDocument(bounded, kind, providerId, sourceRef, null, metaTitle, metaArtist, metaAlbum,
            offsetMs = -offsetMs /* LRC offset>0 means "lyrics earlier": negate into our shift-later convention */)
    }

    /** Null when the line has no `<mm:ss.xx>` word tags. */
    private fun parseEnhanced(text: String): List<LyricWord>? {
        if (!text.contains('<')) return null
        val tags = WORD_TAG.findAll(text).toList()
        if (tags.isEmpty()) return null
        val words = mutableListOf<LyricWord>()
        for (i in tags.indices) {
            val tag = tags[i]
            val start = lrcTimeMs(tag.groupValues) ?: continue
            val nextStart = tags.getOrNull(i + 1)?.let { lrcTimeMs(it.groupValues) }
            // Word text sits between this tag and the next one.
            val wordText = text.substring(tag.range.last + 1, tags.getOrNull(i + 1)?.range?.first ?: text.length)
            if (wordText.isEmpty() && i == tags.lastIndex) continue
            words += LyricWord(wordText, start, nextStart ?: start, isBackground = false)
        }
        return words.takeIf { it.isNotEmpty() }
    }

    /** A line's end is the next line's start when the source did not say otherwise. */
    internal fun boundLineEnds(lines: List<LyricLine>): List<LyricLine> =
        lines.mapIndexed { i, line ->
            val inferred = if (line.endMs <= line.startMs) lines.getOrNull(i + 1)?.startMs ?: line.startMs else line.endMs
            line.copy(endMs = inferred)
        }

    internal fun lrcTimeMs(groups: List<String>): Long? {
        val mm = groups.getOrNull(1)?.toLongOrNull() ?: return null
        val ss = groups.getOrNull(2)?.toLongOrNull() ?: return null
        val frac = groups.getOrNull(3).orEmpty()
        val ms = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLongOrNull() ?: 0L
        }
        return mm * 60_000 + ss * 1_000 + ms
    }
}
