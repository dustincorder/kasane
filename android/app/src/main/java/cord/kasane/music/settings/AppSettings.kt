package cord.kasane.music.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StorageLocation(val key: String) {
    INTERNAL("internal"), EXTERNAL("external");
    companion object { fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: INTERNAL }
}

typealias ThemeMode = cord.kasane.ui.settings.ThemeMode
typealias AccentSource = cord.kasane.ui.settings.AccentSource

/** Device-local preferences. Nothing here is synced or exported with account data. */
class AppSettings(context: Context) : cord.kasane.music.auth.SessionModeStore {
    private val prefs = context.applicationContext.getSharedPreferences("kasane_settings", Context.MODE_PRIVATE)

    private val mode = MutableStateFlow(ThemeMode.fromKey(prefs.getString(THEME_MODE, null)))
    private val accent = MutableStateFlow(
        // Dynamic colour is the default. A user who explicitly turned it off in the earlier build keeps a manual accent.
        if (prefs.contains(DYNAMIC_COLOR) && !prefs.getBoolean(DYNAMIC_COLOR, true)) AccentSource.MANUAL
        else AccentSource.fromKey(prefs.getString(ACCENT_SOURCE, null)))
    private val accentArgb = MutableStateFlow(prefs.getInt(ACCENT_COLOR, DEFAULT_ACCENT))
    private val offline = MutableStateFlow(prefs.getBoolean(OFFLINE_ONLY, false))
    private val cacheOn = MutableStateFlow(prefs.getBoolean(CACHE_ENABLED, true))
    private val cacheLimit = MutableStateFlow(prefs.getInt(CACHE_LIMIT_MB, DEFAULT_CACHE_MB).coerceIn(MIN_CACHE_MB, MAX_CACHE_MB))
    private val location = MutableStateFlow(StorageLocation.fromKey(prefs.getString(CACHE_LOCATION, null)))
    private val wifiOnly = MutableStateFlow(prefs.getBoolean(WIFI_ONLY_DOWNLOADS, true))
    private val consent = MutableStateFlow(prefs.getBoolean(OFFLINE_NOTICE_ACCEPTED, false))
    private val connect = MutableStateFlow(prefs.getBoolean(CONNECT_ENABLED, true))
    private val historyOn = MutableStateFlow(prefs.getBoolean(SAVE_HISTORY, true))
    private val guestHistoryOn = MutableStateFlow(prefs.getBoolean(GUEST_SAVE_HISTORY, false))
    private val enrichMeta = MutableStateFlow(prefs.getBoolean(ENRICH_METADATA, true))
    private val autoplay = MutableStateFlow(
        if (prefs.contains(AUTOPLAY)) prefs.getBoolean(AUTOPLAY, true)
        // Legacy home of the setting — migrate the value, do not reset user preference.
        else context.applicationContext.getSharedPreferences("playback", Context.MODE_PRIVATE).getBoolean("autoplay", true))

    val themeMode: StateFlow<ThemeMode> = mode.asStateFlow()
    val accentSource: StateFlow<AccentSource> = accent.asStateFlow()
    val accentColor: StateFlow<Int> = accentArgb.asStateFlow()
    val offlineOnly: StateFlow<Boolean> = offline.asStateFlow()
    val cacheEnabled: StateFlow<Boolean> = cacheOn.asStateFlow()
    val cacheLimitMb: StateFlow<Int> = cacheLimit.asStateFlow()
    val cacheLocation: StateFlow<StorageLocation> = location.asStateFlow()
    val wifiOnlyDownloads: StateFlow<Boolean> = wifiOnly.asStateFlow()
    val offlineNoticeAccepted: StateFlow<Boolean> = consent.asStateFlow()
    /** LAN remote control and playback handoff (Kasane Connect). */
    val connectEnabled: StateFlow<Boolean> = connect.asStateFlow()
    private val onboarded = MutableStateFlow(prefs.getBoolean(ONBOARDING_DONE, false))
    /** The first-run welcome flow has been completed or skipped. */
    val onboardingDone: StateFlow<Boolean> = onboarded.asStateFlow()
    /** Signed-in history recording (shown under Data & privacy). */
    val saveListeningHistory: StateFlow<Boolean> = historyOn.asStateFlow()
    /** Guest history is opt-in and never merged with the account's entries. */
    val guestSaveHistory: StateFlow<Boolean> = guestHistoryOn.asStateFlow()

    fun historyRecordingEnabled(signedIn: Boolean) = if (signedIn) historyOn.value else guestHistoryOn.value
    /** MusicBrainz/CAA enrichment for saved tracks (official cover, release date, genre). */
    val enrichMetadata: StateFlow<Boolean> = enrichMeta.asStateFlow()
    /** Radio fill for single songs and continuation after collections. */
    val autoplayFlow: StateFlow<Boolean> = autoplay.asStateFlow()

