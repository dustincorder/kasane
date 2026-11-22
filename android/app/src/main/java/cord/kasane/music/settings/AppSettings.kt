package cord.kasane.music.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StorageLocation(val key: String) {
    INTERNAL("internal"), EXTERNAL("external");
    companion object { fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: INTERNAL }
}

/** Device-local preferences. Nothing here is synced or exported with account data. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("kasane_settings", Context.MODE_PRIVATE)

    private val dynamic = MutableStateFlow(prefs.getBoolean(DYNAMIC_COLOR, false))
    private val offline = MutableStateFlow(prefs.getBoolean(OFFLINE_ONLY, false))
    private val cacheOn = MutableStateFlow(prefs.getBoolean(CACHE_ENABLED, true))
    private val cacheLimit = MutableStateFlow(prefs.getInt(CACHE_LIMIT_MB, DEFAULT_CACHE_MB).coerceIn(MIN_CACHE_MB, MAX_CACHE_MB))
    private val location = MutableStateFlow(StorageLocation.fromKey(prefs.getString(CACHE_LOCATION, null)))

    val dynamicColor: StateFlow<Boolean> = dynamic.asStateFlow()
    val offlineOnly: StateFlow<Boolean> = offline.asStateFlow()
    val cacheEnabled: StateFlow<Boolean> = cacheOn.asStateFlow()
    val cacheLimitMb: StateFlow<Int> = cacheLimit.asStateFlow()
    val cacheLocation: StateFlow<StorageLocation> = location.asStateFlow()

    fun setDynamicColor(value: Boolean) { prefs.edit().putBoolean(DYNAMIC_COLOR, value).apply(); dynamic.value = value }
    fun setOfflineOnly(value: Boolean) { prefs.edit().putBoolean(OFFLINE_ONLY, value).apply(); offline.value = value }
    fun setCacheEnabled(value: Boolean) { prefs.edit().putBoolean(CACHE_ENABLED, value).apply(); cacheOn.value = value }
    fun setCacheLimitMb(value: Int) {
        val clamped = value.coerceIn(MIN_CACHE_MB, MAX_CACHE_MB)
        prefs.edit().putInt(CACHE_LIMIT_MB, clamped).apply(); cacheLimit.value = clamped
    }
    fun setCacheLocation(value: StorageLocation) { prefs.edit().putString(CACHE_LOCATION, value.key).apply(); location.value = value }

    companion object {
        const val DEFAULT_CACHE_MB = 1024
        const val MIN_CACHE_MB = 128
        const val MAX_CACHE_MB = 16384
        private const val DYNAMIC_COLOR = "dynamicColor"
        private const val OFFLINE_ONLY = "offlineOnly"
        private const val CACHE_ENABLED = "cacheEnabled"
        private const val CACHE_LIMIT_MB = "cacheLimitMb"
        private const val CACHE_LOCATION = "cacheLocation"
    }
}
