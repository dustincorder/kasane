package cord.kasane.music

import cord.kasane.music.model.Track
import cord.kasane.music.playback.QueuePolicy
import cord.kasane.music.provider.CatalogJsonParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

/** Parses a real YouTube Music `next` radio response for "MONSTER (feat. GUMI)" by KIRA (guest, captured 2026-11-22). */
class RadioCaptureTest {
    private val root = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/captured/next_radio_monster_guest.json")).readBytes().decodeToString()) as JsonObject
    private val page = CatalogJsonParser.radio(root)
    private val seed = Track("iWxGwJy7Jv4", "MONSTER (feat. GUMI)", "KIRA")

    @Test fun realResponseYieldsTheSeedFirstAndAContinuation() {
        assertEquals("iWxGwJy7Jv4", page.tracks.first().id)
        assertEquals(12, page.tracks.size)
        assertEquals(page.tracks.size, page.tracks.map { it.id }.toSet().size)
        assertNotNull(page.continuation, "radio must be extendable through YouTube's own continuation")
        assertTrue(page.tracks.all { it.title.isNotBlank() && it.artist.isNotBlank() })
    }

    @Test fun theSeedIsNotRepeatedWhenAppendedToTheQueue() {
        val appended = QueuePolicy.radioAppend(setOf(seed.id), QueuePolicy.dropSeedAliases(page.tracks, seed), 100)
        assertFalse(appended.any { it.id == seed.id })
        assertEquals(page.tracks.size - 1, appended.size)
        assertEquals("Machine Gun (feat. GUMI)", appended.first().title, "order is YouTube's, untouched")
    }

    @Test fun anAliasOfTheSeedUnderAnotherVideoIdIsDropped() {
        val alias = Track("otherVideoId", "Monster (Official Video)", "KIRA • The Introduction")
        val kept = QueuePolicy.dropSeedAliases(listOf(alias, Track("x", "Machine Gun", "KIRA")), Track("a", "Monster", "KIRA"))
        assertEquals(listOf("x"), kept.map { it.id })
        assertEquals(2, QueuePolicy.dropSeedAliases(listOf(Track("p", "Monster", "Meg & Dia"), Track("q", "Other", "KIRA")), Track("a", "Monster", "KIRA")).size)
    }
}

class RadioRequestTest {
    @Test fun startRadioSendsTheSeedAutomixPlaylistWithRadioParams() = kotlinx.coroutines.runBlocking {
        var body = ""
        val client = cord.kasane.music.provider.InnerTubeClient({ request ->
            body = request.body
            cord.kasane.music.provider.CatalogHttpResponse(200, "{}")
        }, nowSeconds = { 0 }, sha1Hex = { "0".repeat(40) })
        cord.kasane.music.provider.SharedOnlineCatalog(client).radioPage("iWxGwJy7Jv4")
        val sent = Json.parseToJsonElement(body) as JsonObject
        assertEquals("iWxGwJy7Jv4", sent["videoId"].toString().trim('"'))
        assertEquals("RDAMVMiWxGwJy7Jv4", sent["playlistId"].toString().trim('"'))
        assertEquals("wAEB", sent["params"].toString().trim('"'))
        assertEquals("true", sent["isAudioOnly"].toString())
        // A continuation request carries only the token: the seed is already encoded in it.
        cord.kasane.music.provider.SharedOnlineCatalog(client).radioPage("iWxGwJy7Jv4", "TOKEN")
        val next = Json.parseToJsonElement(body) as JsonObject
        assertEquals("TOKEN", next["continuation"].toString().trim('"')); assertNull(next["playlistId"])
    }
}
