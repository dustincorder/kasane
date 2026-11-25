package cord.kasane.music

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cord.kasane.music.data.KasaneDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {

    private fun helper(version: Int, onCreate: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name("migration_test.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onConfigure(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = onCreate(db)
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build())

    private fun columnNames(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val names = mutableSetOf<String>()
            val idx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) names += cursor.getString(idx)
            names
        }

    private fun tableNames(db: androidx.sqlite.db.SupportSQLiteDatabase): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            val names = mutableSetOf<String>()
            while (cursor.moveToNext()) names += cursor.getString(0)
            names
        }

    @Test
    fun migration8to9CreatesLyricsAndMetadataAndPreservesHistory() {
        val openHelper = helper(8) { db ->
            db.execSQL("CREATE TABLE `history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT, `thumbnailUri` TEXT, `playedAt` INTEGER NOT NULL, `kind` TEXT NOT NULL DEFAULT 'track')")
            db.execSQL("INSERT INTO `history` (`trackId`, `title`, `artist`, `playedAt`) VALUES ('abc12345678', 'Song', 'Artist', 42)")
        }
        val db = openHelper.writableDatabase
        KasaneDatabase.MIGRATION_8_9.migrate(db)

        assertTrue("lyrics table must exist", "lyrics" in tableNames(db))
        assertTrue("track_metadata table must exist", "track_metadata" in tableNames(db))
        assertTrue("history must gain owner", "owner" in columnNames(db, "history"))
        db.query("SELECT `trackId`, `title`, `playedAt`, `owner` FROM `history`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("abc12345678", cursor.getString(0))
            assertEquals("Song", cursor.getString(1))
            assertEquals(42L, cursor.getLong(2))
            assertEquals("account", cursor.getString(3))
        }
        openHelper.close()
    }

    @Test
    fun migration7to9AddsKindThenOwnerWithoutLosingRows() {
        val openHelper = helper(7) { db ->
            db.execSQL("CREATE TABLE `history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT, `thumbnailUri` TEXT, `playedAt` INTEGER NOT NULL)")
            db.execSQL("INSERT INTO `history` (`trackId`, `title`, `artist`, `playedAt`) VALUES ('def12345678', 'Old', 'Act', 7)")
        }
        val db = openHelper.writableDatabase
        KasaneDatabase.MIGRATION_7_8.migrate(db)
        KasaneDatabase.MIGRATION_8_9.migrate(db)

        db.query("SELECT `kind`, `owner`, `title` FROM `history`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("track", cursor.getString(0))
            assertEquals("account", cursor.getString(1))
            assertEquals("Old", cursor.getString(2))
        }
        openHelper.close()
    }
}
