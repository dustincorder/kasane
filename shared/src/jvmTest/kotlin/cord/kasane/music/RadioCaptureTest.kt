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

class CreatorCaptureTest {
    private fun page(name: String) = CatalogJsonParser.page(Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/captured/$name")).readBytes().decodeToString()) as JsonObject)

    @Test fun albumCreatorIsTheLinkedArtistWithAvatarAndYearStaysSecondary() {
        val p = page("album_kira_introduction_guest.json")
        val artist = p.creators.single()
        assertEquals("KIRA", artist.name); assertEquals("UCXFEPK5iEJ1MQQmjfleJxlA", artist.browseId)
        assertFalse(artist.userChannel); assertTrue(artist.avatar!!.startsWith("https://yt3.ggpht.com/"))
        assertEquals("Album • 2018", p.subtitle); assertEquals("11 songs • 37 minutes", p.stats); assertFalse(p.owned)
    }

    @Test fun publicPlaylistCreatorIsTheOwnerChannelNotATrackArtist() {
        val p = page("playlist_phonk_public_guest.json")
        val owner = p.creators.single()
        assertEquals("Phonk Time", owner.name); assertEquals("UC50Skh8HNEXD5Ir4StWh5MQ", owner.browseId)
        assertTrue(owner.userChannel); assertTrue(owner.avatar!!.startsWith("https://yt3.ggpht.com/"))
        assertTrue(p.stats.contains("64 tracks"))
        assertTrue(p.tracks.none { it.artist == owner.name })
    }

    @Test fun missingOrNonLinkedCreatorsAreNeverInvented() {
        val none = CatalogJsonParser.page(Json.parseToJsonElement("""{"header":{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Mix"}]}}}}""") as JsonObject)
        assertTrue(none.creators.isEmpty())
        val unlinked = CatalogJsonParser.creators(Json.parseToJsonElement("""{"straplineTextOne":{"runs":[{"text":"YouTube Music"}]}}""") as JsonObject)
        assertEquals(listOf(cord.kasane.music.model.CatalogCreator("YouTube Music")), unlinked)
        val two = CatalogJsonParser.creators(Json.parseToJsonElement("""{"straplineTextOne":{"runs":[
          {"text":"A","navigationEndpoint":{"browseEndpoint":{"browseId":"UCa"}}},{"text":" & "},{"text":"B","navigationEndpoint":{"browseEndpoint":{"browseId":"UCb"}}}]}}""") as JsonObject)
        assertEquals(listOf("A", "B"), two.map { it.name }); assertEquals(listOf("UCa", "UCb"), two.map { it.browseId })
    }
}

class TrackMetadataTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun radioTracksCarryArtistAndAlbumSeparatelyNotTheWholeByline() {
        val root = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/captured/next_radio_monster_guest.json")).readBytes().decodeToString()) as JsonObject
        val seed = CatalogJsonParser.radio(root).tracks.first()
        assertEquals("KIRA", seed.artist); assertEquals("The Introduction", seed.album)
        assertTrue(CatalogJsonParser.radio(root).tracks.none { '•' in it.artist })
    }

    @Test fun albumRowsWithoutAnArtistColumnInheritTheAlbumsArtistAndTitle() {
        val page = CatalogJsonParser.page(Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/captured/album_kira_introduction_guest.json")).readBytes().decodeToString()) as JsonObject)
        val songs = page.shelves.flatMap { it.items }.filter { it.kind == cord.kasane.music.model.CatalogKind.SONG }
        assertTrue(songs.isNotEmpty())
        assertTrue(songs.all { it.subtitle == "KIRA" }, "every album track names its artist: ${songs.map { it.subtitle }}")
        assertTrue(songs.all { it.album == "The Introduction" })
        assertTrue(page.tracks.all { it.artist == "KIRA" && it.album == "The Introduction" })
    }

    @Test fun playCountsAreNeverMistakenForAnArtist() {
        val row = obj("""{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"abcdefghijk"},"flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Title"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"12K plays"}]}}}]}}""")
        assertEquals("", CatalogJsonParser.item(row)!!.subtitle)
        assertNull(CatalogJsonParser.item(row)!!.album)
    }

    @Test fun playlistRowsKeepArtistAndAlbumColumns() {
        val row = obj("""{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"abcdefghijk"},"flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Аполлинария"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Электрослабость","navigationEndpoint":{"browseEndpoint":{"browseId":"UCart"}}}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Имена, животные и бабка без квартиры","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_alb"}}}]}}}]}}""")
        val track = CatalogJsonParser.item(row)!!.track()!!
        assertEquals("Электрослабость", track.artist); assertEquals("Имена, животные и бабка без квартиры", track.album)
    }

    @Test fun trackLinksUseTheRealLinkedNamesNeverPlaceholders() {
        val root = obj("""{"a":{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"abcdefghijk"},"flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"T"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Электрослабость","navigationEndpoint":{"browseEndpoint":{"browseId":"UCart"}}},{"text":" • "},{"text":"Альбом","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_alb"}}}]}}}]}}}""")
        val links = cord.kasane.music.provider.TrackLinksJsonParser.parse(root).getValue("abcdefghijk")
        assertEquals("Электрослабость", links.artist!!.title); assertEquals("Альбом", links.album!!.title)
        assertFalse(links.artist!!.title == "Artist" || links.album!!.title == "Album")
    }
}
