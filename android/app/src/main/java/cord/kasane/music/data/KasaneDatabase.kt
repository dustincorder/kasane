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
)

@Entity(tableName = "catalog_cache_shelf", primaryKeys = ["scopeKey", "cacheKey", "shelfIndex"])
data class CachedCatalogShelfRecord(val scopeKey: String, val cacheKey: String, val shelfIndex: Int, val title: String)

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
)

@Entity(tableName = "catalog_cache_shelf_item", primaryKeys = ["scopeKey", "cacheKey", "shelfIndex", "position"], indices = [Index("itemKey")])
data class CachedCatalogShelfItemRecord(
    val scopeKey: String, val cacheKey: String, val shelfIndex: Int, val position: Int, val itemKey: String,
)

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: Track)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<Track>)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: String): Track?

    @Query("SELECT t.* FROM tracks t INNER JOIN favorites f ON t.id = f.trackId ORDER BY f.addedAt DESC")
    fun getFavorites(): Flow<List<Track>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE trackId = :trackId")
    suspend fun removeFavorite(trackId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackId = :trackId)")
    fun isFavorite(trackId: String): Flow<Boolean>

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
}

@Database(
    entities = [Track::class, FavoriteEntity::class, HistoryEntity::class, CachedCatalogPageRecord::class,
        CachedCatalogShelfRecord::class, CachedCatalogItemEntity::class, CachedCatalogShelfItemRecord::class],
    version = 3,
    exportSchema = false
)
abstract class KasaneDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

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
                page.continuation, page.description, now))
            deleteShelfItems(scopeKey, cacheKey)
            deleteShelves(scopeKey, cacheKey)
            val itemRows = linkedMapOf<String, CachedCatalogItemEntity>()
            val shelfRows = page.shelves.mapIndexed { index, shelf -> CachedCatalogShelfRecord(scopeKey, cacheKey, index, shelf.title) }
            val linkRows = buildList {
                page.shelves.forEachIndexed { shelfIndex, shelf ->
                    shelf.items.forEachIndexed { position, item ->
                        val itemKey = cord.kasane.music.data.CatalogCache.itemKey(scopeKey, item)
                        itemRows[itemKey] = CachedCatalogItemEntity(itemKey, scopeKey, item.kind.name, item.id,
                            item.title, item.subtitle, item.artwork, item.durationSeconds, now)
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
