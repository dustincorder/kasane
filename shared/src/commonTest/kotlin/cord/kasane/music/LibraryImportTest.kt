package cord.kasane.music

import cord.kasane.music.library.*
import kotlin.test.*

class LibraryImportTest {
    private val id1 = "dQw4w9WgXcQ"
    private val id2 = "9bZkp7q19f0"

    @Test fun videoIdsAreExtractedFromEveryYoutubeUrlShape() {
        assertEquals(id1, VideoIds.extract("https://music.youtube.com/watch?v=$id1&list=PLx"))
        assertEquals(id1, VideoIds.extract("https://youtu.be/$id1?t=4"))
        assertEquals(id1, VideoIds.extract("https://www.youtube.com/shorts/$id1"))
        assertEquals(id1, VideoIds.extract(id1))
        assertNull(VideoIds.extract("https://example.com/watch?v=$id1"))
        assertNull(VideoIds.extract("not an id"))
    }

    @Test fun m3uReadsExtinfDurationsArtistsAndYoutubeLinks() {
        val bundle = PlaylistFormats.parseM3u("\uFEFF#EXTM3U\n#PLAYLIST:Night drive\n#EXTINF:215,Aether - Midnight City Lights\n/music/a.mp3\n#EXTINF:-1,Some Title\nhttps://music.youtube.com/watch?v=$id1\n")
        val playlist = bundle.playlists.single()
        assertEquals("Night drive", playlist.name)
        assertEquals(ImportedTrack("Midnight City Lights", "Aether", durationSeconds = 215.0), playlist.tracks[0])
        assertEquals(id1, playlist.tracks[1].videoId)
        assertNull(playlist.tracks[1].durationSeconds)
    }

    @Test fun plsEntriesAreOrderedByIndex() {
        val bundle = PlaylistFormats.parsePls("[playlist]\nFile2=b.mp3\nTitle2=Beta - Two\nLength2=100\nFile1=a.mp3\nTitle1=Alpha - One\nNumberOfEntries=2\n")
        assertEquals(listOf("One", "Two"), bundle.playlists.single().tracks.map { it.title })
        assertEquals("Alpha", bundle.playlists.single().tracks[0].artist)
        assertFailsWith<ImportFormatException> { PlaylistFormats.parsePls("nothing here") }
    }

    @Test fun csvHandlesQuotedCommasSpotifyColumnsAndSemicolonExports() {
        val spotify = "Track Name,Artist Name(s),Album Name,Duration (ms)\n\"Hello, World\",\"A;B\",Album,185000\n"
        val track = PlaylistFormats.parseCsv(spotify).playlists.single().tracks.single()
        assertEquals("Hello, World", track.title)
        assertEquals("A, B", track.artist)
        assertEquals(185.0, track.durationSeconds)
        val semicolon = PlaylistFormats.parseCsv("title;artist;url\nSong;Band;https://youtu.be/$id2\n").playlists.single().tracks.single()
        assertEquals(id2, semicolon.videoId)
        assertFailsWith<ImportFormatException> { PlaylistFormats.parseCsv("foo,bar\n1,2\n") }
    }

    @Test fun genericJsonAcceptsArraysAndNestedPlaylists() {
        val flat = PlaylistFormats.parseJson("""[{"title":"A","artist":"B","videoId":"$id1"},{"name":"C","artists":[{"name":"D"},{"name":"E"}]}]""")
        assertEquals(2, flat.playlists.single().tracks.size)
        assertEquals("D, E", flat.playlists.single().tracks[1].artist)
        val nested = PlaylistFormats.parseJson("""{"playlists":[{"name":"Mix","songs":[{"title":"X","id":"$id2"}]}]}""")
        assertEquals("Mix", nested.playlists.single().name)
        assertFailsWith<ImportFormatException> { PlaylistFormats.parseJson("{") }
        assertFailsWith<ImportFormatException> { PlaylistFormats.parseJson("""{"unrelated":1}""") }
    }

    @Test fun kasaneExportRoundTripsAndRejectsNewerVersions() {
        val bundle = ImportBundle("kasane-library",
            playlists = listOf(ImportedPlaylist("Mix", listOf(ImportedTrack("T", "A", "Al", 120.0, id1)))),
            favorites = listOf(ImportedTrack("F", "Z", videoId = id2)),
            history = listOf(ImportedPlay(ImportedTrack("H", "Y", videoId = id1), 1234L)))
        val json = KasaneExport.encode(bundle, "0.1.1", 99L)
        assertFalse(json.contains("cookie", ignoreCase = true))
        assertEquals(bundle, PlaylistFormats.parseJson(json))
        assertFailsWith<ImportFormatException> { PlaylistFormats.parseJson(json.replace("\"version\": 1", "\"version\": 2")) }
    }

    @Test fun matcherPrefersStableIdsOverMetadata() {
        val candidates = listOf(MatchCandidate(id1, "Completely different", "Nobody"), MatchCandidate(id2, "Midnight City", "Aether", 215.0))
        val byId = TrackMatcher.best(ImportedTrack("Midnight City", "Aether", videoId = id1), candidates)!!
        assertEquals(MatchKind.ID, byId.kind); assertEquals(id1, byId.candidate.videoId); assertEquals(1.0, byId.confidence)
    }

    @Test fun matcherIgnoresDecorationButNotDifferentRecordings() {
        val studio = MatchCandidate(id1, "Midnight City", "Aether", 215.0)
        val decorated = TrackMatcher.best(ImportedTrack("Midnight City (Official Video) feat. Someone", "Aether - Topic", durationSeconds = 216.0), listOf(studio))!!
        assertTrue(decorated.confidence >= TrackMatcher.AUTO_ACCEPT, "was ${decorated.confidence}")
        val live = TrackMatcher.best(ImportedTrack("Midnight City", "Aether", durationSeconds = 480.0), listOf(studio))
        assertTrue(live == null || live.needsReview, "a 4-minute-longer recording must not auto-match")
        assertNull(TrackMatcher.best(ImportedTrack("Totally Unrelated Song", "Other"), listOf(studio)))
    }

    @Test fun plannerReportsDuplicatesUnresolvedAndNeverOverwritesPlaylists() {
        val candidates = listOf(MatchCandidate(id1, "Midnight City", "Aether", 215.0))
        val bundle = ImportBundle("m3u", playlists = listOf(ImportedPlaylist("Mix", listOf(
            ImportedTrack("Midnight City", "Aether", videoId = id1), ImportedTrack("Midnight City", "Aether"),
            ImportedTrack("Missing Song", "Ghost"))))
            , favorites = listOf(ImportedTrack("Midnight City", "Aether", videoId = id1)))
        val plan = ImportPlanner.plan(bundle, candidates, existingPlaylistNames = setOf("mix"), existingFavoriteIds = setOf(id1))
        val playlist = plan.playlists.single()
        assertTrue(playlist.renamed); assertEquals("Mix (imported)", playlist.finalName)
        assertEquals(listOf(Resolution.MATCHED_BY_ID, Resolution.DUPLICATE, Resolution.UNRESOLVED), playlist.tracks.map { it.resolution })
        assertEquals(Resolution.DUPLICATE, plan.favorites.single().resolution)
        assertEquals(1, plan.importable)
        val report = ImportPlanner.report(plan)
        assertTrue(report.contains("Ghost - Missing Song")); assertTrue(report.contains("already exists"))
    }
}
