package cord.kasane.music.data

import androidx.room.*
import cord.kasane.music.model.FavoriteEntity
import cord.kasane.music.model.HistoryEntity
import cord.kasane.music.model.Track
import kotlinx.coroutines.flow.Flow

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
    entities = [Track::class, FavoriteEntity::class, HistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class KasaneDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
}
