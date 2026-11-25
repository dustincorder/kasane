package cord.kasane.desktop.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import cord.kasane.ui.components.ArtworkLoader
import cord.kasane.ui.components.TrackArtwork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image as SkiaImage

/** Desktop image source for the shared artwork component: OkHttp, Skia decode, in-memory LRU. */
class DesktopArtworkLoader(private val http: OkHttpClient) : ArtworkLoader {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 400
    }
    private val inflight = HashMap<String, CompletableDeferred<ImageBitmap?>>()

    override fun peek(url: String): ImageBitmap? = synchronized(cache) { cache[url] }

    override suspend fun load(url: String): ImageBitmap? {
        peek(url)?.let { return it }
        val (deferred, owner) = synchronized(inflight) {
            inflight[url]?.let { it to false } ?: CompletableDeferred<ImageBitmap?>().also { inflight[url] = it }.let { it to true }
        }
        if (!owner) return deferred.await()
        val image = withContext(Dispatchers.IO) {
            runCatching {
                if (url.startsWith("file:")) SkiaImage.makeFromEncoded(java.io.File(java.net.URI(url)).readBytes()).toComposeImageBitmap()
                else http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.bytes()?.let { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }
                }
            }.getOrNull()
        }
        image?.let { synchronized(cache) { cache[url] = it } }
        synchronized(inflight) { inflight.remove(url) }
        deferred.complete(image)
        return image
    }
}

@Composable
fun Artwork(url: String?, size: Dp, shape: Shape, modifier: Modifier = Modifier) =
    TrackArtwork(url, modifier.size(size), shape)
