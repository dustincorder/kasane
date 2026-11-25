package cord.kasane.desktop

import cord.kasane.ui.settings.AccentSource
import cord.kasane.ui.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Preferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accentSource: AccentSource = AccentSource.AUTO,
    val accent: Long = 0xFF4F8BE8,
    /** Multiplier on top of the detected system scale; 1.0 = follow the system. */
    val uiScale: Float = 1f,
    val connectEnabled: Boolean = true,
    val volume: Float = 1f,
    /** Block every network request and play only music stored on this computer. */
    val offlineOnly: Boolean = false,
    /** Keep streamed audio in the temporary cache so recently played songs replay offline. */
    val cacheEnabled: Boolean = true,
    val cacheLimitMb: Int = 1024,
    /** Fetch release date, genre and official artwork from MusicBrainz for saved tracks. */
    val enrichMetadata: Boolean = true,
    /** First-run setup was completed (or skipped); the onboarding screen no longer shows. */
    val onboardingDone: Boolean = false,
    /** Signed-in / guest / undecided — persisted so "Continue as a guest" stays guest across restarts. */
    val sessionMode: cord.kasane.music.auth.SessionMode = cord.kasane.music.auth.SessionMode.UNDECIDED,
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
        val tmp = File(file.parentFile, file.name + ".tmp")
        val text = json.encodeToString(Preferences.serializer(), next)
        tmp.writeText(text)
        if (!tmp.renameTo(file)) file.writeText(text)
    }

    /** Shared session-mode contract — the guest choice survives restarts. */
    val sessionModeStore = object : cord.kasane.music.auth.SessionModeStore {
        override fun read() = _prefs.value.sessionMode
        override fun write(mode: cord.kasane.music.auth.SessionMode) = update { it.copy(sessionMode = mode) }
    }
}
