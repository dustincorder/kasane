package cord.kasane.music.data

import androidx.room.*
import cord.kasane.music.model.FavoriteEntity
import cord.kasane.music.model.HistoryEntity
import cord.kasane.music.model.Track
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.CatalogPage
import cord.kasane.music.model.CatalogShelf
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Aggregated listening statistic for Home; not an entity. */
data class PlayStat(val trackId: String, val title: String, val artist: String, val album: String?, val thumbnailUri: String?,
    val plays: Int, val lastPlayed: Long) {
    fun track() = Track(trackId, title, artist, album, thumbnailUri = thumbnailUri)
}

@Entity(tableName = "catalog_cache_page", primaryKeys = ["scopeKey", "cacheKey"], indices = [Index("updatedAt")])
data class CachedCatalogPageRecord(
    val scopeKey: String,
    val cacheKey: String,
    val title: String,
    val subtitle: String,
    val artwork: String?,
    val continuation: String?,
    val description: String,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Home mood chips as `title<TAB>params` lines; null for pages without chips. */
    val chips: String? = null,
    /** JSON [cord.kasane.music.model.PageMeta]: creators, stats, ownership. Null when the page has none. */
    val meta: String? = null,
)

@Entity(tableName = "catalog_cache_shelf", primaryKeys = ["scopeKey", "cacheKey", "shelfIndex"])
data class CachedCatalogShelfRecord(val scopeKey: String, val cacheKey: String, val shelfIndex: Int, val title: String, val moreBrowseId: String? = null)

@Entity(tableName = "catalog_cache_item", primaryKeys = ["itemKey"], indices = [Index("scopeKey")])
data class CachedCatalogItemEntity(
    val itemKey: String,
    val scopeKey: String,
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val artwork: String?,
    val durationSeconds: Double,
    val updatedAt: Long,
    val album: String? = null,
)

@Entity(tableName = "catalog_cache_shelf_item", primaryKeys = ["scopeKey", "cacheKey", "shelfIndex", "position"], indices = [Index("itemKey")])
data class CachedCatalogShelfItemRecord(
    val scopeKey: String, val cacheKey: String, val shelfIndex: Int, val position: Int, val itemKey: String,
)

/** Offline lyrics: provider payload kept verbatim so parser upgrades apply to stored rows too. */
@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val trackId: String,
    val providerId: String,
    val sourceRef: String?,
    val rawFormat: String,
    val rawPayload: String,
    val offsetMs: Long = 0,
    val fetchedAtMs: Long = 0,
    val userPicked: Boolean = false,
)

@Dao
interface TrackDao {
    suspend fun insertTrack(track: Track) = insertTrackRecord(TrackRecord.from(track))
    suspend fun insertTracks(tracks: List<Track>) = insertTrackRecords(tracks.map(TrackRecord::from))

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackRecord(track: TrackRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackRecords(tracks: List<TrackRecord>)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: String): Track?

    @Query("SELECT t.* FROM tracks t INNER JOIN favorites f ON t.id = f.trackId ORDER BY f.addedAt DESC")
    fun getFavorites(): Flow<List<Track>>

    @Query("SELECT t.* FROM tracks t INNER JOIN favorites f ON t.id = f.trackId ORDER BY f.addedAt DESC")
    suspend fun getFavoritesForExport(): List<Track>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE trackId = :trackId")
    suspend fun removeFavorite(trackId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackId = :trackId)")
    fun isFavorite(trackId: String): Flow<Boolean>

    /** Tracks whose audio is saved on this device. Pattern is a pre-escaped LIKE pattern (escape char \\). */
    @Query("SELECT * FROM tracks WHERE (title LIKE :pattern ESCAPE '\\' OR artist LIKE :pattern ESCAPE '\\') AND id IN (SELECT videoId FROM audio_files WHERE pinned = 1 AND state = 'saved') ORDER BY title COLLATE NOCASE LIMIT 100")
    fun searchSaved(pattern: String): Flow<List<Track>>

    @Insert
    suspend fun insertHistory(history: HistoryEntity)

