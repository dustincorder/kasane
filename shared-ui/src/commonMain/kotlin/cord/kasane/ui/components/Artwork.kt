package cord.kasane.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cord.kasane.music.provider.ArtworkSizing

/** Platform image source (network + decode + caches). Android and desktop each provide one. */
interface ArtworkLoader {
    /** The cached bitmap for [url], if already decoded; lets recomposition skip the loading frame. */
    fun peek(url: String): ImageBitmap?
    suspend fun load(url: String): ImageBitmap?
}

val LocalArtworkLoader = staticCompositionLocalOf<ArtworkLoader?> { null }

/**
 * Draws a cover at the resolution it is shown at. YouTube serves the same image at any size, so the request is
 * rewritten to the next size bucket above the measured pixel size (see [ArtworkSizing]) instead of upscaling a
 * 120px thumbnail. [minPixels] lets a hero cover ask for more than its current layout size.
 */
@Composable
fun TrackArtwork(uri: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp), minPixels: Int = 0) {
    BoxWithConstraints(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val loader = LocalArtworkLoader.current
        val measured = maxOf(if (constraints.hasBoundedWidth) constraints.maxWidth else 0, if (constraints.hasBoundedHeight) constraints.maxHeight else 0)
        val pixels = maxOf(measured, minPixels).takeIf { it > 0 } ?: 360
        val sized = uri?.takeIf { it.isNotBlank() }?.let { ArtworkSizing.sized(it, pixels) }
        val bitmap by produceState(sized?.let { loader?.peek(it) }, sized, loader) { value = sized?.let { loader?.load(it) } ?: value }
        val image = bitmap
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

