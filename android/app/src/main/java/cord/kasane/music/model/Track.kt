package cord.kasane.music.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey
    val trackId: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val thumbnailUri: String? = null,
    val playedAt: Long = System.currentTimeMillis()
)