    @Query("""
        SELECT h.* FROM history AS h
        WHERE h.id = (
            SELECT latest.id FROM history AS latest
            WHERE latest.trackId = h.trackId
            ORDER BY latest.playedAt DESC, latest.id DESC
            LIMIT 1
        )
        ORDER BY h.playedAt DESC, h.id DESC
        LIMIT :limit
    """)
    fun getRecentHistory(limit: Int = 50): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history ORDER BY playedAt DESC, id DESC")
    suspend fun getHistoryForExport(): List<HistoryEntity>

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    /** Play counts per track since [since], most played first. Metadata comes from the newest history row. */
    @Query("""
        SELECT h.trackId AS trackId, h.title AS title, h.artist AS artist, h.album AS album, h.thumbnailUri AS thumbnailUri,
               stats.plays AS plays, stats.lastPlayed AS lastPlayed
        FROM (SELECT trackId, COUNT(*) AS plays, MAX(playedAt) AS lastPlayed, MAX(id) AS latestId
              FROM history WHERE playedAt >= :since GROUP BY trackId) stats
        JOIN history h ON h.id = stats.latestId
        ORDER BY stats.plays DESC, stats.lastPlayed DESC
        LIMIT :limit
    """)
    fun topPlayed(since: Long, limit: Int = 40): Flow<List<PlayStat>>

    /** Tracks whose audio is saved on this device, most recently saved first. */
    @Query("SELECT t.* FROM tracks t JOIN audio_files a ON a.videoId = t.id WHERE a.pinned = 1 AND a.state = 'saved' ORDER BY a.updatedAt DESC LIMIT :limit")
    fun recentlySaved(limit: Int = 20): Flow<List<Track>>
}

/** Enriched release facts (MusicBrainz/CAA) kept per track for offline browsing. */
@Entity(tableName = "track_metadata")
data class TrackMetadataEntity(
    @PrimaryKey val trackId: String,
    val releaseTitle: String?,
    val releaseDate: String?,
    val artistDisplay: String?,
    val genres: String,
    val coverArtUrl: String?,
    val musicBrainzReleaseId: String?,
    val enrichedAtMs: Long = 0,
)

@Dao
interface TrackMetadataDao {
    @Query("SELECT * FROM track_metadata WHERE trackId = :trackId")
    suspend fun get(trackId: String): TrackMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: TrackMetadataEntity)
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE trackId = :trackId")
    suspend fun get(trackId: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LyricsEntity)

    @Query("UPDATE lyrics SET offsetMs = :offsetMs WHERE trackId = :trackId")
    suspend fun setOffset(trackId: String, offsetMs: Long)
}

