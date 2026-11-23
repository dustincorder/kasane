package cord.kasane.music.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import cord.kasane.ui.components.ArtworkLoader

/** Shared artwork components draw through Coil, so memory and disk caches stay the app-wide ones. */
class CoilArtworkLoader(private val context: Context) : ArtworkLoader {
    private val decoded = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 64
    }
    override fun peek(url: String): ImageBitmap? = synchronized(decoded) { decoded[url] }
    override suspend fun load(url: String): ImageBitmap? {
        peek(url)?.let { return it }
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(url).allowHardware(false).build())
        val image = result.drawable?.toBitmap()?.asImageBitmap() ?: return null
        synchronized(decoded) { decoded[url] = image }
        return image
    }
}