    fun setThemeMode(value: ThemeMode) { prefs.edit().putString(THEME_MODE, value.key).apply(); mode.value = value }
    fun setAccentSource(value: AccentSource) { prefs.edit().putString(ACCENT_SOURCE, value.key).remove(DYNAMIC_COLOR).apply(); accent.value = value }
    fun setAccentColor(argb: Int) {
        prefs.edit().putInt(ACCENT_COLOR, argb).putString(ACCENT_SOURCE, AccentSource.MANUAL.key).remove(DYNAMIC_COLOR).apply()
        accentArgb.value = argb; accent.value = AccentSource.MANUAL
    }
    fun setOfflineOnly(value: Boolean) { prefs.edit().putBoolean(OFFLINE_ONLY, value).apply(); offline.value = value }
    fun setCacheEnabled(value: Boolean) { prefs.edit().putBoolean(CACHE_ENABLED, value).apply(); cacheOn.value = value }
    fun setCacheLimitMb(value: Int) {
        val clamped = value.coerceIn(MIN_CACHE_MB, MAX_CACHE_MB)
        prefs.edit().putInt(CACHE_LIMIT_MB, clamped).apply(); cacheLimit.value = clamped
    }
    fun setWifiOnlyDownloads(value: Boolean) { prefs.edit().putBoolean(WIFI_ONLY_DOWNLOADS, value).apply(); wifiOnly.value = value }
    fun acceptOfflineNotice() { prefs.edit().putBoolean(OFFLINE_NOTICE_ACCEPTED, true).apply(); consent.value = true }
    fun setConnectEnabled(value: Boolean) { prefs.edit().putBoolean(CONNECT_ENABLED, value).apply(); connect.value = value }
    fun completeOnboarding() { prefs.edit().putBoolean(ONBOARDING_DONE, true).apply(); onboarded.value = true }
    fun setCacheLocation(value: StorageLocation) { prefs.edit().putString(CACHE_LOCATION, value.key).apply(); location.value = value }
    fun setSaveListeningHistory(value: Boolean) { prefs.edit().putBoolean(SAVE_HISTORY, value).apply(); historyOn.value = value }
    fun setGuestSaveHistory(value: Boolean) { prefs.edit().putBoolean(GUEST_SAVE_HISTORY, value).apply(); guestHistoryOn.value = value }
    fun setEnrichMetadata(value: Boolean) { prefs.edit().putBoolean(ENRICH_METADATA, value).apply(); enrichMeta.value = value }
    fun setAutoplay(value: Boolean) { prefs.edit().putBoolean(AUTOPLAY, value).apply(); autoplay.value = value }

    /** Timestamp of the last OTA check — backs the shared checker's rate limit. */
    fun readUpdateCheckMs(): Long = prefs.getLong(LAST_UPDATE_CHECK_MS, 0)
    fun writeUpdateCheckMs(ms: Long) { prefs.edit().putLong(LAST_UPDATE_CHECK_MS, ms).apply() }

    /** [SessionModeStore]: guest stays guest across restarts (shared SessionPolicy). */
    override fun read(): cord.kasane.music.auth.SessionMode =
        runCatching { cord.kasane.music.auth.SessionMode.valueOf(prefs.getString(SESSION_MODE, null).orEmpty()) }
            .getOrDefault(cord.kasane.music.auth.SessionMode.UNDECIDED)
    override fun write(mode: cord.kasane.music.auth.SessionMode) { prefs.edit().putString(SESSION_MODE, mode.name).apply() }
    fun sessionMode() = read()
    fun setSessionMode(mode: cord.kasane.music.auth.SessionMode) = write(mode)

    companion object {
        const val DEFAULT_ACCENT = 0xFF4F8BE8.toInt()
        const val DEFAULT_CACHE_MB = 1024
        const val MIN_CACHE_MB = 128
        const val MAX_CACHE_MB = 65536
        private const val DYNAMIC_COLOR = "dynamicColor"
        private const val THEME_MODE = "themeMode"
        private const val ACCENT_SOURCE = "accentSource"
        private const val ACCENT_COLOR = "accentColor"
        private const val OFFLINE_ONLY = "offlineOnly"
        private const val CACHE_ENABLED = "cacheEnabled"
        private const val CACHE_LIMIT_MB = "cacheLimitMb"
        private const val CACHE_LOCATION = "cacheLocation"
        private const val WIFI_ONLY_DOWNLOADS = "wifiOnlyDownloads"
        private const val OFFLINE_NOTICE_ACCEPTED = "offlineNoticeAccepted"
        private const val CONNECT_ENABLED = "connectEnabled"
        private const val ONBOARDING_DONE = "onboardingDone"
        private const val SAVE_HISTORY = "saveListeningHistory"
        private const val GUEST_SAVE_HISTORY = "guestSaveListeningHistory"
        private const val ENRICH_METADATA = "enrichMetadata"
        private const val AUTOPLAY = "autoplay"
        private const val SESSION_MODE = "sessionMode"
        private const val LAST_UPDATE_CHECK_MS = "lastUpdateCheckMs"
    }
}
