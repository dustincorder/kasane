package cord.kasane.music.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StorageLocation(val key: String) {
    INTERNAL("internal"), EXTERNAL("external");
    companion object { fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: INTERNAL }
}

enum class ThemeMode(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");
    companion object { fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SYSTEM }
}

/** Where the colour scheme comes from. AUTO = wallpaper colours (Android 12+) or the built-in palette. */
enum class AccentSource(val key: String) {
    AUTO("auto"), MANUAL("manual");
    companion object { fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO }
}

/** Device-local preferences. Nothing here is synced or exported with account data. */
class AppSettings(context: Context) {
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

    val themeMode: StateFlow<ThemeMode> = mode.asStateFlow()
    val accentSource: StateFlow<AccentSource> = accent.asStateFlow()
    val accentColor: StateFlow<Int> = accentArgb.asStateFlow()
    val offlineOnly: StateFlow<Boolean> = offline.asStateFlow()
    val cacheEnabled: StateFlow<Boolean> = cacheOn.asStateFlow()
    val cacheLimitMb: StateFlow<Int> = cacheLimit.asStateFlow()
    val cacheLocation: StateFlow<StorageLocation> = location.asStateFlow()
    val wifiOnlyDownloads: StateFlow<Boolean> = wifiOnly.asStateFlow()
    val offlineNoticeAccepted: StateFlow<Boolean> = consent.asStateFlow()

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
    fun setCacheLocation(value: StorageLocation) { prefs.edit().putString(CACHE_LOCATION, value.key).apply(); location.value = value }

    companion object {
        const val DEFAULT_ACCENT = 0xFF4F8BE8.toInt()
        const val DEFAULT_CACHE_MB = 1024
        const val MIN_CACHE_MB = 128
        const val MAX_CACHE_MB = 16384
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
    }
}
