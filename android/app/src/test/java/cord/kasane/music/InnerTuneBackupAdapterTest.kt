package cord.kasane.music

import android.database.sqlite.SQLiteDatabase
import cord.kasane.music.library.ImportFormatException
import cord.kasane.music.library.InnerTuneBackupAdapter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InnerTuneBackupAdapterTest {
    @get:Rule val folder = TemporaryFolder()
    private val id1 = "dQw4w9WgXcQ"
    private val id2 = "9bZkp7q19f0"

    /** Schema copied from InnerTune's Room entities; `likedDate` mimics a fork's extra column. */
    private fun backup(extraSongColumn: Boolean = true, withArtists: Boolean = true): ByteArray {
        val file = File(folder.root, "song.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE song (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, duration INTEGER NOT NULL, thumbnailUrl TEXT, albumId TEXT, albumName TEXT, liked INTEGER NOT NULL, totalPlayTime INTEGER NOT NULL, inLibrary INTEGER${if (extraSongColumn) ", likedDate INTEGER" else ""})")
        db.execSQL("INSERT INTO song (id,title,duration,albumName,liked,totalPlayTime) VALUES ('$id1','Midnight City',215,'Neon',1,0)")
        db.execSQL("INSERT INTO song (id,title,duration,albumName,liked,totalPlayTime) VALUES ('$id2','Subway Melancholy',188,NULL,0,0)")
        db.execSQL("INSERT INTO song (id,title,duration,liked,totalPlayTime) VALUES ('local-file-1','Local Track',-1,0,0)")
        if (withArtists) {
            db.execSQL("CREATE TABLE artist (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE song_artist_map (songId TEXT NOT NULL, artistId TEXT NOT NULL, position INTEGER NOT NULL)")
            db.execSQL("INSERT INTO artist VALUES ('a1','Aether'),('a2','Guest')")
            db.execSQL("INSERT INTO song_artist_map VALUES ('$id1','a1',0),('$id1','a2',1),('$id2','a2',0)")
        }
        db.execSQL("CREATE TABLE playlist (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, browseId TEXT)")
        db.execSQL("CREATE TABLE playlist_song_map (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, playlistId TEXT NOT NULL, songId TEXT NOT NULL, position INTEGER NOT NULL)")
        db.execSQL("INSERT INTO playlist VALUES ('LPabcdefgh','Night drive',NULL),('LP_LIKED','Liked',NULL),('LP_DOWNLOADED','Downloaded',NULL)")
        db.execSQL("INSERT INTO playlist_song_map (playlistId,songId,position) VALUES ('LPabcdefgh','$id2',1),('LPabcdefgh','$id1',0),('LPabcdefgh','missing',2),('LP_LIKED','$id1',0)")
        db.execSQL("CREATE TABLE event (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, songId TEXT NOT NULL, timestamp INTEGER NOT NULL, playTime INTEGER NOT NULL)")
        db.execSQL("INSERT INTO event (songId,timestamp,playTime) VALUES ('$id1',1700000000000,200000),('$id2',1700000100000,100000)")
        db.close()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("settings.preferences_pb")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("song.db")); zip.write(file.readBytes()); zip.closeEntry()
        }
        file.delete()
        return out.toByteArray()
    }

    @Test fun readsPlaylistsFavoritesAndHistoryAndSkipsInternalPlaylists() {
        val bundle = InnerTuneBackupAdapter.read(backup().inputStream(), folder.root)
        val playlist = bundle.playlists.single()
        assertEquals("Night drive", playlist.name)
        assertEquals(listOf("Midnight City", "Subway Melancholy"), playlist.tracks.map { it.title })
        assertEquals("Aether, Guest", playlist.tracks[0].artist)
        assertEquals(id1, playlist.tracks[0].videoId)
        assertEquals(215.0, playlist.tracks[0].durationSeconds!!, 0.0)
        assertEquals(listOf("Midnight City"), bundle.favorites.map { it.title })
        assertEquals(listOf(1700000100000L, 1700000000000L), bundle.history.map { it.playedAtMillis })
        assertTrue(bundle.warnings.any { it.contains("Settings") })
    }

    @Test fun toleratesForkSpecificColumnsAndMissingArtistTables() {
        val bundle = InnerTuneBackupAdapter.read(backup(extraSongColumn = false, withArtists = false).inputStream(), folder.root)
        assertEquals("", bundle.playlists.single().tracks[0].artist)
        assertTrue(bundle.warnings.any { it.contains("artist") })
    }

    @Test fun rejectsFilesThatAreNotBackups() {
        val empty = ByteArrayOutputStream().also { ZipOutputStream(it).use { z -> z.putNextEntry(ZipEntry("other.txt")); z.closeEntry() } }.toByteArray()
        assertThrows(ImportFormatException::class.java) { InnerTuneBackupAdapter.read(empty.inputStream(), folder.root) }
        assertThrows(ImportFormatException::class.java) { InnerTuneBackupAdapter.read("not a zip".byteInputStream(), folder.root) }
    }
}