@Database(
    entities = [TrackRecord::class, FavoriteEntity::class, HistoryEntity::class, CachedCatalogPageRecord::class,
        CachedCatalogShelfRecord::class, CachedCatalogItemEntity::class, CachedCatalogShelfItemRecord::class,
        cord.kasane.music.offline.AudioFileRecord::class, LyricsEntity::class, TrackMetadataEntity::class],
    version = 9,
    exportSchema = false
)
abstract class KasaneDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun audioDao(): cord.kasane.music.offline.AudioFileDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun metadataDao(): TrackMetadataDao

    @Dao
    interface CatalogCacheDao {
        @Query("SELECT * FROM catalog_cache_page WHERE scopeKey = :scopeKey AND cacheKey = :cacheKey")
        suspend fun getPage(scopeKey: String, cacheKey: String): CachedCatalogPageRecord?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun putPage(entity: CachedCatalogPageRecord)

        @Query("SELECT * FROM catalog_cache_shelf WHERE scopeKey = :scopeKey AND cacheKey = :cacheKey ORDER BY shelfIndex")
        suspend fun getShelves(scopeKey: String, cacheKey: String): List<CachedCatalogShelfRecord>

        @Query("SELECT * FROM catalog_cache_shelf_item WHERE scopeKey = :scopeKey AND cacheKey = :cacheKey ORDER BY shelfIndex, position")
        suspend fun getShelfItems(scopeKey: String, cacheKey: String): List<CachedCatalogShelfItemRecord>

        @Query("SELECT * FROM catalog_cache_item WHERE itemKey IN (:keys)")
        suspend fun getItems(keys: List<String>): List<CachedCatalogItemEntity>

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun putShelves(rows: List<CachedCatalogShelfRecord>)

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun putItems(rows: List<CachedCatalogItemEntity>)

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun putShelfItems(rows: List<CachedCatalogShelfItemRecord>)

        @Query("DELETE FROM catalog_cache_shelf_item WHERE scopeKey = :scopeKey AND cacheKey = :cacheKey")
        suspend fun deleteShelfItems(scopeKey: String, cacheKey: String)

        @Query("DELETE FROM catalog_cache_shelf WHERE scopeKey = :scopeKey AND cacheKey = :cacheKey")
        suspend fun deleteShelves(scopeKey: String, cacheKey: String)

        @Query("DELETE FROM catalog_cache_page WHERE scopeKey = :scopeKey AND cacheKey = :cacheKey")
        suspend fun deletePage(scopeKey: String, cacheKey: String)

        @Query("DELETE FROM catalog_cache_page WHERE scopeKey = :scopeKey")
        suspend fun deletePagesForScope(scopeKey: String)

        @Query("DELETE FROM catalog_cache_shelf_item WHERE scopeKey = :scopeKey")
        suspend fun deleteShelfItemsForScope(scopeKey: String)

        @Query("DELETE FROM catalog_cache_shelf WHERE scopeKey = :scopeKey")
        suspend fun deleteShelvesForScope(scopeKey: String)

        @Query("DELETE FROM catalog_cache_item WHERE scopeKey = :scopeKey")
        suspend fun deleteItemsForScope(scopeKey: String)

        @Query("DELETE FROM catalog_cache_page WHERE updatedAt < :olderThan")
        suspend fun evictPagesOlderThan(olderThan: Long)

        @Query("DELETE FROM catalog_cache_page WHERE rowid IN (SELECT rowid FROM catalog_cache_page ORDER BY updatedAt DESC LIMIT -1 OFFSET :keep)")
        suspend fun keepNewestPages(keep: Int)

        @Query("DELETE FROM catalog_cache_shelf_item WHERE NOT EXISTS (SELECT 1 FROM catalog_cache_page p WHERE p.scopeKey = catalog_cache_shelf_item.scopeKey AND p.cacheKey = catalog_cache_shelf_item.cacheKey)")
        suspend fun pruneShelfItems()

        @Query("DELETE FROM catalog_cache_shelf WHERE NOT EXISTS (SELECT 1 FROM catalog_cache_page p WHERE p.scopeKey = catalog_cache_shelf.scopeKey AND p.cacheKey = catalog_cache_shelf.cacheKey)")
        suspend fun pruneShelves()

        @Query("DELETE FROM catalog_cache_item WHERE NOT EXISTS (SELECT 1 FROM catalog_cache_shelf_item i WHERE i.itemKey = catalog_cache_item.itemKey)")
        suspend fun pruneItems()

        @Transaction
        suspend fun save(scopeKey: String, cacheKey: String, page: CatalogPage, now: Long) {
            putPage(CachedCatalogPageRecord(scopeKey, cacheKey, page.title, page.subtitle, page.artwork,
                page.continuation, page.description, now,
                page.chips.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.title.replace('\t', ' ').replace('\n', ' ') + "\t" + it.params },
                cord.kasane.music.model.PageMeta.of(page)?.let { kotlinx.serialization.json.Json.encodeToString(cord.kasane.music.model.PageMeta.serializer(), it) }))
            deleteShelfItems(scopeKey, cacheKey)
            deleteShelves(scopeKey, cacheKey)
            val itemRows = linkedMapOf<String, CachedCatalogItemEntity>()
            val shelfRows = page.shelves.mapIndexed { index, shelf -> CachedCatalogShelfRecord(scopeKey, cacheKey, index, shelf.title, shelf.moreBrowseId) }
            val linkRows = buildList {
                page.shelves.forEachIndexed { shelfIndex, shelf ->
                    shelf.items.forEachIndexed { position, item ->
                        val itemKey = cord.kasane.music.data.CatalogCache.itemKey(scopeKey, item)
                        itemRows[itemKey] = CachedCatalogItemEntity(itemKey, scopeKey, item.kind.name, item.id,
                            item.title, item.subtitle, item.artwork, item.durationSeconds, now, item.album)
                        add(CachedCatalogShelfItemRecord(scopeKey, cacheKey, shelfIndex, position, itemKey))
                    }
                }
            }
            if (shelfRows.isNotEmpty()) putShelves(shelfRows)
            if (itemRows.isNotEmpty()) putItems(itemRows.values.toList())
            if (linkRows.isNotEmpty()) putShelfItems(linkRows)
            pruneItems()
        }

        @Transaction
        suspend fun clearScope(scopeKey: String) {
            deletePagesForScope(scopeKey); deleteShelfItemsForScope(scopeKey); deleteShelvesForScope(scopeKey); deleteItemsForScope(scopeKey)
        }

        @Transaction
        suspend fun evictAndLimit(olderThan: Long, keep: Int) {
            evictPagesOlderThan(olderThan); keepNewestPages(keep); pruneShelfItems(); pruneShelves(); pruneItems()
        }
    }

    abstract fun catalogCacheDao(): CatalogCacheDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS catalog_cache (scopeKey TEXT NOT NULL, cacheKey TEXT NOT NULL, pageJson TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(scopeKey, cacheKey))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_cache_updatedAt ON catalog_cache(updatedAt)")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `lyrics` (`trackId` TEXT NOT NULL, `providerId` TEXT NOT NULL, `sourceRef` TEXT, `rawFormat` TEXT NOT NULL, `rawPayload` TEXT NOT NULL, `offsetMs` INTEGER NOT NULL DEFAULT 0, `fetchedAtMs` INTEGER NOT NULL DEFAULT 0, `userPicked` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`trackId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `track_metadata` (`trackId` TEXT NOT NULL, `releaseTitle` TEXT, `releaseDate` TEXT, `artistDisplay` TEXT, `genres` TEXT NOT NULL DEFAULT '', `coverArtUrl` TEXT, `musicBrainzReleaseId` TEXT, `enrichedAtMs` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`trackId`))")
                db.execSQL("ALTER TABLE `history` ADD COLUMN `owner` TEXT NOT NULL DEFAULT 'account'")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `history` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'track'")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `catalog_cache_item` ADD COLUMN `album` TEXT")
                // Entries written before artist/album metadata existed would keep showing blank artists.
                // Mark every cached page stale: it is still readable offline, but refetched on the next view.
                db.execSQL("UPDATE catalog_cache_page SET updatedAt = 0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `catalog_cache_page` ADD COLUMN `meta` TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `catalog_cache_shelf` ADD COLUMN `moreBrowseId` TEXT")
                db.execSQL("ALTER TABLE `catalog_cache_page` ADD COLUMN `chips` TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(cord.kasane.music.offline.AudioFileRecord.CREATE_TABLE_SQL)
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS catalog_cache")
                db.execSQL("CREATE TABLE IF NOT EXISTS catalog_cache_page (scopeKey TEXT NOT NULL, cacheKey TEXT NOT NULL, title TEXT NOT NULL, subtitle TEXT NOT NULL, artwork TEXT, continuation TEXT, description TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(scopeKey, cacheKey))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_cache_page_updatedAt ON catalog_cache_page(updatedAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS catalog_cache_shelf (scopeKey TEXT NOT NULL, cacheKey TEXT NOT NULL, shelfIndex INTEGER NOT NULL, title TEXT NOT NULL, PRIMARY KEY(scopeKey, cacheKey, shelfIndex))")
                db.execSQL("CREATE TABLE IF NOT EXISTS catalog_cache_item (itemKey TEXT NOT NULL, scopeKey TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, subtitle TEXT NOT NULL, artwork TEXT, durationSeconds REAL NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(itemKey))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_cache_item_scopeKey ON catalog_cache_item(scopeKey)")
                db.execSQL("CREATE TABLE IF NOT EXISTS catalog_cache_shelf_item (scopeKey TEXT NOT NULL, cacheKey TEXT NOT NULL, shelfIndex INTEGER NOT NULL, position INTEGER NOT NULL, itemKey TEXT NOT NULL, PRIMARY KEY(scopeKey, cacheKey, shelfIndex, position))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_cache_shelf_item_itemKey ON catalog_cache_shelf_item(itemKey)")
            }
        }
    }
}
