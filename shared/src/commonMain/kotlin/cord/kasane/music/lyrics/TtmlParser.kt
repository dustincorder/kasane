package cord.kasane.music.lyrics

/**
 * Apple/AMLL-style TTML lyrics: `<p begin end>` lines, per-word `<span begin end>` children
 * when `itunes:timing="Word"`, `ttm:role="x-bg"` background vocals, `x-translation`/`x-roman`
 * auxiliary texts. Uses a deliberately small XML reader — lyrics files are a bounded input.
 */
object TtmlParser {
    fun parse(raw: String, providerId: String, sourceRef: String? = null,
              title: String? = null, artist: String? = null, album: String? = null): LyricsDocument? {
        val root = XmlReader.parse(raw) ?: return null
        if (!root.name.endsWith("tt")) return null
        val timing = root.attrs.entries.firstOrNull { it.key.endsWith(":timing") || it.key == "timing" }?.value.orEmpty()
        val metadata = root.descendant("head")?.descendant("metadata")
        val lines = mutableListOf<LyricLine>()
        for (div in root.descendants("div")) {
            for (p in div.children("p")) {
                val begin = parseTime(p.attrs["begin"]) ?: continue
                val end = parseTime(p.attrs["end"]) ?: begin
                val agent = p.attrs.entries.firstOrNull { it.key.endsWith(":agent") }?.value
                var translation: String? = null
                var romanization: String? = null
                var backgroundText: String? = null
                val words = mutableListOf<LyricWord>()
                val directText = StringBuilder()
                for (child in p.children) {
                    when (roleOf(child)) {
                        "x-translation" -> translation = child.allText().trim()
                        "x-roman" -> romanization = child.allText().trim()
                        "x-bg" -> backgroundText = child.allText().trim()
                        else -> {
                            val ws = parseTime(child.attrs["begin"]); val we = parseTime(child.attrs["end"])
                            if (ws != null) words += LyricWord(child.allText(), ws, we ?: ws)
                            else directText.append(child.allText())
                        }
                    }
                }
                val lineText = if (words.isNotEmpty())
                    (directText.toString() + words.joinToString("") { it.text }).trim()
                else (directText.toString() + p.textNodes().joinToString("")).trim().ifEmpty { p.allText().trim() }
                lines += LyricLine(begin, end, lineText, words, agent,
                    translatedText = translation?.takeIf { it.isNotEmpty() },
                    romanizedText = romanization?.takeIf { it.isNotEmpty() })
            }
        }
        if (lines.isEmpty()) return null
        lines.sortBy { it.startMs }
        val kind = when {
            timing.equals("Word", ignoreCase = true) || lines.any { it.words.isNotEmpty() } -> LyricsSyncKind.WORD
            timing.equals("None", ignoreCase = true) -> LyricsSyncKind.UNSYNCED
            else -> LyricsSyncKind.LINE
        }
        return LyricsDocument(LrcParser.boundLineEnds(lines), kind, providerId, sourceRef,
            attribution = null, title = title ?: metadata?.valueOf("songName", "title"),
            artist = artist ?: metadata?.valueOf("artists", "artist"), album = album)
    }

    internal fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()
        Regex("""^(\d+(?:\.\d+)?)ms$""").find(v)?.let { return it.groupValues[1].toDouble().toLong() }
        Regex("""^(\d+(?:\.\d+)?)s$""").find(v)?.let { return (it.groupValues[1].toDouble() * 1000).toLong() }
        val parts = v.split(':')
        return when (parts.size) {
            2 -> (parts[0].toLongOrNull() ?: return null) * 60_000 + ((parts[1].toDoubleOrNull() ?: return null) * 1000).toLong()
            3 -> (parts[0].toLongOrNull() ?: return null) * 3_600_000 + (parts[1].toLongOrNull() ?: return null) * 60_000 +
                    ((parts[2].toDoubleOrNull() ?: return null) * 1000).toLong()
            else -> null
        }
    }

    private fun roleOf(node: XmlNode): String? =
        node.attrs.entries.firstOrNull { it.key.endsWith(":role") || it.key == "role" }?.value

    internal class XmlNode(val name: String, val attrs: Map<String, String>) {
        val children = mutableListOf<XmlNode>()
        private val text = StringBuilder()
        fun appendText(chunk: String) = text.append(chunk)
        fun allText(): String = text.toString() + children.joinToString("") { it.allText() }
        fun textNodes(): List<String> = listOf(text.toString())
        fun children(name: String) = children.filter { it.name == name || it.name.endsWith(":$name") }
        fun descendants(name: String): List<XmlNode> =
            children.flatMap { child -> (if (child.name == name || child.name.endsWith(":$name")) listOf(child) else emptyList()) + child.descendants(name) }
        fun descendant(name: String) = descendants(name).firstOrNull()
        fun valueOf(vararg keys: String): String? {
            for (n in descendants("iTunesMetadata")) for (k in keys) {
                n.descendant(k)?.let { return it.allText().trim() }
            }
            return null
        }
    }

    /** Minimal XML reader: elements, attributes, text with entity decoding; comments/PI/DTD skipped. */
    internal object XmlReader {
        fun parse(input: String): XmlNode? {
            var i = 0
            var root: XmlNode? = null
            val stack = ArrayDeque<XmlNode>()
            while (i < input.length) {
                val lt = input.indexOf('<', i)
                if (lt < 0) { stack.lastOrNull()?.appendText(decode(input.substring(i))); break }
                if (lt > i) stack.lastOrNull()?.appendText(decode(input.substring(i, lt)))
                val gt = findTagEnd(input, lt + 1) ?: break
                val tag = input.substring(lt + 1, gt)
                when {
                    tag.startsWith("!--") -> i = input.indexOf("-->", gt).takeIf { it >= 0 }?.plus(3) ?: input.length
                    tag.startsWith("?") || tag.startsWith("!") -> i = gt + 1
                    tag.startsWith("/") -> { if (stack.isNotEmpty()) stack.removeLast(); i = gt + 1 }
                    else -> {
                        val selfClosing = tag.endsWith("/")
                        val body = if (selfClosing) tag.dropLast(1) else tag
                        val name = body.takeWhile { !it.isWhitespace() }.trim()
                        val attrs = parseAttrs(body.substring(name.length))
                        val node = XmlNode(name, attrs)
                        if (stack.isEmpty()) root = node else stack.last().children.add(node)
                        if (!selfClosing) stack.addLast(node)
                        i = gt + 1
                    }
                }
            }
            return root
        }

        private fun findTagEnd(s: String, from: Int): Int? {
            var i = from; var quote: Char? = null
            while (i < s.length) {
                val c = s[i]
                if (quote != null) { if (c == quote) quote = null }
                else when (c) { '"', '\'' -> quote = c; '>' -> return i }
                i++
            }
            return null
        }

        private fun parseAttrs(raw: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val re = Regex("""([\w:.-]+)\s*=\s*("([^"]*)"|'([^']*)')""")
            for (m in re.findAll(raw)) out[m.groupValues[1]] = decode(m.groupValues[3].ifEmpty { m.groupValues[4] })
            return out
        }

        private fun decode(s: String): String {
            if ('&' !in s) return s
            return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&#10;", "\n").replace(Regex("""&#x?([0-9A-Fa-f]+);""")) {
                    it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: ""
                }.replace("&amp;", "&")
        }
    }
}
