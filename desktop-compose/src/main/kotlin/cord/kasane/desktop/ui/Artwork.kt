package cord.kasane.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import cord.kasane.music.provider.ArtworkSizing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image as SkiaImage

/** Small in-memory artwork cache; sizes are bucketed by the shared [ArtworkSizing]. */
class ArtworkLoader(private val http: OkHttpClient) {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 400
    }
    private val inflight = HashMap<String, CompletableDeferred<ImageBitmap?>>()

    fun peek(url: String): ImageBitmap? = synchronized(cache) { cache[url] }

    suspend fun load(url: String): ImageBitmap? {
        peek(url)?.let { return it }
        val (deferred, owner) = synchronized(inflight) {
            inflight[url]?.let { it to false } ?: CompletableDeferred<ImageBitmap?>().also { inflight[url] = it }.let { it to true }
        }
        if (!owner) return deferred.await()
        val image = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
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

val LocalArtwork = staticCompositionLocalOf<ArtworkLoader> { error("ArtworkLoader not provided") }

@Composable
fun Artwork(url: String?, size: Dp, shape: Shape, modifier: Modifier = Modifier) {
    val loader = LocalArtwork.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val sized = url?.let { ArtworkSizing.sized(it, px) }
    val bitmap by produceState(sized?.let(loader::peek), sized) { value = sized?.let { loader.load(it) } }
    Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Outlined.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
