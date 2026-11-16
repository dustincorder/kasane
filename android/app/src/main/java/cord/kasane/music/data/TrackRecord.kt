package cord.kasane.music.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import cord.kasane.music.model.Track

/** Android persistence mapping. Column names/types remain identical to schema v3. */
@Entity(tableName = "tracks")
data class TrackRecord(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Double = 0.0,
    val thumbnailUri: String? = null,
    val streamUri: String? = null,
    val isLocal: Boolean = false,
    val sourceProvider: String = "youtube",
) {
    companion object {
        fun from(track: Track) = TrackRecord(track.id, track.title, track.artist, track.album,
            track.durationSeconds, track.thumbnailUri, track.streamUri, track.isLocal, track.sourceProvider)
    }
}
