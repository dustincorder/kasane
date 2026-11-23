package cord.kasane.desktop.library

import cord.kasane.music.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Liked songs and history kept on this computer, like the Android app's local library. */
class LocalLibrary(private val file: File) {
    @Serializable
    private data class Data(val favorites: List<Track> = emptyList(), val history: List<Track> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var data = runCatching { json.decodeFromString<Data>(file.readText()) }.getOrDefault(Data())
    private val _favorites = MutableStateFlow(data.favorites)
    val favorites: StateFlow<List<Track>> = _favorites.asStateFlow()
    private val _history = MutableStateFlow(data.history)
    val history: StateFlow<List<Track>> = _history.asStateFlow()

    fun isFavorite(id: String) = _favorites.value.any { it.id == id }

    fun toggleFavorite(track: Track) = save(data.copy(favorites =
        if (isFavorite(track.id)) data.favorites.filterNot { it.id == track.id } else listOf(track) + data.favorites))

    fun recordPlayed(track: Track) = save(data.copy(history = (listOf(track) + data.history.filterNot { it.id == track.id }).take(200)))

    @Synchronized
    private fun save(next: Data) {
        data = next
        _favorites.value = next.favorites
        _history.value = next.history
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(Data.serializer(), next))
        tmp.renameTo(file)
    }
}
