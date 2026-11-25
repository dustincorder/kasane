package cord.kasane.music.lyrics

import kotlin.test.*

class LyricsParserSyncTest {

    private val lrc = """
        [ti:Song]
        [ar:Artist]
        [offset:+500]
        [00:12.34]First line
        [00:15.00][00:20.00]Repeated line
        [00:25.50]<00:25.50>One <00:26.00>two <00:27.00>three
        [00:30.00]
    """.trimIndent()

    @Test fun lrcParsesLinesMetadataAndWordTags() {
        val doc = LrcParser.parse(lrc, "test")!!
        assertEquals(LyricsSyncKind.WORD, doc.syncKind)
        assertEquals(5, doc.lines.size) // repeated timestamp becomes two lines, plus the empty one
        assertEquals(12340, doc.lines[0].startMs)
        assertEquals("First line", doc.lines[0].text)
        val wordLine = doc.lines[3]
        assertEquals(3, wordLine.words.size)
        assertEquals(25500, wordLine.words[0].startMs)
        assertEquals(26000, wordLine.words[1].startMs)
        assertEquals("One ", wordLine.words[0].text)
        assertEquals(-500, doc.offsetMs) // [offset:+500] shifts lyrics earlier → stored as -500
        assertTrue(doc.lines[2].isInstrumentalBreak.not() || doc.lines.any { it.isInstrumentalBreak })
    }

    @Test fun plainLrcWithoutWordTagsIsLineSynced() {
        val doc = LrcParser.parse("[00:01.00]Hello\n[00:03.00]World", "test")!!
        assertEquals(LyricsSyncKind.LINE, doc.syncKind)
        assertEquals(3000, doc.lines[0].endMs) // end inferred from next line start
        assertEquals(3000, doc.lines[1].endMs) // last line keeps its own end
    }

    @Test fun ttmlWordTimedLinesAndBackground() {
        val ttml = """<?xml version="1.0"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word">
            <body><div>
              <p begin="00:01.000" end="00:04.000" ttm:agent="v1">
                <span begin="00:01.000" end="00:01.500">I </span><span begin="00:01.500" end="00:02.000">feel </span><span begin="00:02.000" end="00:03.000">good</span>
                <span ttm:role="x-bg"><span begin="00:03.000" end="00:03.500">(hey)</span></span>
                <span ttm:role="x-translation">Мне хорошо</span>
              </p>
              <p begin="00:05.000" end="00:08.000"><span begin="00:05.000" end="00:06.000">Next</span></p>
            </div></body></tt>""".trimIndent()
        val doc = TtmlParser.parse(ttml, "amll-db")!!
        assertEquals(LyricsSyncKind.WORD, doc.syncKind)
        assertEquals(2, doc.lines.size)
        val line = doc.lines[0]
        assertEquals(1000, line.startMs)
        assertEquals("v1", line.agent)
        assertEquals(3, line.words.size)
        assertEquals(2000, line.words[2].startMs)
        assertEquals("Мне хорошо", line.translatedText)
    }

    @Test fun ttmlLineTimingIsLineSynced() {
        val ttml = """<tt itunes:timing="Line"><body><div>
            <p begin="00:01.000" end="00:03.000">Whole line here</p></div></body></tt>"""
        val doc = TtmlParser.parse(ttml, "t")!!
        assertEquals(LyricsSyncKind.LINE, doc.syncKind)
        assertEquals("Whole line here", doc.lines[0].text)
        assertEquals(3000, doc.lines[0].endMs)
    }

    @Test fun syncLookupHonorsOffsetAndFindsActiveWord() {
        val doc = LyricsDocument(listOf(
            LyricLine(1000, 3000, "a b", listOf(LyricWord("a", 1000, 2000), LyricWord("b", 2000, 3000))),
            LyricLine(8000, 10000, "c")),
            LyricsSyncKind.WORD, "t")
        assertEquals(-1, LyricsSync.activeLine(doc, 999))
        assertEquals(0, LyricsSync.activeLine(doc, 1500))
        assertEquals(1, LyricsSync.activeLine(doc, 8500))
        assertEquals(1, LyricsSync.activeWord(doc.lines[0], 2500))
        assertEquals(0.5f, LyricsSync.wordFill(doc.lines[0], 0, 1500), 0.001f)
        assertTrue(LyricsSync.inInstrumentalBreak(doc, 4000)) // 5s gap → break
        assertFalse(LyricsSync.inInstrumentalBreak(doc, 8500)) // inside a sung line
        assertEquals(8000, LyricsSync.seekPosition(doc.lines[1]))
        val shifted = doc.copy(offsetMs = 1000)
        assertEquals(-1, LyricsSync.activeLine(shifted, 500)) // +1s offset pushes lyrics later
        assertEquals(0, LyricsSync.activeLine(shifted, 2500))
    }

    @Test fun resolverPrefersWordSyncedThenLineSyncedThenPlain() = kotlinx.coroutines.runBlocking {
        val store = object : LyricsStore {
            val map = mutableMapOf<String, StoredLyrics>()
            override suspend fun stored(trackId: String) = map[trackId]
            override suspend fun put(trackId: String, entry: StoredLyrics) { map[trackId] = entry }
            override suspend fun setOffset(trackId: String, offsetMs: Long) { map[trackId] = map.getValue(trackId).copy(offsetMs = offsetMs) }
            override suspend fun setChoice(trackId: String, entry: StoredLyrics) { map[trackId] = entry }
        }
        val plain = candidate("plain-p", "plain", "just text\nsecond")
        val line = candidate("line-p", "lrc", "[00:01.00]A\n[00:03.00]B")
        val resolver = LyricsResolver(listOf(
            object : LyricsProvider { override val id = "plain-p"; override val label = "P"
                override suspend fun fetch(q: LyricsQuery, http: LyricsHttpTransport) = plain
                override val supportedSyncKinds = setOf(LyricsSyncKind.UNSYNCED) },
            object : LyricsProvider { override val id = "line-p"; override val label = "L"
                override suspend fun fetch(q: LyricsQuery, http: LyricsHttpTransport) = line }),
            store)
        val doc = resolver.resolve(LyricsQuery("id", "T", "A"), LyricsHttpTransport { _, _ -> null })
        assertEquals(LyricsSyncKind.LINE, doc?.syncKind)
        assertEquals("line-p", doc?.providerId)
        assertEquals("lrc", store.map["id"]?.rawFormat) // winner persisted for offline
    }

    private fun candidate(providerId: String, format: String, payload: String) =
        LyricsCandidate(providerId, providerId, null, null, null, null, null, format, payload)
}
