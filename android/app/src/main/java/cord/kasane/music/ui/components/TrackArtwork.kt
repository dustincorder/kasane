package cord.kasane.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.size.Size
import cord.kasane.music.provider.ArtworkSizing

/**
 * Draws a cover at the resolution it is shown at. YouTube serves the same image at any size, so the request is
 * rewritten to the next size bucket above the measured pixel size (see [ArtworkSizing]) instead of upscaling a
 * 120px thumbnail. [minPixels] lets a hero cover ask for more than its current layout size, so a cover that is
 * still animating from the mini-player is not fetched small and then fetched again.
 */
@Composable
fun TrackArtwork(uri: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp), minPixels: Int = 0) {
    BoxWithConstraints(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val measured = with(density) { maxOf(if (constraints.hasBoundedWidth) constraints.maxWidth else 0,
            if (constraints.hasBoundedHeight) constraints.maxHeight else 0) }
        val pixels = maxOf(measured, minPixels).takeIf { it > 0 } ?: 360
        if (uri.isNullOrBlank()) {
            ArtworkUnavailable()
        } else {
            val context = LocalContext.current
            val request = remember(uri, pixels) {
                val bucket = ArtworkSizing.bucketFor(pixels)
                ImageRequest.Builder(context).data(ArtworkSizing.sized(uri, pixels)).size(Size(bucket, bucket)).crossfade(true).build()
            }
            // Coil's application-wide ImageLoader shares memory/disk caches across all screens.
            SubcomposeAsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { Box(Modifier.fillMaxSize()) },
                error = { ArtworkUnavailable() }
            )
        }
    }
}

@Composable
private fun ArtworkUnavailable() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
