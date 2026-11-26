package cord.kasane.desktop.lyrics

import cord.kasane.music.lyrics.StoredLyrics
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File

/** JSON-file lyrics store: put/offset/choice semantics plus restart persistence. */
class FileLyricsStoreTest {
    private val dir = createTempDirectory("lyrics-store").toFile()
    private var clock = 1_000L
    private fun store() = FileLyricsStore(File(dir, "lyrics.json")) { clock }

    private fun entry(payload: String = "[00:01.00]hi", userPicked: Boolean = false) =
        StoredLyrics("lrclib", "42", "lrc", payload, offsetMs = 0, userPicked = userPicked)

    @Test fun storedPutOffsetAndChoiceSurvive() = runBlocking {
        val s = store()
        assertNull(s.stored("t1"))
        s.put("t1", entry())
        assertEquals("lrclib", s.stored("t1")?.providerId)
        assertEquals(1_000L, s.stored("t1")?.fetchedAtMs)

        s.setOffset("t1", 250)
        assertEquals(250L, s.stored("t1")?.offsetMs)

        s.setChoice("t1", entry(userPicked = true).copy(providerId = "ytmusic", rawFormat = "plain", rawPayload = "words"))
        s.stored("t1")!!.let {
            assertEquals("ytmusic", it.providerId)
            assertTrue(it.userPicked)
            assertEquals("plain", it.rawFormat)
        }

        // A fresh instance reads the same file — persisted across restarts.
        val reopened = store()
        reopened.stored("t1")!!.let {
            assertEquals("ytmusic", it.providerId)
            assertTrue(it.userPicked)
        }
        Unit
    }

    @Test fun autoPutKeepsUserChoiceAndOffsetForSamePayload() = runBlocking {
        val s = store()
        s.setChoice("t1", entry(userPicked = true).copy(offsetMs = 300))
        clock = 2_000
        s.put("t1", entry()) // automatic re-store of the same payload must not un-pick it
        s.stored("t1")!!.let {
            assertTrue(it.userPicked)
            assertEquals(300L, it.offsetMs)
            assertEquals(2_000L, it.fetchedAtMs)
        }

        // A different provider/payload replaces the record wholesale — it is a new auto-choice.
        s.put("t1", StoredLyrics("amll-ttml", null, "ttml", "<tt/>", offsetMs = 0))
        s.stored("t1")!!.let {
            assertEquals("amll-ttml", it.providerId)
            assertTrue(!it.userPicked)
            assertEquals(0L, it.offsetMs)
        }
        Unit
    }
}

/** The `next`→browseId→browse parsing ported from Android's TrackLyrics. */
class YtmLyricsParserTest {

    private fun nextWithTabs(vararg pageTypes: String?) = buildJsonObject {
        putJsonObject("contents") {
            putJsonObject("singleColumnMusicWatchNextResultsRenderer") {
                putJsonObject("tabbedRenderer") {
                    putJsonObject("watchNextTabbedResultsRenderer") {
                        putJsonArray("tabs") {
                            pageTypes.forEach { type ->
                                add(buildJsonObject {
                                    putJsonObject("tabRenderer") {
                                        type?.let { put("pageType", it) }
                                        putJsonObject("endpoint") {
                                            putJsonObject("browseEndpoint") { put("browseId", "lid-${type ?: "none"}") }
                                        }
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun lyricsBrowseIdFindsTheLyricsTab() {
        val next = nextWithTabs("MUSIC_PAGE_TYPE_TRACK_LYRICS", "MUSIC_PAGE_TYPE_TRACK_RELATED")
        assertEquals("lid-MUSIC_PAGE_TYPE_TRACK_LYRICS", YtmLyricsParser.lyricsBrowseId(next))
    }

    @Test fun lyricsBrowseIdIsNullWithoutTheTab() {
        assertNull(YtmLyricsParser.lyricsBrowseId(nextWithTabs("MUSIC_PAGE_TYPE_TRACK_RELATED", null)))
        assertNull(YtmLyricsParser.lyricsBrowseId(buildJsonObject { }))
    }

    /** Real `next` payloads leave tabRenderer.pageType null — the lyrics marker sits in the
     *  endpoint's browse context (and lyrics browseIds carry the MPLY prefix). */
    private fun realWorldTabs() = buildJsonObject {
        putJsonObject("contents") {
            putJsonObject("singleColumnMusicWatchNextResultsRenderer") {
                putJsonObject("tabbedRenderer") {
                    putJsonObject("watchNextTabbedResultsRenderer") {
                        putJsonArray("tabs") {
                            add(buildJsonObject {
                                putJsonObject("tabRenderer") {
                                    put("title", "Up next")
                                    putJsonObject("endpoint") { putJsonObject("watchEndpoint") { } }
                                }
                            })
                            add(buildJsonObject {
                                putJsonObject("tabRenderer") {
                                    put("title", "Lyrics")
                                    putJsonObject("endpoint") {
                                        putJsonObject("browseEndpoint") {
                                            put("browseId", "MPLYt_vsx0QCCgQjT-3")
                                            putJsonObject("browseEndpointContextSupportedConfigs") {
                                                putJsonObject("browseEndpointContextMusicConfig") {
                                                    put("pageType", "MUSIC_PAGE_TYPE_TRACK_LYRICS")
                                                }
                                            }
                                        }
                                    }
                                }
                            })
                            add(buildJsonObject {
                                putJsonObject("tabRenderer") {
                                    put("title", "Related")
                                    putJsonObject("endpoint") {
                                        putJsonObject("browseEndpoint") {
                                            put("browseId", "MPTRt_vsx0QCCgQjT-3")
                                            putJsonObject("browseEndpointContextSupportedConfigs") {
                                                putJsonObject("browseEndpointContextMusicConfig") {
                                                    put("pageType", "MUSIC_PAGE_TYPE_TRACK_RELATED")
                                                }
                                            }
                                        }
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }
    }

    @Test fun lyricsBrowseIdReadsTheEndpointContextLikeRealPayloads() {
        assertEquals("MPLYt_vsx0QCCgQjT-3", YtmLyricsParser.lyricsBrowseId(realWorldTabs()))
    }

    private fun descriptionPage(text: String, footer: String? = null) = buildJsonObject {
        putJsonObject("contents") {
            putJsonObject("sectionListRenderer") {
                putJsonArray("contents") {
                    add(buildJsonObject {
                        putJsonObject("musicDescriptionShelfRenderer") {
                            putJsonObject("description") {
                                putJsonArray("runs") {
                                    add(buildJsonObject { put("text", text.substring(0, 3)) })
                                    add(buildJsonObject { put("text", text.substring(3)) })
                                }
                            }
                            footer?.let { f ->
                                putJsonObject("footer") {
                                    putJsonArray("runs") { add(buildJsonObject { put("text", f) }) }
                                }
                            }
                        }
                    })
                }
            }
        }
    }

    @Test fun descriptionShelfYieldsTextAndSource() {
        val page = descriptionPage("hello world", "Source: Musixmatch")
        val lyrics = YtmLyricsParser.lyrics(page)!!
        assertEquals("hello world", lyrics.text)
        assertEquals("Musixmatch", lyrics.source)
    }

    @Test fun emptyDescriptionYieldsNull() {
        assertNull(YtmLyricsParser.lyrics(descriptionPage("   ")))
        assertNull(YtmLyricsParser.lyrics(buildJsonObject { }))
    }
}
