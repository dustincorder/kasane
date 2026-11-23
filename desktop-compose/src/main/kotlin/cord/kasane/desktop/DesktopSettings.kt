package cord.kasane.desktop

import cord.kasane.desktop.ui.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Preferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Long = 0xFF4F8BE8,
    val connectEnabled: Boolean = true,
    val volume: Float = 1f,
)

class DesktopSettings(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _prefs = MutableStateFlow(runCatching { json.decodeFromString<Preferences>(file.readText()) }.getOrDefault(Preferences()))
    val prefs: StateFlow<Preferences> = _prefs.asStateFlow()

    @Synchronized
    fun update(change: (Preferences) -> Preferences) {
        val next = change(_prefs.value)
        _prefs.value = next
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Preferences.serializer(), next))
    }
}
