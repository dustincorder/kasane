package cord.kasane.music.offline

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One row per YouTube video whose audio Kasane has cached or been asked to save.
 * Bytes live in Media3 caches, never in this table. `pinned` rows are user-saved and are
 * stored in the saved cache, which has no eviction; everything else lives in the temporary LRU cache.
 */
@Entity(tableName = "audio_files")
data class AudioFileRecord(
    @PrimaryKey val videoId: String,
    val cacheKey: String,
    val itag: Int,
    val mimeType: String,
    val contentLength: Long,
    val state: String,
    val pinned: Boolean,
    val bytesDone: Long,
    val failure: String?,
    val complete: Boolean,
    val updatedAt: Long,
    val lastPlayedAt: Long,
) {
    val audioState: AudioState get() = AudioState.fromKey(state)

    companion object {
        /** Must stay byte-compatible with Room's generated schema for [AudioFileRecord]. */
        const val CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS `audio_files` (`videoId` TEXT NOT NULL, `cacheKey` TEXT NOT NULL, " +
            "`itag` INTEGER NOT NULL, `mimeType` TEXT NOT NULL, `contentLength` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
            "`pinned` INTEGER NOT NULL, `bytesDone` INTEGER NOT NULL, `failure` TEXT, `complete` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, `lastPlayedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`))"
    }
}

enum class AudioState(val key: String) {
    CACHED("cached"), QUEUED("queued"), DOWNLOADING("downloading"), SAVED("saved"), FAILED("failed");
    companion object { fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: FAILED }
}

@Dao
interface AudioFileDao {
    @Query("SELECT * FROM audio_files WHERE videoId = :videoId")
    suspend fun get(videoId: String): AudioFileRecord?

    @Query("SELECT * FROM audio_files ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AudioFileRecord>>

    @Query("SELECT * FROM audio_files WHERE pinned = 1 ORDER BY updatedAt DESC")
    fun observePinned(): Flow<List<AudioFileRecord>>

    @Query("SELECT * FROM audio_files WHERE pinned = 1 AND state = 'queued' ORDER BY updatedAt ASC LIMIT 1")
    suspend fun nextQueued(): AudioFileRecord?

    @Query("SELECT COUNT(*) FROM audio_files WHERE pinned = 1")
    suspend fun pinnedCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: AudioFileRecord)

    @Query("UPDATE audio_files SET bytesDone = :bytes WHERE videoId = :videoId AND state = 'downloading'")
    suspend fun updateProgress(videoId: String, bytes: Long)

    @Query("UPDATE audio_files SET lastPlayedAt = :time WHERE videoId = :videoId")
    suspend fun touch(videoId: String, time: Long)

    @Query("DELETE FROM audio_files WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("DELETE FROM audio_files WHERE pinned = 0")
    suspend fun deleteUnpinned()

    @Query("UPDATE audio_files SET state = 'queued', failure = NULL WHERE state = 'downloading'")
    suspend fun resetInterrupted()

    @Query("SELECT * FROM audio_files WHERE pinned = 0")
    suspend fun unpinnedRecords(): List<AudioFileRecord>

    @Query("SELECT videoId FROM audio_files WHERE pinned = 0")
    suspend fun unpinnedIds(): List<String>
}
