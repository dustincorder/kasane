package cord.kasane.music.model

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Double = 0.0,
    val thumbnailUri: String? = null,
    val streamUri: String? = null,
    val isLocal: Boolean = false,
    val sourceProvider: String = "youtube"
)
