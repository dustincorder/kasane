package cord.kasane.music.provider

import java.net.URI

/** URI validation is a platform primitive; artwork traversal/selection is common. */
internal actual fun validatedArtworkUrl(value: String): String? = runCatching { URI(value) }.getOrNull()?.takeIf {
    it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() && it.rawUserInfo == null
}?.toString()
