package cord.kasane.ui.settings

enum class ThemeMode(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");
    companion object { fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SYSTEM }
}

/** Where the colour scheme comes from. AUTO = the platform's own colours (Material You on Android 12+) or the built-in palette. */
enum class AccentSource(val key: String) {
    AUTO("auto"), MANUAL("manual");
    companion object { fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO }
}
