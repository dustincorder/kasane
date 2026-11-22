package cord.kasane.music.library

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Adapter for the InnerTune family of `.backup` files, which includes OpenTune (Arturo254) and Metrolist:
 * a ZIP holding the Room database `song.db` plus a settings file. The schema is read by column discovery,
 * not assumed, because each fork adds columns. Verified against InnerTune's entities (song, artist,
 * song_artist_map, playlist, playlist_song_map, event). Settings are never imported: they can hold the
 * account cookie, which Kasane must not ingest.
 */
object InnerTuneBackupAdapter {
    private const val MAX_DB_BYTES = 512L * 1024 * 1024
    private const val MAX_EVENTS = 100_000
    private val INTERNAL_PLAYLISTS = setOf("LP_LIKED", "LP_DOWNLOADED")

    fun read(backup: InputStream, workDir: File): ImportBundle {
        val dbFile = File.createTempFile("kasane-import-", ".db", workDir)
        try {
            var found = false
            ZipInputStream(backup).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name.substringAfterLast('/') != "song.db") continue
                    var copied = 0L
                    dbFile.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buffer); if (n < 0) break
                            copied += n
                            if (copied > MAX_DB_BYTES) throw ImportFormatException("The backup database is larger than ${MAX_DB_BYTES / 1048576} MB.")
                            out.write(buffer, 0, n)
                        }
                    }
                    found = true; break
                }
            }
            if (!found) throw ImportFormatException("This is not an InnerTune-style backup: it contains no song.db.")
            return readDatabase(dbFile)
        } finally { dbFile.delete() }
    }

    internal fun readDatabase(file: File): ImportBundle {
        val db = try { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }
        catch (e: Exception) { throw ImportFormatException("song.db could not be opened as a SQLite database.") }
        db.use { return convert(it) }
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }

    private fun convert(db: SQLiteDatabase): ImportBundle {
        val warnings = mutableListOf<String>()
        val songCols = columns(db, "song")
        if ("id" !in songCols || "title" !in songCols) throw ImportFormatException("song.db has no usable song table (needs id and title columns).")
        fun opt(name: String) = if (name in songCols) "`$name`" else "NULL"
        val artists = mutableMapOf<String, MutableList<String>>()
        if (columns(db, "song_artist_map").containsAll(setOf("songId", "artistId")) && columns(db, "artist").containsAll(setOf("id", "name"))) {
            val position = if ("position" in columns(db, "song_artist_map")) "m.position" else "m.rowid"
            db.rawQuery("SELECT m.songId, a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId ORDER BY m.songId, $position", null).use { c ->
                while (c.moveToNext()) artists.getOrPut(c.getString(0)) { mutableListOf() }.add(c.getString(1))
            }
        } else warnings += "No artist table was found; tracks are matched by title and duration only."
        data class Row(val track: ImportedTrack, val liked: Boolean)
        val songs = LinkedHashMap<String, Row>()
        db.rawQuery("SELECT id, title, ${opt("duration")}, ${opt("albumName")}, ${opt("liked")} FROM song", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val duration = if (c.isNull(2)) null else c.getDouble(2).takeIf { it > 0 }
                songs[id] = Row(ImportedTrack(c.getString(1).orEmpty(), artists[id]?.joinToString(", ").orEmpty(),
                    if (c.isNull(3)) null else c.getString(3), duration, VideoIds.extract(id)), !c.isNull(4) && c.getInt(4) != 0)
            }
        }
        val playlists = mutableListOf<ImportedPlaylist>()
        if (columns(db, "playlist").containsAll(setOf("id", "name")) && columns(db, "playlist_song_map").containsAll(setOf("playlistId", "songId"))) {
            val order = if ("position" in columns(db, "playlist_song_map")) "position, rowid" else "rowid"
            db.rawQuery("SELECT id, name FROM playlist", null).use { pc ->
                while (pc.moveToNext()) {
                    val pid = pc.getString(0)
                    if (pid in INTERNAL_PLAYLISTS) continue
                    val tracks = mutableListOf<ImportedTrack>()
                    db.rawQuery("SELECT songId FROM playlist_song_map WHERE playlistId = ? ORDER BY $order", arrayOf(pid)).use { sc ->
                        while (sc.moveToNext()) songs[sc.getString(0)]?.let { tracks += it.track }
                    }
                    playlists += ImportedPlaylist(pc.getString(1).orEmpty().ifBlank { "Imported playlist" }, tracks)
                }
            }
        } else warnings += "No playlists were found in this backup."
        val history = mutableListOf<ImportedPlay>()
        val eventCols = columns(db, "event")
        if (eventCols.containsAll(setOf("songId", "timestamp"))) {
            db.rawQuery("SELECT songId, timestamp FROM event ORDER BY timestamp DESC LIMIT $MAX_EVENTS", null).use { c ->
                while (c.moveToNext()) {
                    val track = songs[c.getString(0)]?.track ?: continue
                    val at = when (c.getType(1)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(1)
                        android.database.Cursor.FIELD_TYPE_STRING -> c.getString(1).toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    history += ImportedPlay(track, at)
                }
            }
        } else warnings += "No listening history was found in this backup."
        warnings += "Settings and sign-in data in the backup were ignored."
        return ImportBundle("innertune-backup", playlists, songs.values.filter { it.liked }.map { it.track }, history, warnings)
    }
}
